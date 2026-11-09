package com.rogan.cloudmail.mail

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rogan.cloudmail.MainActivity
import com.rogan.cloudmail.R
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

    private fun notifyNew(ctx: Context, accId: Long, m: MailMessage) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("mail", "新邮件", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val cdb = com.rogan.cloudmail.data.AppDatabase.get(ctx)
        val contact = cdb.contacts().byEmail(m.fromAddr)
        val sender = contact?.remark?.ifBlank { null } ?: m.fromName
        val title = if (contact?.isImportant == true) "★ 重要联系人：$sender" else sender
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra("openMessageId", m.id); putExtra("openAccountId", accId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(ctx, m.id.toInt(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val deleteIntent = Intent(ctx, DeleteReceiver::class.java).apply { putExtra("msgId", m.id) }
        val dpi = PendingIntent.getBroadcast(ctx, m.id.toInt(), deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val b = NotificationCompat.Builder(ctx, "mail")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(m.subject.ifBlank { m.snippet })
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.subject.ifBlank { m.snippet }))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, "查看", pi)
            .addAction(0, "删除", dpi)
        if (m.isAd) b.setSubText("广告邮件")
        nm.notify(m.id.toInt(), b.build())
    }
}
