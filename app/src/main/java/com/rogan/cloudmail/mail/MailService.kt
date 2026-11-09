package com.rogan.cloudmail.mail

import android.content.Context
import com.rogan.cloudmail.data.*
import java.util.*
import javax.mail.BodyPart
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.*

object MailService {

    /* ---------- 会话/连接 ---------- */

    private fun imapProps(acc: Account) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", acc.imapHost)
        put("mail.imaps.port", acc.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "20000")
        put("mail.imaps.timeout", "30000")
        put("mail.mime.decodetext.strict", "false")
    }

    private fun smtpProps(acc: Account) = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", acc.smtpHost)
        put("mail.smtp.port", acc.smtpPort.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.ssl.enable", (acc.smtpPort == 465).toString())
        put("mail.smtp.starttls.enable", (acc.smtpPort == 587).toString())
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.ssl.trust", acc.smtpHost)
    }

    fun connectImap(acc: Account): Store {
        val session = Session.getInstance(imapProps(acc))
        val store = session.getStore("imaps")
        store.connect(acc.imapHost, acc.imapPort, acc.email, acc.password)
        return store
    }

    /** 各服务商「已发送」文件夹名称不同，逐一尝试 */
    private fun sentFolder(store: Store): Folder? {
        for (name in listOf("Sent", "Sent Messages", "Sent Items", "已发送")) {
            val f = runCatching { store.getFolder(name) }.getOrNull()
            if (f != null && f.exists()) return f
        }
        return null
    }

    /** 登录验证，失败抛异常(消息可直接展示给用户) */
    fun verify(acc: Account) {
        connectImap(acc).use { it.getFolder("INBOX").open(Folder.READ_ONLY) }
    }

    /* ---------- 收件同步 ---------- */

    /** 同步单个账号，返回新收到的邮件(用于通知) */
    suspend fun syncAccount(ctx: Context, acc: Account, db: AppDatabase): List<MailMessage> {
        val newInbox = mutableListOf<MailMessage>()
        connectImap(acc).use { store ->
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val msgs = inbox.messages
            for (m in msgs.toList().takeLast(50)) {
                if (m.isExpunged) continue
                val uid = (inbox as UIDFolder).getUID(m).toString()
                if (db.messages().byUid(acc.id, "INBOX", uid) != null) continue
                val ent = toEntity(acc.id, "INBOX", uid, m, ctx)
                db.messages().insert(ent)
                newInbox.add(ent)
            }
            inbox.close(false)

            // 同步服务器上已有的已发送邮件
            runCatching {
                val sent = sentFolder(store)
                if (sent != null) {
                    sent.open(Folder.READ_ONLY)
                    for (m in sent.messages.toList().takeLast(20)) {
                        if (m.isExpunged) continue
                        val uid = (sent as UIDFolder).getUID(m).toString()
                        if (db.messages().byUid(acc.id, "SENT", uid) == null)
                            db.messages().insert(toEntity(acc.id, "SENT", uid, m, ctx))
                    }
                    sent.close(false)
                }
            }
        }
        return newInbox
    }

    private fun toEntity(accId: Long, folder: String, uid: String, m: Message, ctx: Context): MailMessage {
        val from = m.from?.firstOrNull() as? InternetAddress
        val subject = runCatching { m.subject ?: "" }.getOrDefault("")
        val date = runCatching { m.sentDate?.time ?: m.receivedDate?.time ?: System.currentTimeMillis() }
            .getOrDefault(System.currentTimeMillis())
        val to = runCatching { m.getRecipients(Message.RecipientType.TO)?.joinToString(",") { (it as InternetAddress).address } }.getOrDefault("")
        val cc = runCatching { m.getRecipients(Message.RecipientType.CC)?.joinToString(",") { (it as InternetAddress).address } }.getOrDefault("")
        val ad = runCatching { m.getHeader("List-Unsubscribe")?.isNotEmpty() == true }.getOrDefault(false) ||
                listOf("促销", "优惠", "广告", "退订", "营销", "unsubscribe").any { subject.lowercase().contains(it.lowercase()) }
        return MailMessage(
            accountId = accId, folder = folder, uid = uid,
            messageId = runCatching { m.getHeader("Message-ID")?.firstOrNull() ?: "" }.getOrDefault(""),
            subject = subject,
            fromAddr = from?.address ?: "", fromName = from?.personal ?: from?.address ?: "",
            toAddr = to ?: "", ccAddr = cc ?: "", date = date,
            bodyHtml = "", snippet = subject,
            isRead = runCatching { m.isSet(Flags.Flag.SEEN) }.getOrDefault(false),
            isStarred = false, isAd = ad, hasAttachments = false,
            attachmentsJson = "[]", loaded = false,
            size = runCatching { m.size.toLong() }.getOrDefault(0L)
        )
    }

    /* ---------- 正文与附件 ---------- */

    /** 拉取完整正文+附件到本地，返回更新后的实体 */
    suspend fun loadFull(ctx: Context, acc: Account, msg: MailMessage, db: AppDatabase): MailMessage {
        if (msg.loaded) return msg
        connectImap(acc).use { store ->
            val f = store.getFolder(if (msg.folder == "SENT") "Sent" else "INBOX")
            if (!f.exists()) return msg
            f.open(Folder.READ_ONLY)
            val uidFolder = f as UIDFolder
            val m = uidFolder.getMessageByUID(msg.uid.toLong()) ?: return msg
            val atts = mutableListOf<AttInfo>()
            val body = extractBody(ctx, m, msg.id, atts)
            val updated = msg.copy(
                bodyHtml = body,
                snippet = android.text.Html.fromHtml(
                    body.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""),
                    android.text.Html.FROM_HTML_MODE_LEGACY
                ).toString().take(120),
                hasAttachments = atts.isNotEmpty(),
                attachmentsJson = org.json.JSONArray().apply { atts.forEach { put(org.json.JSONObject(it.toJson())) } }.toString(),
                loaded = true
            )
            db.messages().update(updated)
            f.close(false)
            // 真实已读回执(MDN)：对方要求回执且这是收件时,回执一次
            runCatching {
                val rrt = m.getHeader("Disposition-Notification-To")?.firstOrNull()
                if (!rrt.isNullOrBlank()) sendMdn(ctx, acc, msg, rrt)
            }
            return updated
        }
    }

    private fun fileStore(ctx: Context, msgId: Long) =
        java.io.File(ctx.filesDir, "att/$msgId").apply { mkdirs() }

    private fun extractBody(ctx: Context, part: Part, msgId: Long, atts: MutableList<AttInfo>): String {
        return try {
            if (part.isMimeType("multipart/*")) {
                val mp = part.content as javax.mail.Multipart
                // 优先找 multipart/alternative 中的 html
                var htmlFallback = ""
                for (i in 0 until mp.count) {
                    val p = mp.getBodyPart(i)
                    if (p.isMimeType("multipart/alternative")) return extractBody(ctx, p, msgId, atts)
                    if (p.isMimeType("text/html")) { htmlFallback = p.content.toString() }
                    else if (p.isMimeType("text/plain") && htmlFallback.isEmpty()) { /* 继续找 html */ }
                    else if (p.isMimeType("multipart/*")) return extractBody(ctx, p, msgId, atts)
                    else collectAttachment(ctx, p, msgId, atts)
                }
                if (htmlFallback.isNotEmpty()) return htmlFallback
                // 全是纯文本
                for (i in 0 until mp.count) {
                    val p = mp.getBodyPart(i)
                    if (p.isMimeType("text/plain")) {
                        val t = p.content.toString()
                        return "<pre style=\"white-space:pre-wrap;font-family:sans-serif\">${esc(t)}</pre>"
                    }
                }
                htmlFallback
            } else if (part.isMimeType("text/html")) {
                part.content.toString()
            } else if (part.isMimeType("text/plain")) {
                val t = part.content.toString()
                "<pre style=\"white-space:pre-wrap;font-family:sans-serif\">${esc(t)}</pre>"
            } else {
                collectAttachment(ctx, part, msgId, atts)
                ""
            }
        } catch (e: Exception) { "" }
    }

    private fun collectAttachment(ctx: Context, p: Part, msgId: Long, atts: MutableList<AttInfo>) {
        runCatching {
            val disp = p.disposition
            val cid = runCatching { p.getHeader("Content-ID")?.firstOrNull()?.trim('\"', '<', '>') ?: "" }.getOrDefault("")
            val fileName = javax.mail.internet.MimeUtility.decodeText(p.fileName ?: "附件")
            if (disp == null && cid.isEmpty()) return
            if (disp != null && disp.equals(Part.INLINE, true) && fileName.isEmpty() && cid.isEmpty()) return
            val dir = fileStore(ctx, msgId)
            val safe = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "file_${atts.size}" }
            val out = java.io.File(dir, "${atts.size}_$safe")
            p.dataHandler?.inputStream?.use { ins -> out.outputStream().use { ins.copyTo(it) } }
            val mime = p.contentType.substringBefore(';').trim()
            atts.add(AttInfo(safe, out.absolutePath, mime, cid.isNotEmpty(), cid))
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /* ---------- 发送 ---------- */

    data class SendRequest(
        val to: List<String>,
        val cc: List<String>,
        val bcc: List<String>,
        val subject: String,
        val bodyHtml: String,
        val attachments: List<AttInfo>,
        val readReceipt: Boolean,
        val cardHtml: String
    )

    fun sendMail(acc: Account, req: SendRequest): String {
        val session = Session.getInstance(smtpProps(acc))
        val mime = MimeMessage(session)
        mime.setFrom(InternetAddress(acc.email, acc.nickname.ifBlank { acc.email }))
        req.to.filter { it.isNotBlank() }.forEach { mime.addRecipient(Message.RecipientType.TO, InternetAddress(it.trim())) }
        req.cc.filter { it.isNotBlank() }.forEach { mime.addRecipient(Message.RecipientType.CC, InternetAddress(it.trim())) }
        req.bcc.filter { it.isNotBlank() }.forEach { mime.addRecipient(Message.RecipientType.BCC, InternetAddress(it.trim())) }
        mime.subject = req.subject
        mime.sentDate = Date()
        if (req.readReceipt) mime.setHeader("Disposition-Notification-To", acc.email)

        val container = MimeMultipart("mixed")
        val content = MimeMultipart("alternative")

        val fullBody = req.bodyHtml + req.cardHtml
        val textPart = MimeBodyPart()
        textPart.setText(plainOf(fullBody), "utf-8")
        val htmlPart = MimeBodyPart()
        htmlPart.setContent(fullBody, "text/html;charset=utf-8")
        content.addBodyPart(textPart)
        content.addBodyPart(htmlPart)
        val wrap = MimeBodyPart()
        wrap.setContent(content)
        container.addBodyPart(wrap)

        // 附件(内联图片用 cid 引用)
        req.attachments.forEach { a ->
            val bp = MimeBodyPart()
            bp.attachFile(java.io.File(a.path))
            bp.fileName = MimeUtility.encodeText(a.name)
            if (a.isInline && a.cid.isNotBlank()) {
                bp.contentID = "<${a.cid}>"
                bp.disposition = Part.INLINE
            }
            container.addBodyPart(bp)
        }
        mime.setContent(container)
        Transport.send(mime, acc.email, acc.password)

        // 保存到已发送 (IMAP APPEND)
        runCatching {
            connectImap(acc).use { store ->
                val f = sentFolder(store)
                if (f != null) {
                    f.open(Folder.READ_WRITE)
                    f.appendMessages(arrayOf(mime))
                    f.close(false)
                }
            }
        }
        return mime.messageID ?: ""
    }

    private fun plainOf(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }

    /* ---------- 撤回 ---------- */

    /**
     * 邮件协议本身不支持真正"从对方收件箱移除"。撤回采取两层真实操作：
     * 1) 从本方服务器 Sent 文件夹删除原始邮件（服务器级真实删除）；
     * 2) 向原收件人发送一封系统样式的「邮件撤回」通知（与 QQ 邮箱内部撤回提示一致的体验）。
     * 同域邮箱（如 QQ→QQ、163→163）部分服务商网页端可真正撤回，第三方客户端无法调用其私有接口，此处为协议内的最大能力。
     */
    fun recall(acc: Account, messageId: String, originalTo: String, subject: String): Pair<Boolean, String> {
        var deletedFromServer = false
        if (messageId.isNotBlank()) {
            runCatching {
                connectImap(acc).use { store ->
                    val f = sentFolder(store)
                    if (f != null) {
                        f.open(Folder.READ_WRITE)
                        val msgs = f.search(javax.mail.search.MessageIDTerm(messageId))
                        msgs.forEach { it.setFlag(Flags.Flag.DELETED, true) }
                        deletedFromServer = msgs.isNotEmpty()
                        f.close(true)
                    }
                }
            }
        }
        // 发送撤回通知
        val html = """
        <div style="border:1px solid #ddd;padding:16px;border-radius:8px;font-family:sans-serif">
          <h3 style="color:#1565C0;margin:0 0 8px">邮件撤回通知</h3>
          <p>发件人 <b>${esc(acc.nickname.ifBlank { acc.email })}</b> 已撤回此前发送的邮件：<br/>
          <b>《${esc(subject)}》</b></p>
          <p style="color:#888;font-size:13px">此邮件内容已被发件人撤回，请勿参考其中内容。</p>
        </div>
        """.trimIndent()
        val req = SendRequest(
            to = listOf(originalTo), cc = emptyList(), bcc = emptyList(),
            subject = "【撤回】$subject", bodyHtml = html,
            attachments = emptyList(), readReceipt = false, cardHtml = ""
        )
        val notified = runCatching { sendMail(acc, req); true }.getOrDefault(false)
        val tips = buildString {
            if (deletedFromServer) append("已从您的服务器「已发送」中删除原邮件。")
            else append("原邮件在服务器上未能删除(可能已被服务器清理)。")
            append(if (notified) "已向收件人发送撤回通知。" else "撤回通知发送失败。")
            if (sameDomain(acc.email, originalTo))
                append("双方为同一邮箱服务商，服务商网页版可能支持彻底撤回。")
            else append("跨服务商邮件受协议限制，无法从对方服务器物理移除，只能通知对方。")
        }
        return notified to tips
    }

    fun sameDomain(a: String, b: String): Boolean =
        a.substringAfter('@').lowercase() == b.substringAfter('@').lowercase()

    /* ---------- 服务器端删除(已发送里撤回场景) ---------- */

    fun deleteFromServer(acc: Account, folder: String, uid: String): Boolean = runCatching {
        connectImap(acc).use { store ->
            val f = if (folder == "SENT") sentFolder(store) ?: return false else store.getFolder("INBOX")
            f.open(Folder.READ_WRITE)
            val m = (f as UIDFolder).getMessageByUID(uid.toLong())
            val ok = m != null
            m?.setFlag(Flags.Flag.DELETED, true)
            f.close(true)
            ok
        }
    }.getOrDefault(false)

    /** 真实发送 MDN 已读回执(按 RFC 2298) */
    private fun sendMdn(ctx: Context, acc: Account, msg: MailMessage, rrt: String) {
        runCatching {
            val session = Session.getInstance(smtpProps(acc))
            val mdn = MimeMessage(session)
            mdn.setFrom(InternetAddress(acc.email, acc.nickname.ifBlank { acc.email }))
            mdn.setRecipient(Message.RecipientType.TO, InternetAddress(rrt.trim()))
            mdn.subject = "已读：${msg.subject.ifBlank { "(无主题)" }}"
            mdn.setHeader("Content-Type", "multipart/report; report-type=disposition-notification")
            val mp = MimeMultipart("report")
            val notif = MimeBodyPart()
            notif.setContent(
                "Reporting-UA: CloudMail/1.1\r\n" +
                "Final-Recipient: rfc822; ${msg.toAddr}\r\n" +
                "Original-Message-ID: <${msg.messageId}>\r\n" +
                "Disposition: automatic-action/MDN-sent-automatically; displayed\r\n",
                "text/plain; charset=utf-8")
            mp.addBodyPart(notif)
            val human = MimeBodyPart()
            human.setText("${acc.nickname.ifBlank { acc.email }} 已阅读您的邮件《${msg.subject.ifBlank { "(无主题)" }}》。", "utf-8")
            mp.addBodyPart(human)
            mdn.setContent(mp)
            Transport.send(mdn, acc.email, acc.password)
        }
    }

    /** SMTP/网络错误转成用户能看懂的中文原因 */
    fun smtpErrorText(e: Exception): String = when (e) {
        is com.sun.mail.smtp.SMTPAddressFailedException ->
            when {
                e.returnCode == 550 && e.message?.contains("quotation") == true -> "对方邮箱不存在或已停用"
                e.returnCode == 550 -> "对方服务器拒收(邮箱不存在/被拉黑/不收外部邮件)：${e.address ?: ""}"
                e.returnCode == 552 -> "对方邮箱存储已满，无法投递"
                e.returnCode == 551 -> "对方地址已变更，服务器拒绝接收"
                e.returnCode == 553 -> "对方服务器拒绝，可能本方邮箱/域名被列入黑名单"
                e.returnCode == 451 -> "对方服务器临时故障，请稍后重试"
                else -> "对方服务器拒绝投递(${e.returnCode})：${e.message ?: ""}"
            }
        is com.sun.mail.smtp.SMTPSendFailedException ->
            when (e.returnCode) {
                550 -> "对方邮箱不存在或服务器拒收"
                552 -> "对方邮箱存储已满"
                553, 554 -> "服务器拒绝发送(可能邮箱/域名被限制)"
                else -> "服务器返回错误(${e.returnCode})：${e.message ?: ""}"
            }
        is javax.mail.AuthenticationFailedException -> "邮箱登录失败：账号或授权码不正确(发送失败)"
        is java.net.UnknownHostException -> "收件邮箱域名不存在(服务器地址无法解析)"
        is java.net.ConnectException -> "无法连接对方服务器(网络或服务器不可用)"
        is java.net.SocketTimeoutException -> "连接对方服务器超时，请稍后重试"
        is javax.mail.SendFailedException -> {
            val inv = e.invalidAddresses?.joinToString("、") { it.toString() } ?: ""
            val vali = e.validSentAddresses?.joinToString("、") { it.toString() } ?: ""
            buildString {
                append("发送失败")
                if (inv.isNotBlank()) append("，以下地址无效：$inv")
                if (vali.isNotBlank()) append("，以下地址已成功送达：$vali")
                append(e.message?.takeIf { it.isNotBlank() }?.let { "：${it.take(120)}" } ?: "")
            }
        }
        is javax.mail.MessagingException -> "邮件发送失败：${e.message ?: "未知协议错误"}"
        else -> "发送失败：${e.message ?: e.javaClass.simpleName}"
    }
}

private typealias UIDFolder = com.sun.mail.imap.IMAPFolder
