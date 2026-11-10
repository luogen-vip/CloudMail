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
        var gotNew = false
        for (acc in accounts) {
            runCatching {
                val fresh = MailService.syncAccount(ctx, acc, db)
                fresh.filter { !it.isRead && it.folder == "INBOX" }.forEach { notifyNew(ctx, acc.id, it) }
                if (fresh.isNotEmpty()) gotNew = true
            }
        }
        // 后台同步收到新邮件后递增推送版本号：主页 500ms 轮询检测到变化立即刷新列表+回顶，
        // 保证“提示有一封新邮件”的同时，全部邮件顶部立刻出现这封信，
        // 不需要用户点系统通知/切到未读文件夹才能看到
        if (gotNew) MailService.pushVersion++
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
                    // 退信给出具体失败原因：域名不存在/地址不存在/已满/被拒收等，从退信正文解析
                    append("邮件未送达：")
                    append(com.rogan.cloudmail.ui.bounceReason(m.snippet + " " + m.bodyHtml.take(4000)))
                    val fa = com.rogan.cloudmail.ui.bounceFailedAddr(m.snippet + " " + m.bodyHtml.take(4000))
                    if (fa != null && fa != m.fromAddr) append("\n失败地址：$fa")
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
