package com.rogan.cloudmail.mail

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rogan.cloudmail.data.*
import java.util.Calendar

/** 真实发信 Worker：即时发送与定时发送共用；发送状态真实反映 SMTP 结果 */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val itemId = inputData.getLong("itemId", -1)
        if (itemId <= 0) return Result.success()
        val item = db.outbox().byId(itemId) ?: return Result.success()
        val acc = db.accounts().byId(item.accountId) ?: run {
            AppNotify.sendFail(applicationContext, item.subject, "发件账号不存在，可能已被删除")
            return Result.failure()
        }

        // 发送中状态：本地数据库 + 系统通知栏真实显示「正在发送…」(发送成功/失败后自动被替换)
        db.outbox().update(item.copy(status = "SENDING", error = "", updatedAt = System.currentTimeMillis()))
        AppNotify.sending(applicationContext, item.subject)

        return try {
            val atts = AttInfo.fromJson(item.attachmentsJson).toMutableList()
            val cardHtml = if (item.cardMode) cardBlock(acc) else ""
            // 名片头像作为内联图片(带 Content-ID)发送，对方客户端才能显示头像
            if (item.cardMode && acc.avatarPath != null && java.io.File(acc.avatarPath).exists()) {
                val f = java.io.File(acc.avatarPath)
                val mime = when (f.extension.lowercase()) {
                    "png" -> "image/png"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; else -> "image/jpeg"
                }
                if (atts.none { it.cid == CARD_AVATAR_CID })
                    atts.add(AttInfo("avatar.${f.extension.ifBlank { "jpg" }}", f.absolutePath, mime, true, CARD_AVATAR_CID))
            }
            val mid = MailService.sendMail(acc, MailService.SendRequest(
                to = item.toAddr.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() },
                cc = item.ccAddr.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() },
                bcc = item.bccAddr.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() },
                subject = item.subject,
                bodyHtml = item.bodyHtml,
                attachments = atts,
                readReceipt = item.readReceipt,
                cardHtml = cardHtml
            ))
            // 真实发送成功：Transport.send 未抛异常 = 对方 SMTP 服务器已接收
            // 邮件大小：之前不写 size 列表里就不显示大小(有的显示有的没有)；
            // 现在按 正文HTML字节数+全部附件文件大小 估算，保证每封已发送邮件都有真实大小可显示
            val approxSize = item.bodyHtml.toByteArray(Charsets.UTF_8).size +
                atts.filter { it.cid.isBlank() }.sumOf { java.io.File(it.path).takeIf { f -> f.exists() }?.length() ?: 0L }
            db.messages().insert(MailMessage(
                accountId = acc.id, folder = "SENT", uid = "local_$mid", messageId = mid,
                subject = item.subject, fromAddr = acc.email,
                fromName = acc.nickname.ifBlank { acc.email },
                toAddr = item.toAddr, ccAddr = item.ccAddr,
                date = System.currentTimeMillis(),
                // 名片段用 <!--VCARD--> 标记包起来：再次编辑/转发时能整块还原成名片(含头像图片和样式)，
                // HTML 注释对收件人不可见，不影响任何邮箱的显示效果
                bodyHtml = item.bodyHtml + (if (cardHtml.isNotBlank()) "<!--VCARD-->$cardHtml<!--/VCARD-->" else ""),
                snippet = item.subject, isRead = true, isStarred = false, isAd = false,
                hasAttachments = atts.isNotEmpty(),
                attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() }, loaded = true,
                size = approxSize
            ))
            db.outbox().delete(itemId)
            AppNotify.sendOk(applicationContext, item.subject)
            // 发送成功后立即拉取服务器最新邮件，保证列表马上可见(不等通知)
            runCatching { SyncWorker.syncAllNow(applicationContext) }
            Result.success()
        } catch (e: Exception) {
            // 真实失败原因(分类为可读中文)
            val reason = MailService.smtpErrorText(e)
            // MailService 内部已经重试过 3 次仍失败：区分「临时故障」与「真实拒收」。
            // 临时故障(超时/断网/服务器 4xx/令牌过期)交给 WorkManager 退避再试，不直接判死，
            // 保证网络恢复后邮件仍会自动发出去；邮件保留在待发送箱里，界面能看到。
            if (MailService.isTransient(e) && runAttemptCount < 3) {
                db.outbox().update(item.copy(status = "QUEUED", error = reason,
                    scheduledAt = null, updatedAt = System.currentTimeMillis()))
                AppNotify.cancelSending(applicationContext)
                Result.retry()
            } else {
                db.outbox().update(item.copy(status = "FAILED", error = reason, scheduledAt = null, updatedAt = System.currentTimeMillis()))
                AppNotify.sendFail(applicationContext, item.subject, reason)
                Result.failure()
            }
        }
    }

    companion object {
        const val CHANNEL = "send"
        const val CARD_AVATAR_CID = "cloudmail_vcard_avatar"

        /**
         * 名片(资料卡) HTML 块：头像 + 姓名，邮箱另起一行，每个字段一行；
         * 头像用 cid 引用内联图片(发送时作为内联附件)，未设置头像时显示首字圆标
         */
        fun cardBlock(acc: Account): String {
            val v = VCard.fromJson(acc.vcard)
            // 姓名优先级：显示名称(昵称) > 名片姓名 > 邮箱前缀。
            // 之前优先用名片姓名，而建号时名片姓名被写成了邮箱前缀且之后不再跟随显示名称更新，
            // 导致用户改了「显示名称」后名片预览/发出的名片最上面还是邮箱地址
            val name = acc.nickname.ifBlank { v.name.ifBlank { acc.email.substringBefore("@") } }
            val rows = buildList {
                if (v.phone.isNotBlank()) add("电话：${v.phone}")
                if (v.org.isNotBlank()) add("单位：${v.org}")
                if (v.title.isNotBlank()) add("职务：${v.title}")
                if (v.address.isNotBlank()) add("地址：${v.address}")
                if (v.note.isNotBlank()) add("备注：${v.note}")
            }
            if (rows.isEmpty() && name.isBlank()) return ""
            val avatarFile = acc.avatarPath?.let { java.io.File(it) }?.takeIf { it.exists() }
            val avatarHtml = if (avatarFile != null) {
                "<img src=\"cid:$CARD_AVATAR_CID\" width=\"64\" height=\"64\" alt=\"头像\" style=\"border-radius:32px\"/>"
            } else {
                "<div style=\"width:64px;height:64px;line-height:64px;text-align:center;background:#1565C0;color:#ffffff;font-size:28px;font-weight:bold;border-radius:32px;\">${name.take(1)}</div>"
            }
            val rowHtml = rows.joinToString("") {
                "<div style=\"color:#555;font-size:13px;line-height:22px\">$it</div>"
            }
            return """
            <div style="margin-top:24px;border-top:1px solid #e0e0e0;padding-top:14px;font-family:sans-serif">
              <table style="border-collapse:collapse"><tr>
                <td style="vertical-align:top;padding-right:12px">${avatarHtml}</td>
                <td style="vertical-align:top">
                  <div style="font-size:16px;font-weight:bold;color:#333;line-height:22px">${name}</div>
                  <div style="color:#666;font-size:13px;line-height:22px">${acc.email}</div>
                  ${rowHtml}
                </td>
              </tr></table>
            </div>
            """.trimIndent()
        }

        /** 立即发送：同一封邮件只用唯一任务名入队，连点发送按钮不会重复投递 */
        fun sendNow(context: Context, itemId: Long) {
            val req = androidx.work.OneTimeWorkRequest.Builder(SendWorker::class.java)
                .setInputData(androidx.work.Data.Builder().putLong("itemId", itemId).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 20, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork("send_now_$itemId", androidx.work.ExistingWorkPolicy.KEEP, req)
        }

        /** 定时发送：到点真实触发(WorkManager 持久化，APP 被清理后到点仍会发送) */
        fun sendAt(context: Context, itemId: Long, whenMillis: Long) {
            val delay = (whenMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val req = androidx.work.OneTimeWorkRequest.Builder(SendWorker::class.java)
                .setInputData(androidx.work.Data.Builder().putLong("itemId", itemId).build())
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork("send_$itemId", androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        /** 取消定时发送 */
        fun cancelScheduled(context: Context, itemId: Long) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("send_$itemId")
        }
    }
}
