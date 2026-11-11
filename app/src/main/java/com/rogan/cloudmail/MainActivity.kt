package com.rogan.cloudmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.rogan.cloudmail.ui.CloudMailTheme
import com.rogan.cloudmail.ui.RootNav

class MainActivity : ComponentActivity() {
    var pendingMessageId by mutableStateOf(-1L)

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 小窗自愈(仅冷启动)：系统清后台后从桌面/最近任务恢复时，任务可能带着“自由窗口(小窗)”模式，
        // APP 就会在屏幕中间以小窗打开。此时用 NEW_TASK|CLEAR_TASK 以全新全屏任务重启自己。
        // 只在冷启动(savedInstanceState==null)时触发，用户主动的右上角滑动小窗/分屏是运行中切换模式、
        // 不走 onCreate 重建，不受影响，里面的编辑内容也原样保留。
        val retry = intent?.getBooleanExtra("fullscreenRetry", false) ?: false
        if (!retry && savedInstanceState == null && android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode) {
            val i = intent?.let { android.content.Intent(it) } ?: android.content.Intent(this, MainActivity::class.java)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            i.putExtra("fullscreenRetry", true)
            startActivity(i)
            finish()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        setContent {
            CloudMailTheme {
                RootNav(pendingMessageId = { pendingMessageId }, onConsumePending = { pendingMessageId = -1 })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: android.content.Intent?) {
        val id = i?.getLongExtra("openMessageId", -1L) ?: -1L
        if (id > 0) pendingMessageId = id
    }
}
