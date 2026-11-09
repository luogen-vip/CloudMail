package com.rogan.cloudmail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.rogan.cloudmail.data.*
import kotlinx.coroutines.launch
import java.io.File

/* ================== 根导航 ================== */

@Composable
fun RootNav(pendingMessageId: () -> Long, onConsumePending: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(nav, pendingMessageId, onConsumePending) }
        composable("detail/{id}") { back ->
            MessageDetailScreen(nav, back.arguments?.getString("id")?.toLong() ?: 0)
        }
        composable("compose") { ComposeScreen(nav, draftId = -1L, presetTo = "") }
        composable("compose/{draftId}") { back ->
            ComposeScreen(nav, draftId = back.arguments?.getString("draftId")?.toLong() ?: -1L, presetTo = "")
        }
        composable("composeTo/{to}") { back ->
            ComposeScreen(nav, draftId = -1L, presetTo = back.arguments?.getString("to") ?: "")
        }
        composable("contacts") { ContactsScreen(nav) }
        composable("contactEdit/{id}") { back ->
            ContactEditScreen(nav, back.arguments?.getString("id")?.toLong() ?: -1L)
        }
        composable("addAccount") { AddAccountScreen(nav) }
        composable("editAccount/{id}") { back ->
            EditAccountScreen(nav, back.arguments?.getString("id")?.toLong() ?: -1L)
        }
        composable("settings") { SettingsScreen(nav) }
        composable("search") { SearchScreen(nav) }
    }
}

