package com.rogan.cloudmail.mail

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rogan.cloudmail.data.*
import java.util.Calendar

/** 真实发信 Worker：即时发送与定时发送共用 */
class SendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val itemId = inputData.getLong("itemId", -1)
        if (itemId <= 0) return Result.success()
        val item = db.outbox().byId(itemId) ?: return Result.success()
        val acc = db.accounts().byId(item.accountId) ?: return Result.failure()

        return try {
            val atts = AttInfo.fromJson(item.attachmentsJson)
            val cardHtml = if (item.cardMode) cardBlock(acc) else ""
            val mid = MailService.sendMail(acc, MailService.SendRequest(
                to = item.toAddr.split(",", ";").map { it.trim() }.filter { it.isNotBlank() },
                cc = item.ccAddr.split(",", ";").map { it.trim() }.filter { it.isNotBlank() },
                bcc = item.bccAddr.split(",", ";").map { it.trim() }.filter { it.isNotBlank() },
                subject = item.subject,
                bodyHtml = item.bodyHtml,
                attachments = atts,
                readReceipt = item.readReceipt,
                cardHtml = cardHtml
            ))
            // 本地已发送记录(附件引用同一批本地文件)
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
            Result.success()
        } catch (e: Exception) {
            // 定时任务失败则转为待发送草稿并标注原因
            val o = item.copy(status = "DRAFT", scheduledAt = null, updatedAt = System.currentTimeMillis())
            db.outbox().update(o)
            Result.failure()
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

        /** 定时发送：到点真实触发 */
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
