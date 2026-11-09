package com.rogan.cloudmail.mail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rogan.cloudmail.MainActivity
import com.rogan.cloudmail.data.*
import java.util.Calendar

/** 真实发信 Worker：即时发送与定时发送共用；发送状态真实反映 SMTP 结果 */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val itemId = inputData.getLong("itemId", -1)
        if (itemId <= 0) return Result.success()
        val item = db.outbox().byId(itemId) ?: return Result.success()
        val acc = db.accounts().byId(item.accountId) ?: return Result.failure()

        // 发送中状态
        db.outbox().update(item.copy(status = "SENDING", error = "", updatedAt = System.currentTimeMillis()))

        return try {
            val atts = AttInfo.fromJson(item.attachmentsJson)
            val cardHtml = if (item.cardMode) cardBlock(acc) else ""
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
            db.messages().insert(MailMessage(
                accountId = acc.id, folder = "SENT", uid = "local_$mid", messageId = mid,
                subject = item.subject, fromAddr = acc.email,
                fromName = acc.nickname.ifBlank { acc.email },
                toAddr = item.toAddr, ccAddr = item.ccAddr,
                date = System.currentTimeMillis(),
                bodyHtml = item.bodyHtml + cardHtml,
                snippet = item.subject, isRead = true, isStarred = false, isAd = false,
                hasAttachments = atts.isNotEmpty(),
                attachmentsJson = item.attachmentsJson, loaded = true
            ))
            db.outbox().delete(itemId)
            notifyResult(applicationContext, "邮件发送成功", "《${item.subject.ifBlank { "(无主题)" }}》已成功发送到对方服务器", silent = true)
            Result.success()
        } catch (e: Exception) {
            // 真实失败原因(分类为可读中文)
            val reason = MailService.smtpErrorText(e)
            db.outbox().update(item.copy(status = "FAILED", error = reason, scheduledAt = null, updatedAt = System.currentTimeMillis()))
            notifyResult(applicationContext, "邮件发送失败", "《${item.subject.ifBlank { "(无主题)" }}》：$reason", silent = false)
            Result.failure()
        }
    }

    private fun notifyResult(ctx: Context, title: String, text: String, silent: Boolean) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel("send_result", "发送状态",
                    if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT))
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(ctx, 9001, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = androidx.core.app.NotificationCompat.Builder(ctx, "send_result")
                .setSmallIcon(com.rogan.cloudmail.R.drawable.ic_launcher)
                .setContentTitle(title).setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(pi)
                .setSilent(silent)
            nm.notify(9100, b.build())
        }
    }

    companion object {
        const val CHANNEL = "send"

        /** 名片(资料卡) HTML 块 */
        fun cardBlock(acc: Account): String {
            val v = VCard.fromJson(acc.vcard)
            val rows = buildList {
                if (v.phone.isNotBlank()) add("📞 电话：${v.phone}")
                if (v.org.isNotBlank()) add("🏢 单位：${v.org}")
                if (v.title.isNotBlank()) add("💼 职务：${v.title}")
                if (v.address.isNotBlank()) add("📍 地址：${v.address}")
                if (v.note.isNotBlank()) add("📝 备注：${v.note}")
            }
            if (rows.isEmpty() && v.name.isBlank()) return ""
            val avatar = acc.avatarPath?.let {
                "<img src=\"file://$it\" width=\"56\" height=\"56\" style=\"border-radius:28px;object-fit:cover\"/>"
            } ?: "<div style=\"width:56px;height:56px;border-radius:28px;background:#1565C0;color:#fff;text-align:center;line-height:56px;font-size:24px\">${(v.name.ifBlank { acc.nickname }).take(1)}</div>"
            return """
            <div style="margin-top:24px;border-top:1px solid #e0e0e0;padding-top:16px;font-family:sans-serif">
              <div style="display:flex;align-items:center">${avatar}
                <div style="margin-left:12px">
                  <div style="font-size:16px;font-weight:bold;color:#333">${v.name.ifBlank { acc.nickname }}<span style="font-weight:normal;color:#888;font-size:13px">&nbsp;&lt;${acc.email}&gt;</span></div>
                  ${rows.joinToString("<br/>") { "<span style=\"color:#666;font-size:13px\">$it</span>" }}
                </div>
              </div>
            </div>
            """.trimIndent()
        }

        /** 立即发送 */
        fun sendNow(context: Context, itemId: Long) {
            androidx.work.OneTimeWorkRequest.Builder(SendWorker::class.java)
                .setInputData(androidx.work.Data.Builder().putLong("itemId", itemId).build())
                .build()
                .let { androidx.work.WorkManager.getInstance(context).enqueue(it) }
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
