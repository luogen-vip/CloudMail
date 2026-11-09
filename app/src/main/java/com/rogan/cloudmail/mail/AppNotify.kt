package com.rogan.cloudmail.mail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.rogan.cloudmail.MainActivity
import com.rogan.cloudmail.R

/**
 * 统一通知管理：
 *  - 收到新邮件：提示音 + 横幅(高优先级)
 *  - 发送成功：提示音
 *  - 发送失败：与成功不同的提示音(警示音)，让用户一听就能区分
 */
object AppNotify {

    const val CH_MAIL = "mail"            // 新邮件
    const val CH_SEND_OK = "send_ok"      // 发送成功
    const val CH_SEND_FAIL = "send_fail"  // 发送失败
    const val CH_SENDING = "sending"      // 发送中(静默常驻)

    private fun soundUri(uri: Uri?): AudioAttributes? = uri?.let {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        nm.createNotificationChannel(NotificationChannel(CH_MAIL, "新邮件", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "收到新邮件时立即提醒，带提示音与横幅"
            setSound(default, soundUri(default))
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SEND_OK, "发送成功", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "邮件发送成功时提示"
            setSound(default, soundUri(default))
        })
        nm.createNotificationChannel(NotificationChannel(CH_SEND_FAIL, "发送失败", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "邮件发送失败时用警示音提醒"
            setSound(alarm, soundUri(alarm))
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_SENDING, "发送中", NotificationManager.IMPORTANCE_LOW).apply {
            description = "邮件正在发送的状态"
            setSound(null, null)
        })
    }

    private fun openApp(ctx: Context, msgId: Long = -1): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (msgId > 0) putExtra("openMessageId", msgId)
        }
        return PendingIntent.getActivity(ctx, (msgId.toInt().takeIf { it != 0 } ?: 9001), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 新邮件通知：点击跳转对应邮件 */
    fun newMail(ctx: Context, msgId: Long, title: String, text: String, deletePi: PendingIntent? = null, ad: Boolean = false) {
        runCatching {
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CH_MAIL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, msgId))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
            deletePi?.let { b.addAction(0, "删除", it) }
            if (ad) b.setSubText("广告邮件")
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(msgId.toInt(), b.build())
        }
    }

    /** 发送中(常驻，随后被成功/失败通知替换) */
    fun sending(ctx: Context, subject: String) {
        runCatching {
            ensureChannels(ctx)
            val b = NotificationCompat.Builder(ctx, CH_SENDING)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("正在发送…")
                .setContentText("《${subject.ifBlank { "(无主题)" }}》正在发送")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(9200, b.build())
        }
    }

    private fun replace(ctx: Context, ch: String, title: String, text: String, silent: Boolean) {
        runCatching {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9200) // 撤掉"发送中"
            val b = NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
            if (silent) b.setSilent(true)
            nm.notify(9300, b.build())
        }
    }

    fun sendOk(ctx: Context, subject: String) =
        replace(ctx, CH_SEND_OK, "邮件发送成功", "《${subject.ifBlank { "(无主题)" }}》已成功发送到对方服务器", silent = false)

    fun sendFail(ctx: Context, subject: String, reason: String) =
        replace(ctx, CH_SEND_FAIL, "邮件发送失败", "《${subject.ifBlank { "(无主题)" }}》：$reason", silent = false)

    fun cancelSending(ctx: Context) {
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(9200)
        }
    }
}
