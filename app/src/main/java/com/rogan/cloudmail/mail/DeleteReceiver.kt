package com.rogan.cloudmail.mail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.rogan.cloudmail.data.AppDatabase

/** 通知上的"删除"按钮：直接把该邮件移入已删除 */
class DeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("msgId", -1)
        if (id <= 0) return
        val db = AppDatabase.get(context)
        val m = db.messages().byId(id) ?: return
        m.folder = "TRASH"
        db.messages().update(m)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
    }
}
