package com.rogan.cloudmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.AppDatabase
import com.rogan.cloudmail.data.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 往来邮件：展示我和某个邮箱地址之间的全部往来(我发的 + TA 发的，含抄送)。
 *  从邮件详情页点「往来邮件」进入；点击任一封回到该邮件详情 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(nav: NavHostController, addr: String) {
    val db = AppDatabase.get(LocalContext.current)
    var msgs by remember { mutableStateOf<List<MailMessage>>(emptyList()) }
    LaunchedEffect(addr) {
        msgs = kotlinx.coroutines.withContext(Dispatchers.IO) { db.messages().conversation(addr) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("与 $addr 的往来邮件", fontSize = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("共 ${msgs.size} 封(按时间排序，含我发出的)", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            if (msgs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有和这个地址的往来邮件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            LazyColumn {
                items(msgs, key = { it.id }) { m ->
                    val sent = m.folder == "SENT"
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate("detail/${m.id}") }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // 方向图标：↗ 我发出的 / ↘ 对方发来的
                        Icon(if (sent) Icons.Default.CallMade else Icons.Default.CallReceived, null,
                            Modifier.size(18.dp), tint = if (sent) Color(0xFF1565C0) else Color(0xFF2E7D32))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (sent) "我 → ${m.toAddr.substringBefore(",")}" else m.fromName.ifBlank { m.fromAddr },
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(fmtTime(m.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(m.subject.ifBlank { "(无主题)" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.snippet, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (m.pinnedAt > 0) Text("置顶", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
