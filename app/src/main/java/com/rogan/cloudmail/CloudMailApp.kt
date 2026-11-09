package com.rogan.cloudmail

import android.app.Application
import androidx.work.*
import com.rogan.cloudmail.mail.SyncWorker
import java.util.concurrent.TimeUnit

class CloudMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 后台定时收信(每15分钟真实 IMAP 轮询, 触发系统通知)
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cloudmail_sync", ExistingPeriodicWorkPolicy.KEEP, req
        )
    }
}
