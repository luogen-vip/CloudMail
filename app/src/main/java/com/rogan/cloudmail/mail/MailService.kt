package com.rogan.cloudmail.mail

import android.content.Context
import com.rogan.cloudmail.data.*
import kotlinx.coroutines.sync.withLock
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

    /* ---------- OAuth2 (Outlook 等) ---------- */

    const val MS_CLIENT_ID = "9e5f94bc-e8a4-4e73-b8be-63364c29d753"   // Thunderbird 公开客户端
    const val MS_REDIRECT = "https://localhost"
    const val MS_SCOPES = "openid profile email offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send"

    fun msAuthorizeUrl(codeChallenge: String) = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize" +
            "?client_id=$MS_CLIENT_ID&response_type=code&redirect_uri=$MS_REDIRECT" +
            "&response_mode=query&scope=${java.net.URLEncoder.encode(MS_SCOPES, "UTF-8")}" +
            "&code_challenge=$codeChallenge&code_challenge_method=S256&prompt=select_account"

    /** OAuth 令牌(含 id_token)：id_token 中的 email/preferred_username 比访问令牌更可靠 */
    data class MsTokens(val access: String, val refresh: String, val expire: Long, val idToken: String)

    /** 用授权码换取令牌(需携带 PKCE 校验器)，返回完整令牌信息 */
    fun msExchangeToken(code: String, verifier: String): MsTokens {
        val body = "client_id=$MS_CLIENT_ID&grant_type=authorization_code" +
                "&code=${java.net.URLEncoder.encode(code, "UTF-8")}&redirect_uri=$MS_REDIRECT" +
                "&code_verifier=${java.net.URLEncoder.encode(verifier, "UTF-8")}"
        return msTokenRequest(body)
    }

    /** 用刷新令牌续期 */
    fun msRefreshToken(refresh: String): MsTokens {
        val body = "client_id=$MS_CLIENT_ID&grant_type=refresh_token" +
                "&refresh_token=${java.net.URLEncoder.encode(refresh, "UTF-8")}" +
                "&scope=${java.net.URLEncoder.encode(MS_SCOPES, "UTF-8")}"
        return msTokenRequest(body)
    }

    private fun msTokenRequest(form: String): MsTokens {
        val url = java.net.URL("https://login.microsoftonline.com/common/oauth2/v2.0/token")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 20000; readTimeout = 20000
        }
        conn.outputStream.use { it.write(form.toByteArray()) }
        // 微软在授权失败时返回 HTTP 400，必须读 errorStream 才能拿到真实错误(否则只会显示 URL)
        val respText = try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            val errBody = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
            val desc = errBody?.let {
                runCatching { org.json.JSONObject(it).optString("error_description") }.getOrNull()
            }
            throw Exception(desc?.takeIf { it.isNotBlank() } ?: "网络请求失败(${e.message ?: "未知错误"})，请检查网络后重试")
        }
        val json = org.json.JSONObject(respText)
        if (json.has("error")) throw Exception("OAuth 失败：${json.optString("error_description")}")
        return MsTokens(
            access = json.getString("access_token"),
            refresh = json.optString("refresh_token", ""),
            expire = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 120) * 1000,
            idToken = json.optString("id_token", "")
        )
    }

    /** 内存令牌缓存：email -> 令牌信息 */
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, MsTokens>()

    /** 返回可用令牌：过期前自动用刷新令牌续期 */
    fun ensureToken(acc: Account): String {
        if (acc.oauthRefresh.isBlank()) return acc.password
        val cached = tokenCache[acc.email]
        if (cached != null && cached.expire > System.currentTimeMillis() + 60_000) return cached.access
        val t = msRefreshToken(cached?.refresh ?: acc.oauthRefresh)
        tokenCache[acc.email] = t.copy(refresh = t.refresh.ifBlank { acc.oauthRefresh })
        return t.access
    }

    /* ---------- 会话/连接 ---------- */

    private fun imapProps(acc: Account) = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", acc.imapHost)
        put("mail.imaps.port", acc.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "25000")
        // TCP 保活：让系统内核定期探测长连接，静默断网(切换基站/WiFi)时能尽快发现并重建，
        // 不再出现"连接看着还在、其实早就死了"导致新邮件迟迟不到
        put("mail.imaps.socket.keepalive", "true")
        put("mail.imaps.usesocketchannels", "false")
        put("mail.imaps.peek", "true")
        put("mail.mime.decodetext.strict", "false")
        if (acc.oauthToken.isNotBlank() || acc.oauthRefresh.isNotBlank())
            put("mail.imaps.auth.mechanisms", "XOAUTH2")
    }

    private fun smtpProps(acc: Account) = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", acc.smtpHost)
        put("mail.smtp.port", acc.smtpPort.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.ssl.enable", (acc.smtpPort == 465).toString())
        put("mail.smtp.starttls.enable", (acc.smtpPort == 587).toString())
        put("mail.smtp.starttls.required", "false")
        // 发信超时放宽：附件大、对方服务器慢的时候，超时太短会误判成"发送失败"
        put("mail.smtp.connectiontimeout", "30000")
        put("mail.smtp.timeout", "60000")
        put("mail.smtp.writetimeout", "60000")
        put("mail.smtp.ssl.trust", acc.smtpHost)
        put("mail.smtp.socket.keepalive", "true")
        if (acc.oauthToken.isNotBlank() || acc.oauthRefresh.isNotBlank())
            put("mail.smtp.auth.mechanisms", "XOAUTH2")
    }

    fun connectImap(acc: Account): Store {
        val token = ensureToken(acc)
        val session = Session.getInstance(imapProps(acc))
        val store = session.getStore("imaps")
        store.connect(acc.imapHost, acc.imapPort, acc.email, token)
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

    // IMAP 长连接复用(email -> Store)：避免每轮同步都重建 SSL+登录(每次要好几秒，收新邮件动辄20多秒)。
    // 连接保持不断，每秒轮询只做一次轻量增量检查，新邮件 1~3 秒即可到达列表。
    private val storeCache = java.util.concurrent.ConcurrentHashMap<String, Store>()
    // 每个账号已拉取过的最大 UID：下一轮只拉这个 UID 之后的新邮件(增量同步)
    private val lastSeenUid = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // 已发送文件夹改为低频扫描(每 30 轮一次)，不拖慢高频收信检查
    private val sentScanCounter = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // 正在自愈重试的账号：防止重试风暴把连接池打满
    private val catchingUp = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun getStore(acc: Account): Store {
        val c = storeCache[acc.email]
        if (c != null && runCatching { c.isConnected }.getOrDefault(false)) return c
        runCatching { c?.close() }
        val s = connectImap(acc)
        storeCache[acc.email] = s
        return s
    }

    /** 丢弃某个账号缓存的连接：长连接可能"看着还在、其实已经断了"，
     *  出错后必须彻底重建，否则每轮同步都在同一条死连接上失败，表现为邮件迟迟不到 */
    private fun invalidateStore(email: String) {
        runCatching { storeCache.remove(email)?.let { st -> runCatching { st.close() } } }
    }

    // 每账号一把互斥锁：轮询同步与打开正文共用同一条长连接，同一 IMAPFolder 并发 open/close
    // 会协议冲突，表现为“正文一直加载不出来/等一会没反应”，返回重进又正常。串行化后彻底解决。
    private val folderMutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun mutexFor(email: String) =
        folderMutexes.computeIfAbsent(email) { kotlinx.coroutines.sync.Mutex() }

    /** 同步单个账号，返回新收到的邮件(用于通知)。连接复用+增量 UID，速度极快。
     *  每账号互斥：避免与 loadFull 并发操作同一 IMAP 连接。
     *  自愈：任何一次同步失败，立刻丢掉这条(可能早已静默断开的)长连接并用全新连接重试一次，
     *  保证"网络抖动一次不会让收信卡住十几秒"。 */
    suspend fun syncAccount(ctx: Context, acc: Account, db: AppDatabase): List<MailMessage> =
        mutexFor(acc.email).withLock {
            if (catchingUp.getOrDefault(acc.email, false)) return@withLock emptyList()
            catchingUp[acc.email] = true
            try {
                syncOnce(ctx, acc, db)
            } catch (first: Throwable) {
                invalidateStore(acc.email)
                try {
                    syncOnce(ctx, acc, db)
                } catch (second: Throwable) {
                    throw second
                }
            } finally {
                catchingUp[acc.email] = false
            }
        }

    private suspend fun syncOnce(ctx: Context, acc: Account, db: AppDatabase): List<MailMessage> {
        val newInbox = mutableListOf<MailMessage>()
        val store = getStore(acc)
        val inbox = store.getFolder("INBOX")
        if (!inbox.isOpen) inbox.open(Folder.READ_ONLY)
        val uidFolder = inbox as UIDFolder
        val seen0 = lastSeenUid[acc.email]
        if (seen0 == null) {
            // 首次(每次启动后第一轮)：用 UIDNext 直接定位最近 50 封建立基线(不再全量列出，
            // 大邮箱全量列出要 10 秒以上；UIDNext 定位亚秒级)，同时把库里缺的补进来
            var maxUid = 0L
            val uidNext = runCatching { uidFolder.uidNext }.getOrDefault(1L)
            val start = maxOf(1L, uidNext - 50)
            val recent = runCatching { uidFolder.getMessagesByUID(start, uidNext - 1) }
                .getOrNull()?.toList() ?: emptyList()
            for (m in recent) {
                if (m.isExpunged) continue
                val uid = uidFolder.getUID(m)
                if (uid > maxUid) maxUid = uid
                if (db.messages().byUid(acc.id, "INBOX", uid.toString()) != null) continue
                val ent = toEntity(acc.id, "INBOX", uid.toString(), m, ctx)
                db.messages().insert(ent)
                if (!ent.isRead) newInbox.add(ent)
            }
            lastSeenUid[acc.email] = maxOf(maxUid, uidNext - 1)
        } else {
            // 之后每一轮：只拉基线 UID 之后的新邮件(通常 0 封，一次轻量查询，亚秒级完成)
            val fresh = uidFolder.getMessagesByUID((seen0 ?: 0L) + 1, UIDFolder.LASTUID)
            newInbox += insertNew(ctx, acc, db, uidFolder, fresh)
        }
        runCatching { if (inbox.isOpen) inbox.close(false) }

        // 同步服务器上已有的已发送邮件：低频(每 30 轮一次)，收信检查保持最快
        val n = (sentScanCounter[acc.email] ?: 0) + 1
        if (n >= 30) {
            sentScanCounter[acc.email] = 0
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
        } else sentScanCounter[acc.email] = n
        return newInbox
    }


    /** 增量入库：把 UID 大于基线且库里没有的邮件插入，返回新邮件并推进基线(轮询与推送线程共用，幂等) */
    private suspend fun insertNew(ctx: Context, acc: Account, db: AppDatabase,
                                  uidFolder: UIDFolder, msgs: Array<out Message>): List<MailMessage> {
        val out = mutableListOf<MailMessage>()
        val seen = lastSeenUid[acc.email] ?: 0L
        var maxUid = seen
        for (m in msgs) {
            if (m == null || m.isExpunged) continue
            val uid = uidFolder.getUID(m)
            if (uid > maxUid) maxUid = uid
            if (uid <= seen) continue
            if (db.messages().byUid(acc.id, "INBOX", uid.toString()) != null) continue
            val ent = toEntity(acc.id, "INBOX", uid.toString(), m, ctx)
            db.messages().insert(ent)
            if (!ent.isRead) out.add(ent)
        }
        if (maxUid > (lastSeenUid[acc.email] ?: 0L)) lastSeenUid[acc.email] = maxUid
        return out
    }

    /* ---------- IMAP IDLE 推送(比轮询更快) ---------- */

    // 推送版本号：IDLE 线程每收到新邮件就 +1，界面检测到变化立即刷新列表并回顶
    @Volatile var pushVersion = 0
    private val pushStarted = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // 每条 IDLE 长连接的实际对象与进入空闲的时刻：看门狗按这两个表判断"是真在等推送，还是卡死在死连接上"
    private val idleStores = java.util.concurrent.ConcurrentHashMap<String, Store>()
    private val idleEnteredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val idleFolderRef = java.util.concurrent.ConcurrentHashMap<String, com.sun.mail.imap.IMAPFolder>()

    /** 为每个账号启动 IMAP IDLE 长连接推送线程：服务器一有新邮件毫秒级主动通知。
     *  关键修复：
     *  1) 断线重连退避从 2→4→8…→60 秒改成 0.4→1→2→3→5 秒(封顶 5 秒)：之前一次抖动就退到 16/32 秒，
     *     正好表现为"邮件要等十几二十秒才出现"；
     *  2) 加看门狗：IDLE 卡在一条已静默死亡的连接上(手机切网/基站切换最常见)时，
     *     120 秒内没有收到任何服务器事件就强制掐断这条连接，立刻重建；
     *  3) 每轮进入 IDLE 前先做一次增量追赶，重建连接期间的邮件不会漏。 */
    fun startPush(ctx: Context, acc: Account, db: AppDatabase) {
        if (pushStarted.containsKey(acc.email)) return
        pushStarted[acc.email] = true
        Thread {
            var backoff = 400L
            while (true) {
                var store: Store? = null
                val sessionStart = System.currentTimeMillis()
                try {
                    // 进入 IDLE 前先追赶一次：上一次连接断开期间到达的邮件在这里补齐
                    kotlinx.coroutines.runBlocking { runCatching { syncAccount(ctx, acc, db) } }
                    store = connectImap(acc)
                    idleStores[acc.email] = store
                    val f = store.getFolder("INBOX") as com.sun.mail.imap.IMAPFolder
                    idleFolderRef[acc.email] = f
                    if (!f.isOpen) f.open(Folder.READ_ONLY)
                    while (f.isOpen) {
                        idleEnteredAt[acc.email] = System.currentTimeMillis()
                        try {
                            f.idle() // 阻塞直到服务器推送(新邮件/状态变化)或连接被掐断
                        } catch (e: Exception) {
                            break
                        }
                        idleEnteredAt[acc.email] = 0L
                        // 服务器有新事件 → 立刻走一次标准增量同步(独立连接、自带去重与自愈)，
                        // 避免在这里直接操作正在 IDLE 的那条连接造成协议冲突
                        val newOnes = kotlinx.coroutines.runBlocking {
                            runCatching { syncAccount(ctx, acc, db) }.getOrDefault(emptyList())
                        }
                        kotlinx.coroutines.runBlocking {
                            newOnes.filter { it.folder == "INBOX" }
                                .forEach { SyncWorker.notifyNew(ctx, it.accountId, it) }
                        }
                        if (newOnes.isNotEmpty()) pushVersion++
                    }
                    runCatching { if (f.isOpen) f.close(false) }
                } catch (t: Throwable) {
                    // 连接建立/鉴权失败：走下面的快速退避重建
                } finally {
                    idleEnteredAt[acc.email] = 0L
                    idleFolderRef.remove(acc.email)
                    idleStores.remove(acc.email)
                    runCatching { store?.close() }
                }
                // 连接曾经稳定跑过一段时间(>60 秒) → 退避归零，重新抖动时还是秒级恢复
                if (System.currentTimeMillis() - sessionStart > 60_000) backoff = 400L
                try { Thread.sleep(backoff) } catch (e: InterruptedException) { pushStarted.remove(acc.email); return@Thread }
                backoff = minOf(backoff * 2, 5000L)
            }
        }.apply { isDaemon = true; name = "cloudmail-idle-${acc.email}" }.start()

        // 看门狗：每分钟检查一次，某账号在 IDLE 里"无声无息"超过 120 秒说明连接已死(RFC 里服务器
        // 30 分钟内必回事件，正常情况下不会有 2 分钟静默)，直接掐断连接让上面的循环立刻重建
        if (watchdogStarted.compareAndSet(false, true)) {
            Thread {
                while (true) {
                    try { Thread.sleep(20_000) } catch (e: InterruptedException) { return@Thread }
                    for (email in idleStores.keys.toList()) {
                        val entered = idleEnteredAt[email] ?: 0L
                        if (entered > 0L && System.currentTimeMillis() - entered > 120_000L) {
                            runCatching { idleFolderRef[email]?.close(false) }
                            runCatching { idleStores[email]?.close() }
                        }
                    }
                }
            }.apply { isDaemon = true; name = "cloudmail-idle-watchdog" }.start()
        }
    }

    private val watchdogStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 主动踢一下某个账号：设置页"立即重连"、网络恢复广播都可调用 */
    fun kickPush(email: String) {
        runCatching { idleFolderRef[email]?.close(false) }
        runCatching { idleStores[email]?.close() }
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

    /** 拉取完整正文+附件到本地，返回更新后的实体。
     *  复用长连接(不再每次新建 SSL+登录，省 2~5 秒)：首次打开正文明显更快 */
    // 正文预取节流：同一账号 60 秒内只跑一轮，避免频繁占用 IMAP 连接
    private val prefetchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 后台预取最近未拉取正文的收件箱邮件(默认最新 3 封)：点开邮件时正文已在本地，基本秒开。
     *  之前首次点开一封邮件要等 1~2 秒(实时去服务器拉正文)，尤其是刚收到的新邮件。 */
    fun prefetchLatest(ctx: Context, acc: Account, db: AppDatabase, count: Int = 3) {
        val now = System.currentTimeMillis()
        val last = prefetchAt[acc.email] ?: 0L
        if (now - last < 60_000) return
        prefetchAt[acc.email] = now
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    val targets = db.messages().inbox().filter { !it.loaded }.take(count)
                    for (m in targets) {
                        runCatching { loadFull(ctx, acc, m, db) }
                    }
                }
            }
        }.apply { isDaemon = true; name = "cloudmail-prefetch-${acc.email}" }.start()
    }

    suspend fun loadFull(ctx: Context, acc: Account, msg: MailMessage, db: AppDatabase): MailMessage {
        if (msg.loaded) return msg
        return mutexFor(acc.email).withLock {
        var lastE: Exception? = null
        var result: MailMessage? = null
        var attempt = 0
        // 最多 3 次尝试：连接可能"看着还在、其实已被服务器断开"(半死连接)，第一次失败后
        // 必须彻底丢弃旧连接重建再试，否则点重新加载用的还是同一条死连接，必然再失败；
        // 退出重进页面时恰好缓存失效新建了连接才"看起来正常"
        while (attempt < 3 && result == null) {
            if (attempt > 0) {
                invalidateStore(acc.email)
                kotlinx.coroutines.delay(400L * attempt)
            }
            attempt++
            var opened: javax.mail.Folder? = null
            try {
                val store = getStore(acc)
                val f = if (msg.folder == "SENT") sentFolder(store) ?: return msg else store.getFolder("INBOX")
                if (!f.exists()) return msg
                f.open(Folder.READ_ONLY)
                opened = f
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
                opened = null
                result = updated
                // 真实已读回执(MDN)：对方要求回执且这是收件时,回执一次
                runCatching {
                    val rrt = m.getHeader("Disposition-Notification-To")?.firstOrNull()
                    if (!rrt.isNullOrBlank()) sendMdn(ctx, acc, msg, rrt)
                }
            } catch (e: Exception) {
                runCatching { opened?.close(false) }
                lastE = e
                // 失败即彻底丢弃该账号缓存连接，下次尝试走全新连接
                invalidateStore(acc.email)
                if (!isTransient(e)) throw e
            }
        }
        result ?: msg
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
                var vcardHtml = ""
                for (i in 0 until mp.count) {
                    val p = mp.getBodyPart(i)
                    if (p.isMimeType("multipart/alternative")) return extractBody(ctx, p, msgId, atts) + vcardHtml
                    if (p.isMimeType("text/html")) { htmlFallback = p.content.toString() }
                    else if (p.isMimeType("text/plain") && htmlFallback.isEmpty()) { /* 继续找 html */ }
                    else if (p.isMimeType("multipart/*")) return extractBody(ctx, p, msgId, atts) + vcardHtml
                    else if (p.isMimeType("text/vcard") || p.isMimeType("text/x-vcard")) {
                        // 别人发来的 vCard 名片(.vcf)：正文里渲染成完整资料卡(含头像)，同时保留为附件
                        vcardHtml += runCatching { vcardToHtml(p.content.toString()) }.getOrDefault("")
                        collectAttachment(ctx, p, msgId, atts)
                    }
                    else collectAttachment(ctx, p, msgId, atts)
                }
                if (htmlFallback.isNotEmpty()) return htmlFallback + vcardHtml
                // 全是纯文本
                for (i in 0 until mp.count) {
                    val p = mp.getBodyPart(i)
                    if (p.isMimeType("text/plain")) {
                        val t = p.content.toString()
                        return "<pre style=\"white-space:pre-wrap;font-family:sans-serif\">${esc(t)}</pre>" + vcardHtml
                    }
                }
                htmlFallback + vcardHtml
            } else if (part.isMimeType("text/html")) {
                part.content.toString()
            } else if (part.isMimeType("text/plain")) {
                val t = part.content.toString()
                "<pre style=\"white-space:pre-wrap;font-family:sans-serif\">${esc(t)}</pre>"
            } else if (part.isMimeType("text/vcard") || part.isMimeType("text/x-vcard")) {
                val h = runCatching { vcardToHtml(part.content.toString()) }.getOrDefault("")
                collectAttachment(ctx, part, msgId, atts)
                h
            } else {
                collectAttachment(ctx, part, msgId, atts)
                ""
            }
        } catch (e: Exception) { "" }
    }

    /** 解析他人发来的 vCard(.vcf) 名片：渲染成资料卡(头像/姓名/电话/邮箱/单位/地址)，
     *  之前只当附件列出，正文里看不到名片信息和头像 */
    fun vcardToHtml(vcf: String): String {
        return runCatching {
            // 折行合并(vCard 规范：换行后以空格开头的是续行)
            val unfolded = vcf.replace(Regex("\r?\n[ \t]"), "")
            var name = ""; var phone = ""; var email = ""; var org = ""; var title = ""; var adr = ""; var photo = ""
            for (line in unfolded.lines()) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).uppercase()
                val value = line.substring(idx + 1)
                when {
                    key == "FN" && name.isBlank() -> name = value
                    key == "N" && name.isBlank() -> name = value.split(';').filter { it.isNotBlank() }.joinToString(" ")
                    key.startsWith("TEL") && phone.isBlank() -> phone = value
                    key.startsWith("EMAIL") && email.isBlank() -> email = value
                    key.startsWith("ORG") && org.isBlank() -> org = value.replace(';', ' ')
                    key.startsWith("TITLE") && title.isBlank() -> title = value
                    key.startsWith("ADR") && adr.isBlank() -> adr = value.split(';').filter { it.isNotBlank() }.joinToString(" ")
                    key.startsWith("PHOTO") && photo.isBlank() -> {
                        photo = when {
                            value.startsWith("http") -> value
                            key.contains("BASE64") || value.length > 100 -> "data:image/jpeg;base64," + value.replace(" ", "")
                            else -> ""
                        }
                        if (photo.length > 600_000) photo = ""  // 过大不内嵌，避免 WebView 卡顿
                    }
                }
            }
            val avatar = if (photo.isNotBlank())
                "<img src=\"$photo\" style=\"width:52px;height:52px;border-radius:50%;object-fit:cover\"/>"
            else
                "<div style=\"width:52px;height:52px;border-radius:50%;background:#3F7BF6;color:#fff;display:flex;align-items:center;justify-content:center;font-size:22px;flex:none\">${esc(name.firstOrNull()?.toString() ?: "?")}</div>"
            fun row(label: String, v: String): String =
                if (v.isBlank()) "" else "<div style=\"font-size:12px;color:#555;margin-top:2px\"><b style=\"color:#333\">$label</b> ${esc(v)}</div>"
            "<div style=\"border:1px solid #e0e0e0;border-radius:12px;padding:12px;margin:8px 0\">" +
                "<div style=\"display:flex;align-items:center;gap:12px\">" + avatar +
                "<div style=\"font-size:15px;font-weight:bold\">" + esc(name.ifBlank { "名片" }) + "</div></div>" +
                row("电话", phone) + row("邮箱", email) + row("单位", org) + row("职务", title) + row("地址", adr) +
            "</div>"
        }.getOrDefault("")
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

    /** 地址清洗：拆分隔符、去空格、去重复、过滤明显不合法的地址 */
    private fun normalizeAddrs(raw: List<String>): List<String> =
        raw.flatMap { it.split(",", ";", "，", "；") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.contains("@") && !it.contains(" ") }
            .distinct()

    /** 发送一封邮件。失败自动重试 3 次(网络抖动/服务器临时拒绝/令牌过期)，
     *  全部失败才抛异常给上层，保证"能发出去就一定发出去"，不会因为一次超时就判失败。 */
    fun sendMail(acc: Account, req: SendRequest): String {
        val to = normalizeAddrs(req.to)
        val cc = normalizeAddrs(req.cc)
        val bcc = normalizeAddrs(req.bcc)
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty())
            throw IllegalArgumentException("没有有效的收件人地址，请检查收件人邮箱")

        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                return sendOnce(acc, req.copy(to = to, cc = cc, bcc = bcc))
            } catch (e: Exception) {
                lastError = e
                if (!isTransient(e) || attempt == 3) throw e
                // 可能是 OAuth 令牌过期/连接被服务端限流：丢掉缓存令牌与连接，退避后再试一次
                tokenCache.remove(acc.email)
                invalidateStore(acc.email)
                try { Thread.sleep(if (attempt == 1) 1500L else 3000L) } catch (ignore: InterruptedException) {}
            }
        }
        throw lastError ?: Exception("发送失败：未知错误")
    }

    private fun sendOnce(acc: Account, req: SendRequest): String {
        val session = Session.getInstance(smtpProps(acc))
        val mime = MimeMessage(session)
        mime.setFrom(InternetAddress(acc.email, acc.nickname.ifBlank { acc.email }))
        req.to.forEach { mime.addRecipient(Message.RecipientType.TO, InternetAddress(it.trim())) }
        req.cc.forEach { mime.addRecipient(Message.RecipientType.CC, InternetAddress(it.trim())) }
        req.bcc.forEach { mime.addRecipient(Message.RecipientType.BCC, InternetAddress(it.trim())) }
        mime.subject = req.subject
        mime.sentDate = Date()
        mime.setHeader("MIME-Version", "1.0")
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
            try {
                val bp = MimeBodyPart()
                bp.attachFile(java.io.File(a.path))
                bp.fileName = MimeUtility.encodeText(a.name)
                if (a.isInline && a.cid.isNotBlank()) {
                    bp.contentID = "<${a.cid}>"
                    bp.disposition = Part.INLINE
                }
                container.addBodyPart(bp)
            } catch (e: Exception) {
                // 单个附件读取失败(文件被删/权限丢失)不能让整封邮件发不出去
            }
        }
        mime.setContent(container)
        Transport.send(mime, acc.email, ensureToken(acc))

        // 真实已读回执的替代：保存到已发送 (IMAP APPEND)。失败不影响"已成功发出"的结论。
        // 用独立连接，避免和同步线程共用一条连接造成协议冲突
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

    /** 临时性错误(值得自动重试)：超时、连接中断、被限流、鉴权过期、4xx 软退信 */
    fun isTransient(e: Exception): Boolean = when (e) {
        is java.net.SocketTimeoutException -> true
        is java.net.ConnectException -> true
        is javax.mail.AuthenticationFailedException -> true
        // 没网 / DNS 暂时不可用 / socket 被重置：都可恢复，等网络回来要继续发
        is java.net.UnknownHostException -> true
        is java.net.SocketException -> true
        is java.io.IOException -> true
        is com.sun.mail.smtp.SMTPAddressFailedException -> e.returnCode in 400..499
        is com.sun.mail.smtp.SMTPSendFailedException -> e.returnCode in 400..499
        is javax.mail.SendFailedException -> false
        is javax.mail.MessagingException -> {
            val msg = (e.message ?: "") + (e.cause?.message ?: "")
            e.cause is java.io.IOException ||
                listOf("Could not connect", "Exception reading response", "EOF", "Connection reset",
                       "timed out", "Temporary", "try again", "421", "454", "Connection closed").any {
                    msg.contains(it, ignoreCase = true)
                }
        }
        else -> false
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
    fun sendMdn(ctx: Context, acc: Account, msg: MailMessage, rrt: String) {
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
            Transport.send(mdn, acc.email, ensureToken(acc))
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