/* ================== 主页(抽屉+列表) ================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, pendingMessageId: () -> Long, onConsumePending: () -> Unit) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var folder by remember { mutableStateOf("all") } // all/unread/starred/sent/outbox/trash/account:ID
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var unreadMap by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var syncing by remember { mutableStateOf(false) }
    var outboxCount by remember { mutableStateOf(0) }

    val refresh = {
        scope.launch {
            accounts = db.accounts().all()
            unreadMap = accounts.associate { it.id to db.messages().unreadCount(it.id) }
            outboxCount = db.outbox().all().size
            if (folder.startsWith("account:") && accounts.none { it.id.toString() == folder.substringAfter(":") }) folder = "all"
        }
        Unit
    }
    LaunchedEffect(Unit) {
        refresh()
        val pid = pendingMessageId()
        if (pid > 0) {
            onConsumePending()
            nav.navigate("detail/$pid")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                // 账号区
                Text("云邮", modifier = Modifier.padding(20.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                accounts.forEach { acc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            folder = "account:${acc.id}"
                            scope.launch { drawer.close() }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(acc)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(acc.nickname.ifBlank { acc.email }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(acc.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (acc.isDefault) Text("主邮箱", fontSize = 10.sp, color = Color.White,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 6.dp, vertical = 2.dp))
                        val un = unreadMap[acc.id] ?: 0
                        if (un > 0) Text("$un", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
                ListItem(headlineContent = { Text("添加邮箱账号") },
                    leadingContent = { Icon(Icons.Default.PersonAdd, null) },
                    modifier = Modifier.clickable { nav.navigate("addAccount") })
                ListItem(headlineContent = { Text("联系人") },
                    leadingContent = { Icon(Icons.Default.People, null) },
                    modifier = Modifier.clickable { nav.navigate("contacts") })
                ListItem(headlineContent = { Text("搜索") },
                    leadingContent = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.clickable { nav.navigate("search") })
                ListItem(headlineContent = { Text("设置") },
                    leadingContent = { Icon(Icons.Default.Settings, null) },
                    modifier = Modifier.clickable { nav.navigate("settings") })
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                // 文件夹
                FolderItem(Icons.Default.Mail, "全部邮件", folder == "all") { folder = "all"; scope.launch { drawer.close() } }
                FolderItem(Icons.Default.MarkEmailUnread, "未读邮件", folder == "unread") { folder = "unread"; scope.launch { drawer.close() } }
                FolderItem(Icons.Default.Star, "星标邮件", folder == "starred") { folder = "starred"; scope.launch { drawer.close() } }
                FolderItem(Icons.Default.Send, "已发送", folder == "sent") { folder = "sent"; scope.launch { drawer.close() } }
                FolderItem(Icons.Default.Schedule, "待发送", folder == "outbox") { folder = "outbox"; scope.launch { drawer.close() } }
                FolderItem(Icons.Default.Delete, "已删除", folder == "trash") { folder = "trash"; scope.launch { drawer.close() } }
                Spacer(Modifier.height(16.dp))
                Text("作者：罗根", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(folderTitle(folder)) },
                    actions = {
                        IconButton(onClick = {
                            syncing = true
                            scope.launch {
                                val accs = if (folder.startsWith("account:"))
                                    listOfNotNull(db.accounts().byId(folder.substringAfter(":").toLong()))
                                else db.accounts().all()
                                for (a in accs) runCatching { com.rogan.cloudmail.mail.MailService.syncAccount(ctx, a, db) }
                                syncing = false
                                refresh()
                            }
                        }) { Icon(Icons.Default.Refresh, "同步") }
                        IconButton(onClick = { nav.navigate("search") }) { Icon(Icons.Default.Search, "搜索") }
                    }
                )
            },
            floatingActionButton = {
                if (folder != "outbox" && folder != "trash")
                    ExtendedFloatingActionButton(onClick = { nav.navigate("compose") }) {
                        Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("写邮件")
                    }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (folder == "outbox") OutboxList(nav, refresh) else MessageList(nav, folder, refresh)
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        LaunchedEffect(Unit) { refresh() }
    }
}

@Composable
private fun FolderItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sel: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal) },
        leadingContent = { Icon(icon, null, tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun folderTitle(f: String) = when {
    f == "all" -> "全部邮件"
    f == "unread" -> "未读邮件"
    f == "starred" -> "星标邮件"
    f == "sent" -> "已发送"
    f == "outbox" -> "待发送"
    f == "trash" -> "已删除"
    f.startsWith("account:") -> "邮箱邮件"
    else -> "邮件"
}

/* ================== 邮件列表 ================== */

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MessageList(nav: NavHostController, folder: String, refreshParent: () -> Unit) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    var msgs by remember(folder) { mutableStateOf<List<MailMessage>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val reload = { scope.launch {
        msgs = when {
            folder == "all" -> db.messages().allMail()
            folder == "unread" -> db.messages().unread()
            folder == "starred" -> db.messages().starred()
            folder == "sent" -> db.messages().sent()
            folder == "trash" -> db.messages().trash()
            folder.startsWith("account:") -> db.messages().byAccount(folder.substringAfter(":").toLong())
            else -> db.messages().allMail()
        }
    }; Unit }
    LaunchedEffect(folder) { reload() }
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life, folder) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    if (msgs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (folder == "trash") "回收站为空" else "暂无邮件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var longPressTarget by remember { mutableStateOf<MailMessage?>(null) }
    longPressTarget?.let { lm ->
        AlertDialog(
            onDismissRequest = { longPressTarget = null },
            title = { Text("删除邮件") },
            text = { Text(if (lm.folder == "TRASH") "将彻底删除这封邮件，不可恢复。" else "删除后移入「已删除」，可稍后彻底删除或恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (lm.folder == "TRASH") db.messages().deleteForever(lm.id)
                    else { lm.folder = "TRASH"; db.messages().update(lm) }
                    longPressTarget = null; reload()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (lm.folder == "TRASH") { db.messages().setFolder(lm.id, "INBOX"); reload() }
                    longPressTarget = null
                }) { Text(if (lm.folder == "TRASH") "恢复邮件" else "取消") }
            }
        )
    }
    LazyColumn {
        items(msgs, key = { it.id }) { m ->
            val contact = remember { mutableStateOf<Contact?>(null) }
            LaunchedEffect(m.fromAddr) { contact.value = db.contacts().byEmail(m.fromAddr) }
            val shownName = contact.value?.remark?.ifBlank { null } ?: m.fromName.ifBlank { m.fromAddr }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { nav.navigate("detail/${m.id}") }, onLongClick = { longPressTarget = m })
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    AvatarOf(shownName, null, 44.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shownName, fontSize = 15.sp,
                                fontWeight = if (!m.isRead) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(fmtTime(m.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(m.subject.ifBlank { "(无主题)" }, fontSize = 14.sp,
                            fontWeight = if (!m.isRead) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.snippet, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (m.isAd) Text("广告", fontSize = 10.sp, color = Color.White,
                                modifier = Modifier.background(Color(0xFFEF6C00), MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 1.dp))
                            if (m.hasAttachments) Icon(Icons.Default.AttachFile, null, Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (m.isStarred) Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = Color(0xFFFBC02D))
                            if (m.folder == "SENT") Text("已发送至 ${m.toAddr}", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/* ================== 待发送列表 ================== */

@Composable
fun OutboxList(nav: NavHostController, refreshParent: () -> Unit) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    var items by remember { mutableStateOf<List<OutboxItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val reload = {
        scope.launch { items = db.outbox().all(); refreshParent() }
        Unit
    }
    LaunchedEffect(Unit) { items = db.outbox().all() }
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { items = db.outbox().all() }
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有待发送的邮件") }
        return
    }
    LazyColumn {
        items(items, key = { it.id }) { o ->
            val acc = remember { mutableStateOf<Account?>(null) }
            LaunchedEffect(o.accountId) { acc.value = db.accounts().byId(o.accountId) }
            Column(Modifier.fillMaxWidth().clickable { nav.navigate("compose/${o.id}") }.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.subject.ifBlank { "(无主题)" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (o.scheduledAt != null) {
                        Text("定时发送", fontSize = 10.sp, color = Color.White,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
                    } else {
                        Text("草稿", fontSize = 10.sp, color = Color.White,
                            modifier = Modifier.background(Color(0xFF78909C), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Text("收件人：${o.toAddr}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        o.scheduledAt != null -> "将于 ${fmtFull(o.scheduledAt)} 自动定时发送，点击可编辑或取消定时"
                        else -> "已保存未发送，点击可继续编辑或立即发送"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary
                )
                Text("发件账号：${acc.value?.email ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 10.dp))
            }
        }
    }
}
