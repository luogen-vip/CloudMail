package com.rogan.cloudmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Attachment
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
import kotlinx.coroutines.withContext

/** ===== 聊天模式 =====
 *  把邮件渲染成聊天气泡：底层仍是真实 IMAP/SMTP 邮件(不需要自建服务器)，
 *  只是"另一种看法"——同一批邮件随时可以切回传统邮件界面。
 *  纯新增模块，不改动任何邮件收发/详情/搜索逻辑。 */

data class ChatSession(
    val addr: String,
    val name: String,
    val lastSnippet: String,
    val lastDate: Long,
    val unread: Int,
    val isMine: Boolean   // 最后一条是我发的
)

/** 聊天会话列表：按对方邮箱分组(INBOX→发件人 / SENT→第一个收件人)，
 *  广告邮件不出现在聊天里；头像=联系人备注首字圆标；显示最后一条消息预览+未读数 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(nav: NavHostController) {
    val db = AppDatabase.get(LocalContext.current)
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var contacts by remember { mutableStateOf(listOf<com.rogan.cloudmail.data.Contact>()) }
    var tick by remember { mutableStateOf(0) }

    fun reload() {
        tick++
    }
    LaunchedEffect(Unit) {
        while (true) {
            val msgs = withContext(Dispatchers.IO) {
                db.messages().allMail().filter { !it.isAd }
            }
            contacts = withContext(Dispatchers.IO) { db.contacts().all() }
            val map = linkedMapOf<String, MutableList<MailMessage>>()
            for (m in msgs) {
                val peer = if (m.folder == "SENT")
                    m.toAddr.split(",").firstOrNull()?.trim() ?: continue
                else m.fromAddr
                if (peer.isBlank()) continue
                map.getOrPut(peer.lowercase()) { mutableListOf() }.add(m)
            }
            sessions = map.mapNotNull { (addr, list) ->
                val last = list.maxByOrNull { it.date } ?: return@mapNotNull null
                val remark = contacts.firstOrNull { it.email.lowercase() == addr }?.remark
                ChatSession(
                    addr = addr,
                    name = remark ?: last.fromName.ifBlank {
                        if (last.folder == "SENT") addr.substringBefore("@") else addr
                    },
                    lastSnippet = last.snippet.ifBlank { last.subject.ifBlank { "(无正文)" } },
                    lastDate = last.date,
                    unread = list.count { it.folder == "INBOX" && !it.isRead },
                    isMine = last.folder == "SENT"
                )
            }.sortedByDescending { it.lastDate }
            kotlinx.coroutines.delay(3000) // 3 秒轮询本地库：收信推送/发送完成后会话自动刷新
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("聊天", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("聊天消息底层仍是真实邮件，对方用 QQ/163 等任何邮箱都能正常收发",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有聊天：收发一封邮件后，这里会按联系人自动生成会话",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                return@Column
            }
            LazyColumn {
                items(sessions, key = { it.addr }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate("chat/" + android.net.Uri.encode(s.addr)) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AvatarOf(s.name, null, 44.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(fmtChatTime(s.lastDate), fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text((if (s.isMine) "我: " else "") + s.lastSnippet.replace("\n", " "),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.unread > 0) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFEF5350)) {
                                Text("${s.unread}", color = Color.White, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    // tick 仅用于保持 reload 可用性(轮询在 LaunchedEffect 内完成)
    LaunchedEffect(tick) {}
}

/** 与某人的聊天页：对方邮件靠左气泡、我发的靠右气泡；气泡点击看完整邮件详情；
 *  底部输入框直接发消息(底层=真实邮件，主题自动取消息首行，方便对方邮件客户端识别) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController, addr: String) {
    val db = AppDatabase.get(LocalContext.current)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<MailMessage>>(emptyList()) }
    var peerName by remember { mutableStateOf(addr.substringBefore("@")) }
    var myAcc by remember { mutableStateOf<com.rogan.cloudmail.data.Account?>(null) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(addr) {
        val c = withContext(Dispatchers.IO) { db.contacts().byEmail(addr) }
        peerName = c?.remark?.ifBlank { null } ?: addr.substringBefore("@")
        myAcc = withContext(Dispatchers.IO) { db.accounts().getDefault() }
        while (true) {
            val list = withContext(Dispatchers.IO) {
                db.messages().conversation(addr)
                    .filter { it.folder == "INBOX" || it.folder == "SENT" }
                    .sortedBy { it.date }
            }
            msgs = list
            // 进入聊天即把对方来信标为已读(和 IM 聊天一致)
            list.filter { it.folder == "INBOX" && !it.isRead }.forEach { m ->
                runCatching { withContext(Dispatchers.IO) { db.messages().setRead(m.id, true) } }
            }
            // 有新消息时自动滚到底部
            if (list.isNotEmpty()) {
                runCatching { listState.animateScrollToItem(list.size - 1) }
            }
            kotlinx.coroutines.delay(4000) // 停在聊天页时每 4 秒刷新本地库
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(peerName, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }
        )
    }, bottomBar = {
        // 输入栏：发出去的就是一封真实邮件(HTML 正文，换行=换行标签)
        Surface(shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom) {
                TextField(value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
                    placeholder = { Text("输入消息，直接发送(底层是真实邮件)", fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                    maxLines = 4)
                IconButton(onClick = {
                    val text = input.trim()
                    if (text.isEmpty() || sending) return@IconButton
                    sending = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val acc = myAcc ?: db.accounts().getDefault() ?: return@launch
                            val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                            val bodyHtml = "<div style=\"font-family:sans-serif;font-size:15px;white-space:pre-wrap\">" +
                                esc.replace("\n", "<br/>") + "</div>"
                            // 主题取消息首行(截 40 字)：对方邮件客户端里看得懂，不写"(无主题)"
                            val subject = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "聊天消息"
                            val itemId = db.outbox().insert(com.rogan.cloudmail.data.OutboxItem(
                                accountId = acc.id, toAddr = addr, ccAddr = "", bccAddr = "",
                                subject = subject, bodyHtml = bodyHtml, attachmentsJson = "[]",
                                readReceipt = false, cardMode = false, scheduledAt = null,
                                status = "SENDING", updatedAt = System.currentTimeMillis()))
                            com.rogan.cloudmail.mail.SendWorker.sendNow(ctx, itemId)
                            withContext(Dispatchers.Main) {
                                input = ""
                                // 立即本地回显一条"我发的"气泡(不等服务器已发送同步)，发完 4 秒内自动被真实记录替换
                                val now = System.currentTimeMillis()
                                msgs = msgs + MailMessage(
                                    id = -now, accountId = acc.id, folder = "SENT", uid = "local",
                                    messageId = "", subject = subject, fromAddr = acc.email, fromName = acc.nickname,
                                    toAddr = addr, ccAddr = "", date = now,
                                    bodyHtml = bodyHtml,
                                    snippet = android.text.Html.fromHtml(bodyHtml.replace("<br/>", "\n").replace(Regex("<[^>]+>"), ""),
                                        android.text.Html.FROM_HTML_MODE_LEGACY).toString(),
                                    isRead = true, isStarred = false, isAd = false,
                                    hasAttachments = false, attachmentsJson = "[]", loaded = true)
                                runCatching { listState.animateScrollToItem(msgs.size - 1) }
                            }
                        } finally { withContext(Dispatchers.Main) { sending = false } }
                    }
                }, enabled = input.isNotBlank() && !sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送",
                        tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("与 $addr 的全部往来(共 ${msgs.size} 条) · 点气泡看完整邮件",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            if (msgs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有往来消息，在下方输入框发第一条吧",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                return@Column
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs, key = { it.id }) { m ->
                    val mine = m.folder == "SENT"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        if (!mine) {
                            AvatarOf(peerName, null, 34.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(if (mine) Modifier.fillMaxWidth(0.82f) else Modifier.weight(1f),
                            horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                            if (m.subject.isNotBlank() && m.subject != subjectOf(m.snippet))
                                Text(m.subject, fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Surface(shape = RoundedCornerShape(
                                topStart = 14.dp, topEnd = 14.dp,
                                bottomStart = if (mine) 14.dp else 3.dp,
                                bottomEnd = if (mine) 3.dp else 14.dp),
                                color = if (mine) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { if (m.id > 0) nav.navigate("detail/${m.id}") }) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(m.snippet.replace("\n", "\n"), fontSize = 15.sp,
                                        color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface)
                                    if (m.hasAttachments) {
                                        Spacer(Modifier.height(2.dp))
                                        Icon(Icons.Default.Attachment, "附件", Modifier.size(14.dp),
                                            tint = if (mine) Color.White.copy(alpha = 0.85f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text(fmtChatTime(m.date) + if (m.id < 0) " 发送中…" else "",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                        if (mine) {
                            Spacer(Modifier.width(8.dp))
                            AvatarOf(myAcc?.nickname?.ifBlank { myAcc?.email?.substringBefore("@") ?: "我" } ?: "我",
                                myAcc?.avatarPath, 34.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun subjectOf(snippet: String) = snippet.take(40)

/** 聊天时间：今天显示 时:分，昨天显示"昨天"，更早显示 月/日 */
fun fmtChatTime(t: Long): String {
    if (t <= 0) return ""
    val cal = java.util.Calendar.getInstance()
    val now = cal.clone() as java.util.Calendar
    cal.timeInMillis = t
    return when {
        now.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR) &&
            now.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) ->
            "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        now.get(java.util.Calendar.DAY_OF_YEAR) - cal.get(java.util.Calendar.DAY_OF_YEAR) == 1 -> "昨天"
        else -> "%d/%d".format(cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
