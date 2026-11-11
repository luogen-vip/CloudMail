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
        // 小窗自愈：系统清后台后从最近任务/桌面恢复时，任务可能带着“自由窗口(小窗)”模式，
        // APP 就会在屏幕中间以小窗打开。检测到多窗口模式且不是自愈重试时，
        // 用 NEW_TASK|CLEAR_TASK 以全新全屏任务重启自己，保证任何情况下都是全屏打开。
        // (Manifest 已声明 resizeableActivity=false，这是对历史遗留小窗任务的兑底)
        val retry = intent?.getBooleanExtra("fullscreenRetry", false) ?: false
        if (!retry && android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode) {
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
