package com.rogan.cloudmail.mail

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.rogan.cloudmail.data.AppDatabase
import com.rogan.cloudmail.data.MailMessage

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val db = AppDatabase.get(ctx)
        val accounts = db.accounts().all()
        for (acc in accounts) {
            runCatching {
                val fresh = MailService.syncAccount(ctx, acc, db)
                fresh.filter { !it.isRead && it.folder == "INBOX" }.forEach { notifyNew(ctx, acc.id, it) }
            }
        }
        return Result.success()
    }

    companion object {
        /** 立即同步一次全部账号(前台刷新按钮 / 登录后首同步共用) */
        fun syncAllNow(context: Context) {
            val req = OneTimeWorkRequest.Builder(SyncWorker::class.java).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync_now", ExistingWorkPolicy.REPLACE, req)
        }

        /** 新邮件弹通知(发件人备注优先 + 摘要 + 点击跳转)；手动同步与后台同步共用。
         *  退信(DSN)单独文案：明确告知“邮件没送达”，不再像普通新邮件一样只显示发件人 */
        suspend fun notifyNew(ctx: Context, accId: Long, m: MailMessage) {
            val cdb = AppDatabase.get(ctx)
            val contact = cdb.contacts().byEmail(m.fromAddr)
            val sender = contact?.remark?.ifBlank { null } ?: m.fromName.ifBlank { m.fromAddr }
            val bounce = com.rogan.cloudmail.ui.isBounceMail(m.fromAddr, m.subject)
            val title = when {
                bounce -> "⚠️ 邮件退信：发送未送达"
                contact?.isImportant == true -> "★ 重要联系人：$sender"
                else -> sender
            }
            val text = buildString {
                if (bounce) {
                    append("您之前发送的邮件被退回，对方地址可能不存在/已满/被拒收")
                    if (m.subject.isNotBlank()) append("。").append(m.subject.take(50))
                } else {
                    append(m.subject.ifBlank { "(无主题)" })
                    if (m.snippet.isNotBlank() && m.snippet != m.subject) append("\n").append(m.snippet.take(80))
                }
            }
            val deleteIntent = Intent(ctx, DeleteReceiver::class.java).apply { putExtra("msgId", m.id) }
            val dpi = PendingIntent.getBroadcast(ctx, m.id.toInt(), deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            AppNotify.newMail(ctx, m.id, title, text, dpi, m.isAd)
        }
    }
}
