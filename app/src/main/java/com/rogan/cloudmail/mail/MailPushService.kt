package com.rogan.cloudmail.mail

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.rogan.cloudmail.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时收信前台服务。
 *
 * 为什么需要它：
 *  - IMAP IDLE 是"服务器主动推"的长连接，能做到毫秒级收信；但 APP 退到后台后进程会被系统冻结/回收，
 *    长连接随之失效，新邮件只能等下一轮低频后台任务，于是出现"十几秒才到"甚至收不到。
 *  - 用一个低优先级常驻通知的前台服务把进程保活，IDLE 连接就能一直在线，后台也是秒级到达。
 *
 * 这个服务只做两件事：
 *  1) 为每个账号拉起 IDLE 推送线程(服务被系统重启后也会自动补建)；
 *  2) 每 8 秒做一次极轻量的增量兜底检查，IDLE 万一被服务器断开，最多 8 秒内也能收到，不会漏。
 */
class MailPushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        AppNotify.ensureChannels(this)
        runCatching { startForeground(NOTIFY_ID, AppNotify.pushService(this, "长连接已建立，新邮件即时到达")) }
        registerNetworkWatcher()
    }

    /** 网络切换(WiFi↔流量、断网恢复)后，旧的长连接基本都是死的：立刻踢掉重建，
     *  不用等看门狗 2 分钟，也不用等上一轮退避，新邮件马上就能进来 */
    private fun registerNetworkWatcher() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    scope.launch {
                        val db = AppDatabase.get(this@MailPushService)
                        val accs = runCatching { db.accounts().all() }.getOrDefault(emptyList())
                        for (a in accs) runCatching { MailService.kickPush(a.email) }
                        kotlinx.coroutines.delay(1200)
                        for (a in accs) runCatching { MailService.syncAccount(this@MailPushService, a, db) }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val db = AppDatabase.get(this)
            val accs = runCatching { db.accounts().all() }.getOrDefault(emptyList())
            if (accs.isEmpty()) {
                stopSelf()
                return
            }
            // IDLE 推送线程：幂等，已经在跑就直接返回
            for (a in accs) runCatching { MailService.startPush(this, a, db) }
            // 轻量兜底：只拉基线 UID 之后的新邮件，正常情况 0 封、一次往返
            for (a in accs) {
                val fresh = runCatching { MailService.syncAccount(this, a, db) }.getOrDefault(emptyList())
                fresh.filter { !it.isRead && it.folder == "INBOX" }.forEach {
                    runCatching { SyncWorker.notifyNew(this, it.accountId, it) }
                    MailService.pushVersion++
                }
            }
            delay(POLL_MS)
        }
    }

    override fun onDestroy() {
        running = false
        runCatching {
            netCallback?.let {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        netCallback = null
        scope.launch { }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFY_ID = 9100
        /** 兜底检查间隔：IDLE 正常时这段代码什么都不做，只花一次极轻量查询 */
        const val POLL_MS = 8_000L

        /** 有账号就拉起服务；没有账号直接不启（避免空跑耗电） */
        fun startIfNeeded(ctx: Context) {
            if (!isEnabled(ctx)) return
            runCatching {
                val i = Intent(ctx, MailPushService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, MailPushService::class.java)) }
        }

        // ---- 开关(设置页可关)：关掉后不再常驻，收信回落到前台轮询/后台定时任务 ----
        private const val PREF = "cloudmail_prefs"
        private const val KEY_REALTIME = "realtime_push"

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_REALTIME, true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_REALTIME, on).apply()
            if (on) startIfNeeded(ctx) else stop(ctx)
        }
    }
}
