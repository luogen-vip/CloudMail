package com.rogan.cloudmail.mail

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.rogan.cloudmail.data.AppDatabase
import java.util.concurrent.TimeUnit

/** 稍后提醒 Worker：到点后把指定的那封邮件再「提醒」一遍(系统通知，点击直达邮件详情)。
 *  WorkManager 持久化：APP 被清理后台、甚至重启后到点仍会触发 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val db = AppDatabase.get(ctx)
        val msgId = inputData.getLong("msgId", -1)
        if (msgId <= 0) return Result.success()
        val m = db.messages().byId(msgId) ?: return Result.success() // 邮件已被删除则静默跳过
        val deleteIntent = Intent(ctx, DeleteReceiver::class.java).apply { putExtra("msgId", m.id) }
        val dpi = PendingIntent.getBroadcast(ctx, (m.id + 70000).toInt(), deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        AppNotify.newMail(ctx, m.id,
            "⏰ 稍后提醒：《${m.subject.ifBlank { "(无主题)" }}》",
            "这是你设置「稍后提醒」的邮件，再看一眼吧。\n${m.snippet.take(80)}",
            dpi, m.isAd)
        return Result.success()
    }

    companion object {
        /** 设置稍后提醒：atMillis 到点时触发 */
        fun schedule(context: Context, msgId: Long, atMillis: Long) {
            val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
            val req = OneTimeWorkRequest.Builder(ReminderWorker::class.java)
                .setInputData(Data.Builder().putLong("msgId", msgId).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("reminder_$msgId", ExistingWorkPolicy.REPLACE, req)
        }

        /** 取消某封邮件的稍后提醒 */
        fun cancel(context: Context, msgId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork("reminder_$msgId")
        }
    }
}
