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
