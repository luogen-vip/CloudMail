package com.rogan.cloudmail.mail

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rogan.cloudmail.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 通知上的"删除"按钮：直接把该邮件移入已删除 */
class DeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("msgId", -1)
        if (id <= 0) return
        // Room 为 suspend 调用：goAsync() + 协程，完成后 finish 释放
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(context).messages().setFolder(id, "TRASH")
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(id.toInt())
            } finally {
                pending.finish()
            }
        }
    }
}
