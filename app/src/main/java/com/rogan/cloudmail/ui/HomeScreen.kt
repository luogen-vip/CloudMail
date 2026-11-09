package com.rogan.cloudmail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import com.rogan.cloudmail.data.*
import kotlinx.coroutines.launch

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
        composable("forward/{id}") { back ->
            ComposeScreen(nav, draftId = -1L, presetTo = "", forwardId = back.arguments?.getString("id")?.toLong() ?: -1L)
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

// 排序模式: 0=最新优先 1=最早优先 2=大小大→小 3=大小小→大
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
    var sortMode by remember { mutableStateOf(0) }
    var showSortMenu by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var lastAutoSync by remember { mutableStateOf(0L) }

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

    // 系统返回键：侧边栏打开时先关闭侧边栏，而不是退出APP
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val doSync = {
        if (!syncing && accounts.isNotEmpty()) {
            syncing = true
            scope.launch {
                val accs = if (folder.startsWith("account:"))
                    listOfNotNull(db.accounts().byId(folder.substringAfter(":").toLong()))
                else db.accounts().all()
                val newMails = mutableListOf<com.rogan.cloudmail.data.MailMessage>()
                for (a in accs) runCatching {
                    newMails += com.rogan.cloudmail.mail.MailService.syncAccount(ctx, a, db)
                }
                // 同步到的新邮件立即弹通知(与后台同步一致，不漏提醒)
                newMails.filter { !it.isRead && it.folder == "INBOX" }
                    .forEach { com.rogan.cloudmail.mail.SyncWorker.notifyNew(ctx, it.accountId, it) }
                syncing = false
                refresh()
                reloadKey++
                // 只在有新邮件时提示，不再每次都弹「已是最新」
                if (newMails.isNotEmpty()) syncMsg = "收到 ${newMails.size} 封新邮件"
            }
        }
        Unit
    }

    // 回到前台/进入主页时自动同步(8秒节流)，保证新邮件马上可见
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (System.currentTimeMillis() - lastAutoSync > 8_000) {
                    lastAutoSync = System.currentTimeMillis()
                    doSync()
                }
                refresh()
            }
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                // 账号区
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("云邮", modifier = Modifier.padding(20.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    accounts.forEach { acc ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                folder = "account:${acc.id}"
                                reloadKey++
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
                    FolderItem(Icons.Default.Mail, "全部邮件", folder == "all") { folder = "all"; reloadKey++; scope.launch { drawer.close() } }
                    FolderItem(Icons.Default.MarkEmailUnread, "未读邮件", folder == "unread") { folder = "unread"; reloadKey++; scope.launch { drawer.close() } }
                    FolderItem(Icons.Default.Star, "星标邮件", folder == "starred") { folder = "starred"; reloadKey++; scope.launch { drawer.close() } }
                    FolderItem(Icons.Default.Send, "已发送", folder == "sent") { folder = "sent"; reloadKey++; scope.launch { drawer.close() } }
                    FolderItem(Icons.Default.Schedule, "草稿/待发送", folder == "outbox") { folder = "outbox"; reloadKey++; scope.launch { drawer.close() } }
                    FolderItem(Icons.Default.Delete, "已删除(回收站)", folder == "trash") { folder = "trash"; reloadKey++; scope.launch { drawer.close() } }
                    Spacer(Modifier.height(16.dp))
                    Text("作者：罗根", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(folderTitle(folder)) },
                    actions = {
                        IconButton(onClick = { doSync() }) {
                            Icon(Icons.Default.Refresh, "同步/刷新收件(检查新邮件)")
                        }
                        IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Default.Sort, "排序") }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            listOf("按日期：最新优先", "按日期：最早优先", "按大小：从大到小", "按大小：从小到大")
                                .forEachIndexed { i, s ->
                                    DropdownMenuItem(text = { Text(s, fontSize = 13.sp, fontWeight = if (sortMode == i) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { sortMode = i; showSortMenu = false })
                                }
                        }
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
                if (folder == "outbox") OutboxList(nav, refresh) else MessageList(nav, folder, refresh, sortMode, reloadKey)
                if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                syncMsg?.let { t ->
                    Snackbar(Modifier.padding(16.dp)) { Text(t) }
                    LaunchedEffect(t) { kotlinx.coroutines.delay(2500); syncMsg = null }
                }
            }
        }
        // 首次使用引导：还没有任何账号时,引导先添加邮箱
        if (accounts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(Modifier.padding(28.dp)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("欢迎使用云邮", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("先添加一个邮箱账号，才能收发邮件。\n推荐 QQ / 163 / 139 / Outlook / Gmail 等，\n登录密码填「授权码」即可。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { nav.navigate("addAccount") }) { Text("立即添加邮箱账号") }
                    }
                }
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
    f == "outbox" -> "草稿/待发送"
    f == "trash" -> "已删除(回收站)"
    f.startsWith("account:") -> "邮箱邮件"
    else -> "邮件"
}

/* ================== 邮件列表(支持多选删除/排序/计数) ================== */

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MessageList(nav: NavHostController, folder: String, refreshParent: () -> Unit,
                sortMode: Int = 0, reloadKey: Int = 0) {
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
        msgs = when (sortMode) {
            1 -> msgs.sortedBy { it.date }
            2 -> msgs.sortedByDescending { it.size }
            3 -> msgs.sortedBy { it.size }
            else -> msgs.sortedByDescending { it.date }
        }
    }; Unit }
    LaunchedEffect(folder, reloadKey, sortMode) { reload() }
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life, folder) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload()
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }

    // 多选模式
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showClearTrash by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selected = emptySet() }

    // 系统返回键：多选模式下先退出多选，而不是退出 APP
    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelection() }

    // 排序/刷新后回到列表顶部，保证正在看的邮件位置可预测
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(sortMode) { listState.scrollToItem(0) }

    val deleteIds = { ids: Set<Long>, forever: Boolean ->
        scope.launch {
            ids.forEach { id ->
                if (forever) db.messages().deleteForever(id)
                else db.messages().setFolder(id, "TRASH")
            }
            exitSelection(); refreshParent(); reload()
        }
        Unit
    }

    Box(Modifier.fillMaxSize()) {
        if (msgs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (folder == "trash") "回收站为空" else "暂无邮件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        Column(Modifier.fillMaxSize()) {
            // 统计条：共多少封(实时)
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${msgs.size} 封", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (folder == "unread") TextButton(onClick = {
                    scope.launch { db.messages().markAllRead(); refreshParent(); reload() }
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("全部标为已读", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (folder == "trash") {
                    TextButton(onClick = {
                        scope.launch { db.messages().restoreAllTrash(); refreshParent(); reload() }
                    }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("还原全部", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { showClearTrash = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("清空回收站", fontSize = 12.sp, color = Color(0xFFEF6C00))
                    }
                }
            }
            if (selectionMode) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 封", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        selected = if (selected.size == msgs.size) emptySet() else msgs.map { it.id }.toSet()
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (selected.size == msgs.size) "取消全选" else "全选", color = Color.White, fontSize = 12.sp) }
                    if (folder == "starred") {
                        TextButton(onClick = {
                            scope.launch {
                                selected.forEach { db.messages().setStar(it, false) }
                                exitSelection(); refreshParent(); reload()
                            }
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("取消星标", color = Color.White, fontSize = 12.sp) }
                    }
                    if (folder == "trash") {
                        TextButton(onClick = {
                            scope.launch {
                                selected.forEach { db.messages().setFolder(it, "INBOX") }
                                exitSelection(); refreshParent(); reload()
                            }
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("还原", color = Color.White, fontSize = 12.sp) }
                    }
                    TextButton(onClick = {
                        deleteIds(selected, folder == "trash")
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (folder == "trash") "彻底删除" else "删除", color = Color.White, fontSize = 12.sp)
                    }
                    TextButton(onClick = { exitSelection() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("取消", color = Color.White, fontSize = 12.sp) }
                }
            }
            LazyColumn(state = listState) {
                items(msgs, key = { it.id }) { m ->
                    val contact = remember(m.id) { mutableStateOf<Contact?>(null) }
                    LaunchedEffect(m.fromAddr) { contact.value = db.contacts().byEmail(m.fromAddr) }
                    val shownName = contact.value?.remark?.takeIf { it.isNotBlank() } ?: m.fromName.ifBlank { m.fromAddr }
                    val isSel = m.id in selected
                    Row(Modifier.fillMaxWidth().background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                        if (selectionMode) {
                            Checkbox(checked = isSel, onCheckedChange = {
                                selected = if (it) selected + m.id else selected - m.id
                            })
                        }
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selected = if (isSel) selected - m.id else selected + m.id
                                        } else nav.navigate("detail/${m.id}")
                                    },
                                    onLongClick = { selectionMode = true; selected = selected + m.id })
                                .padding(horizontal = if (selectionMode) 4.dp else 14.dp, vertical = 10.dp)) {
                                AvatarOf(shownName, null, 44.dp)
                                // 未读蓝点：一眼区分已读/未读(所有文件夹均生效)
                                if (!m.isRead) Box(
                                    Modifier.padding(end = 4.dp).size(10.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Column(Modifier.weight(1f).padding(start = if (!m.isRead) 4.dp else 10.dp)) {
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
                                        if (m.size > 0) Text(fmtSize(m.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        }
        // 清空回收站确认
        if (showClearTrash) {
            AlertDialog(onDismissRequest = { showClearTrash = false },
                title = { Text("清空回收站") },
                text = { Text("将彻底删除回收站里的全部邮件，不可恢复。确定吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearTrash = false
                        scope.launch { db.messages().emptyTrash(); refreshParent(); reload() }
                    }) { Text("清空") }
                },
                dismissButton = { TextButton(onClick = { showClearTrash = false }) { Text("取消") } })
        }
    }
}

/* ================== 待发送列表(草稿/定时/失败) ================== */

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
    var deleteTarget by remember { mutableStateOf<OutboxItem?>(null) }
    deleteTarget?.let { o ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text("删除草稿") },
            text = { Text("确定删除这条「${o.subject.ifBlank { "(无主题)" }}」吗？") },
            confirmButton = { TextButton(onClick = {
                deleteTarget = null
                scope.launch {
                    if (o.scheduledAt != null) com.rogan.cloudmail.mail.SendWorker.cancelScheduled(ctx, o.id)
                    db.outbox().delete(o.id); reload()
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有草稿或待发送的邮件") }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text("共 ${items.size} 条(草稿/定时发送/发送失败)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        LazyColumn {
            items(items, key = { it.id }) { o ->
                val acc = remember { mutableStateOf<Account?>(null) }
                LaunchedEffect(o.accountId) { acc.value = db.accounts().byId(o.accountId) }
                Column(Modifier.fillMaxWidth().clickable { nav.navigate("compose/${o.id}") }.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.subject.ifBlank { "(无主题)" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        when {
                            o.status == "FAILED" -> {
                                Text("发送失败", fontSize = 10.sp, color = Color.White,
                                    modifier = Modifier.background(Color(0xFFD32F2F), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            o.scheduledAt != null -> {
                                Text("定时发送", fontSize = 10.sp, color = Color.White,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            else -> {
                                Text("草稿", fontSize = 10.sp, color = Color.White,
                                    modifier = Modifier.background(Color(0xFF78909C), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        IconButton(onClick = { deleteTarget = o }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, "删除", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (o.status == "FAILED" && o.error.isNotBlank()) {
                        Text("原因：${o.error}", fontSize = 12.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                    }
                    Text("收件人：${o.toAddr}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            o.status == "FAILED" -> "点击可修改后重新发送"
                            o.scheduledAt != null -> "将于 ${fmtFull(o.scheduledAt)} 自动真实发送，点击可编辑或取消定时"
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
}
