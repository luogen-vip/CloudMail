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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
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
        composable("labels") { LabelsScreen(nav) }
        composable("browser/{url}") { back ->
            BrowserScreen(nav, java.net.URLDecoder.decode(back.arguments?.getString("url") ?: "", "UTF-8"))
        }
        composable("conv/{addr}") { back ->
            ConversationScreen(nav, java.net.URLDecoder.decode(back.arguments?.getString("addr") ?: "", "UTF-8"))
        }
        composable("reedit/{id}") { back ->
            ComposeScreen(nav, draftId = -1L, presetTo = "", reeditId = back.arguments?.getString("id")?.toLong() ?: -1L)
        }
        composable("search") { SearchScreen(nav) }
    }
}

/* ================== 主页(抽屉+列表) ================== */

// 跨页面文件夹跳转桥：标签管理页点某个标签→回主页并直接打开该标签的邮件列表
object FolderBridge {
    var pendingFolder: String? = null
    fun consume(): String? = pendingFolder.also { pendingFolder = null }
}

// 排序模式: 0=最新优先 1=最早优先 2=大小大→小 3=大小小→大
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, pendingMessageId: () -> Long, onConsumePending: () -> Unit) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    // folder/sortMode 用 rememberSaveable：进入详情页返回后仍保留当前文件夹(星标/未读/已发送/回收站)，不再重置回“全部邮件”
    var folder by rememberSaveable { mutableStateOf("all") } // all/unread/starred/sent/outbox/trash/account:ID/label:ID
    var labels by remember { mutableStateOf<List<com.rogan.cloudmail.data.Label>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var unreadMap by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var syncing by remember { mutableStateOf(false) }
    var outboxCount by remember { mutableStateOf(0) }
    var sortMode by rememberSaveable { mutableStateOf(0) }
    var sortNonce by remember { mutableStateOf(0) } // 每次点排序菜单都 +1：即使重复点同一排序也强制重排+回顶
    var scrollNonce by remember { mutableStateOf(0) } // 有新邮件/手动刷新时 +1：列表强制跳回绝对顶部显示最新邮件
    var showSortMenu by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var syncMsg by remember { mutableStateOf<String?>(null) }
    var lastAutoSync by remember { mutableStateOf(0L) }
    var lastRefreshAt by remember { mutableStateOf(0L) }
    var manualSync by remember { mutableStateOf(false) } // 只有手动点刷新才显示顶部进度条
    // 粘性的“首次同步未完成”状态：首次登录同步期间空文件夹连续显示“正在同步”，
    // 不再在两轮同步间隙闪“暂无邮件”；同步完成(或超时兑底)后才显示真实的空状态文案
    var initialSyncDone by remember { mutableStateOf(false) }
    var dbEmpty by remember { mutableStateOf(true) }

    // 提示条用 Scaffold 标准的 SnackbarHost：之前直接把 Snackbar 放在内容层顶部，
    // 会被顶栏遮住看不见(与写邮件页同样的 bug)，改用标准宿主后提示一定可见
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(syncMsg) {
        syncMsg?.let { snackbar.showSnackbar(it); syncMsg = null }
    }

    val refresh = {
        scope.launch {
            // 数据库查询移到 IO 线程：之前在主线程读库，账号/邮件多时会卡顿一下，
            // 表现为滑动列表偶发顿住、点击没反应，再操作一次才正常
            // 加固：整块包上 runCatching，数据库偶发异常不能让 APP 闪退；失败时 2 秒后的轮询会自动重试
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    val a = db.accounts().all()
                    a.forEach { com.rogan.cloudmail.mail.MailService.startPush(ctx, it, db) }
                    val u = a.associate { it.id to db.messages().unreadCount(it.id) }
                    val o = db.outbox().all().size
                    val e = db.messages().countAll() == 0
                    val l = db.labels().all()
                    listOf(a, u, o, e, l)
                }.getOrDefault(null)
            }
            if (result == null) return@launch
            val (accs, uMap, ob, empty, lbs) = result
            accounts = accs as List<com.rogan.cloudmail.data.Account>
            unreadMap = uMap as Map<Long, Int>
            outboxCount = ob as Int
            dbEmpty = empty as Boolean
            labels = lbs as List<com.rogan.cloudmail.data.Label>
            if (folder.startsWith("account:") && accounts.none { it.id.toString() == folder.substringAfter(":") }) folder = "all"
        }
        Unit
    }
    LaunchedEffect(Unit) {
        refresh()
        // 从标签管理页跳转过来：直接打开指定标签的邮件列表；同时把侧边栏收起来，
        // 之前侧边栏保持打开状态，用户以为“点标签只是打开了侧边栏”
        FolderBridge.consume()?.let { folder = it; reloadKey++; scope.launch { drawer.close() } }
        val pid = pendingMessageId()
        if (pid > 0) {
            onConsumePending()
            nav.navigate("detail/$pid")
        }
    }

    // 系统返回键逻辑：
    // 1) 侧边栏打开 → 关闭侧边栏(若刚才是“全部邮件页按返回打开的侧边栏”且间隔很短，视为要退出 APP)
    // 2) 在分类(星标/未读/已发送/草稿/回收站/账号/标签)里 → 先回「全部邮件」
    // 3) 在「全部邮件」且侧边栏关闭 → 打开侧边栏方便去其他文件夹，并提示“再按一次退出”；
    //    2 秒内再按一次才真正退出 APP(不会误触直接退出)
    var lastBackAt by remember { mutableStateOf(0L) }
    fun currentActivity(): android.app.Activity? {
        var c: android.content.Context = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }
    BackHandler(enabled = drawer.isOpen) {
        val exitNow = folder == "all" && System.currentTimeMillis() - lastBackAt < 2000
        scope.launch { drawer.close() }
        if (exitNow) currentActivity()?.finishAffinity()
    }
    BackHandler(enabled = !drawer.isOpen && folder != "all") { folder = "all"; reloadKey++; scrollNonce++ }
    BackHandler(enabled = !drawer.isOpen && folder == "all") {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            currentActivity()?.finishAffinity()
        } else {
            lastBackAt = now
            syncMsg = "再按一次返回键退出云邮；可从侧边栏进入其他文件夹"
            scope.launch { drawer.open() }
        }
    }

    // 真实立即同步：必须在 IO 线程执行 IMAP 网络请求(主线程会被系统拦截导致“刷新没反应”)
    // showBar=只有手动点刷新按钮才显示顶部进度条；后台自动同步静默进行，不打扰浏览
    val doSync = { showBar: Boolean ->
        if (!syncing && accounts.isNotEmpty()) {
            syncing = true
            if (showBar) {
                manualSync = true
                syncMsg = "正在刷新收件箱…" // 手动刷新立刻给出可见反馈，用户知道刷新已生效
            }
            scope.launch(Dispatchers.IO) {
                val accs = runCatching {
                    if (folder.startsWith("account:"))
                        listOfNotNull(db.accounts().byId(folder.substringAfter(":").toLong()))
                    else db.accounts().all()
                }.getOrDefault(emptyList())
                val newMails = mutableListOf<com.rogan.cloudmail.data.MailMessage>()
                for (a in accs) runCatching {
                    newMails += com.rogan.cloudmail.mail.MailService.syncAccount(ctx, a, db)
                }
                // 同步到的新邮件立即弹通知(与后台同步一致，不漏提醒)
                newMails.filter { !it.isRead && it.folder == "INBOX" }
                    .forEach { com.rogan.cloudmail.mail.SyncWorker.notifyNew(ctx, it.accountId, it) }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    syncing = false
                    manualSync = false
                    val wasEmpty = dbEmpty
                    val firstDone = !initialSyncDone
                    initialSyncDone = true
                    refresh()
                    // 按需重建列表：首次同步完成/数据库从空变非空/手动刷新/有新邮件才重建；
                    // 之前“每轮都重建”导致列表每秒重组一次，滑动顿一下没反应
                    if (showBar || newMails.isNotEmpty() || firstDone || (wasEmpty && !dbEmpty)) {
                        reloadKey++
                        if (newMails.isNotEmpty()) scrollNonce++
                    }
                    if (showBar) {
                        // 手动刷新一定给结果反馈
                        syncMsg = if (newMails.isEmpty()) "已刷新，暂无新邮件" else "已刷新，收到 ${newMails.size} 封新邮件"
                    } else if (newMails.isNotEmpty()) {
                        syncMsg = "收到 ${newMails.size} 封新邮件"
                    }
                }
            }
        } else if (showBar) {
            // 已有同步在进行：等它结束后立刻再补一次真实拉取，保证手动刷新一定拿到最新
            syncMsg = "正在刷新收件箱…"
            scope.launch(Dispatchers.IO) {
                while (syncing) kotlinx.coroutines.delay(200)
                val nm = mutableListOf<com.rogan.cloudmail.data.MailMessage>()
                runCatching {
                    val accs = db.accounts().all()
                    for (a in accs) runCatching { nm += com.rogan.cloudmail.mail.MailService.syncAccount(ctx, a, db) }
                    nm.filter { !it.isRead && it.folder == "INBOX" }
                        .forEach { com.rogan.cloudmail.mail.SyncWorker.notifyNew(ctx, it.accountId, it) }
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    manualSync = false
                    refresh()
                    reloadKey++
                    if (nm.isNotEmpty()) scrollNonce++
                    syncMsg = if (nm.isEmpty()) "已刷新，暂无新邮件" else "已刷新，收到 ${nm.size} 封新邮件"
                }
            }
        }
        Unit
    }

    // 前台保底同步 + 推送感知：IDLE 推送线程收到新邮件后入库并递增 pushVersion，
    // 这里检测到变化立即刷新列表+回顶(接近 QQ 邮箱体验)。
    // 轮询兑底降频到每 3 秒一次(推送已经在毫秒级收信，高频轮询只会争抢 IMAP 连接和刷新列表，
    // 造成滑动顿住/正文加载等待)，pushVersion 检查仍保持 500ms
    LaunchedEffect(accounts.isNotEmpty()) {
        var lastPush = -1
        var lastSyncAt = 0L
        while (accounts.isNotEmpty()) {
            kotlinx.coroutines.delay(500)
            val pv = com.rogan.cloudmail.mail.MailService.pushVersion
            if (pv != lastPush) {
                lastPush = pv
                refresh(); reloadKey++; scrollNonce++
            } else if (System.currentTimeMillis() - lastSyncAt > 3000) {
                lastSyncAt = System.currentTimeMillis()
                doSync(false)
            }
        }
    }

    // 超时兑底：即使同步一直不成功，20 秒后也恢复正常空状态提示，不永远转圈
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(20000)
        initialSyncDone = true
    }

    // 待发送条数实时轮询(每 2 秒)：发送成功后顶部“待发送邮件 N 封”横条立即消失，
    // 不再等下一次同步/切页面才更新
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            runCatching { outboxCount = kotlinx.coroutines.withContext(Dispatchers.IO) { db.outbox().all().size } }
            // 标签列表也实时刷新：详情页里新建/删除标签后抽屉马上同步
            runCatching { labels = kotlinx.coroutines.withContext(Dispatchers.IO) { db.labels().all() } }
        }
    }

    // 回到前台/进入主页时自动同步(8秒节流)，保证新邮件马上可见
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (System.currentTimeMillis() - lastAutoSync > 8_000) {
                    lastAutoSync = System.currentTimeMillis()
                    doSync(false)
                }
                // 刷新节流(1 秒)：从详情页返回时立即全量刷新会引发重组风暴，
                // 是“返回后点击下一封邮件第一下没反应”的推手之一；轮询本身已保证数据实时，这里只做兑底
                if (System.currentTimeMillis() - lastRefreshAt > 1_000) {
                    lastRefreshAt = System.currentTimeMillis()
                    refresh()
                }
                // 从标签管理页点了某个标签回来：切到该标签的邮件列表，并收起侧边栏
                FolderBridge.consume()?.let { folder = it; reloadKey++; scope.launch { drawer.close() } }
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
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    // 标签区：每个标签像一个文件夹，点进去只看打上该标签的邮件；
                    // “管理”进入标签管理页(新建/勾选删除/全选删除)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("标签", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("管理/新建", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { nav.navigate("labels") }.padding(4.dp))
                    }
                    if (labels.isEmpty()) {
                        Text("暂无标签，点「管理/新建」创建", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    labels.forEach { lb ->
                        FolderItem(Icons.Default.Label, lb.name, folder == "label:${lb.id}") {
                            folder = "label:${lb.id}"; reloadKey++; scope.launch { drawer.close() }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("作者：罗根", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(folderTitle(folder)) },
                    actions = {
                        IconButton(onClick = { doSync(true) }) {
                            Icon(Icons.Default.Refresh, "同步/刷新收件(检查新邮件)")
                        }
                        IconButton(onClick = { showSortMenu = true }) { Icon(Icons.Default.Sort, "排序") }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            listOf("按日期：最新优先", "按日期：最早优先", "按大小：从大到小", "按大小：从小到大")
                                .forEachIndexed { i, s ->
                                    DropdownMenuItem(text = { Text(s, fontSize = 13.sp, fontWeight = if (sortMode == i) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { sortMode = i; sortNonce++; showSortMenu = false })
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
                Column(Modifier.fillMaxSize()) {
                    // 待发送状态条(仿 QQ 邮箱)：发送中/待发送的邮件在最顶部醒目提示，点击进入草稿/待发送列表
                    if (outboxCount > 0 && folder != "outbox") {
                        Row(Modifier.fillMaxWidth().clickable { folder = "outbox"; reloadKey++ }
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("  待发送邮件 ${outboxCount} 封(含正在发送/定时/草稿)，点击查看",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }
                    }
                    if (folder == "outbox") OutboxList(nav, refresh) else MessageList(nav, folder, refresh, sortMode, reloadKey, sortNonce, scrollNonce,
                        syncingEmpty = !initialSyncDone && dbEmpty)
                }
                if (manualSync) LinearProgressIndicator(Modifier.fillMaxWidth())
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
    f.startsWith("label:") -> "标签邮件"
    else -> "邮件"
}

/* ================== 邮件列表(支持多选删除/排序/计数) ================== */

// 列表内存缓存：从详情页返回时 MessageList 会整体重建，若无缓存那一瞬间列表为空(显示加载转圈)，
// 用户此刻的第一次滑动会落空——正是“返回后立刻滑动顿一下没反应”的根因。
// 有缓存时返回瞬间立即渲染原列表，滑动无缝衔接；后台轮询继续保证数据实时
private val msgListCache = mutableMapOf<String, List<MailMessage>>()

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MessageList(nav: NavHostController, folder: String, refreshParent: () -> Unit,
                sortMode: Int = 0, reloadKey: Int = 0, sortNonce: Int = 0, scrollNonce: Int = 0,
                syncingEmpty: Boolean = false) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    var msgs by remember(folder) { mutableStateOf(msgListCache[folder] ?: emptyList()) }
    // 首次加载数据库完成前显示加载状态，避免闪烁“暂无邮件”误导用户；有缓存则认为已加载
    var firstLoad by remember(folder) { mutableStateOf(msgListCache[folder] == null) }
    val scope = rememberCoroutineScope()
    suspend fun loadList(): List<MailMessage> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val rows = when {
            folder == "all" -> db.messages().allMail()
            folder == "unread" -> db.messages().unread()
            folder == "starred" -> db.messages().starred()
            folder == "sent" -> db.messages().sent()
            folder == "trash" -> db.messages().trash()
            folder.startsWith("account:") -> db.messages().byAccount(folder.substringAfter(":").toLong())
            folder.startsWith("label:") -> db.mailLabels().messagesForLabel(folder.substringAfter(":").toLong())
            else -> db.messages().allMail()
        }
        // 同一封邮件只显示一条(按 Message-ID 合并)：
        // - 给自己发邮件时，服务器同时存在「已发送(SENT)」和「收件箱(INBOX)」两份副本，列表出现两条一模一样的；
        // - 推送+同步竞态偶尔也会往 INBOX 插入两条同一封。
        // 合并规则：保留先出现的一条；若已保留的是 SENT 而当前是 INBOX，用 INBOX 那条替换(收件箱视角更对)
        val seen = mutableMapOf<String, Int>() // key -> dedup 中的下标
        val dedup = mutableListOf<MailMessage>()
        for (m in rows) {
            val key = m.messageId.ifBlank { "k:${m.accountId}|${m.folder}|${m.uid}" }
            val prev = seen[key]
            if (prev == null) {
                seen[key] = dedup.size
                dedup.add(m)
            } else if (dedup[prev].folder == "SENT" && m.folder == "INBOX") {
                dedup[prev] = m
            }
        }
        when (sortMode) {
            1 -> dedup.sortedBy { it.date }
            2 -> dedup.sortedByDescending { it.size }
            3 -> dedup.sortedBy { it.size }
            else -> dedup.sortedByDescending { it.date }
        }.let { base ->
            // 置顶邮件永远在最上面；多封置顶按置顶时间倒序依次排列(后置顶的更靠上)
            base.sortedWith(compareByDescending<MailMessage> { it.pinnedAt > 0 }.thenByDescending { it.pinnedAt })
        }
    }
    // 关键修复：加载数据和回顶放在同一个协程里顺序执行。
    // reloadKey(后台同步/操作后刷新)不回顶，避免正在浏览时被强行拉回顶部；
    // 换文件夹/点排序/新邮件到达才回顶
    fun applyList(n: List<MailMessage>) { msgListCache[folder] = n; msgs = n; firstLoad = false }
    // 从详情页返回的时刻：返回后 1.5 秒内轮询发现的数据变化只写缓存不立即赋值，
    // 避免刚返回的第一次重组与用户的第一下点击/滑动撞车(表现为“第一下没反应，第二下才行”)
    var lastResumeAt by remember { mutableStateOf(0L) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 加固：首次加载失败(数据库偶发忙碌/异常)时自动重试最多 3 次；
    // 且「全部邮件」查出来是空的但数据库其实有邮件时也重试，
    // 杜绝“打开 APP 全部邮件一直显示暂无邮件、切到别的文件夹再回来才恢复”的问题
    LaunchedEffect(folder, reloadKey) {
        for (attempt in 1..3) {
            val n = runCatching { loadList() }.getOrNull()
            if (n != null) {
                applyList(n)
                // 「全部邮件」查出来是空的但数据库其实有邮件：等一下重试，防止偶发查询异常
                val needRetry = folder == "all" && n.isEmpty() &&
                    runCatching { db.messages().countAll() > 0 }.getOrDefault(false)
                if (!needRetry) break
            }
            kotlinx.coroutines.delay(500)
        }
    }
    LaunchedEffect(folder, sortMode, sortNonce, scrollNonce) {
        runCatching { loadList() }.getOrNull()?.let { applyList(it) }
        // 等一帧让新列表先上屏再回顶：之前 scrollToItem 和新数据重组赛跑，
        // 偶发“点了排序但没回到顶部”；再补一次延迟回顶双保险
        androidx.compose.runtime.withFrameNanos { }
        runCatching { listState.scrollToItem(0) }
        kotlinx.coroutines.delay(100)
        runCatching { listState.scrollToItem(0) }
    }
    // 实时轮询数据库(每 0.8 秒，IO 线程，纯本地查询开销极小)：同步入库/已读状态变化后，
    // 当前文件夹(包括正在浏览的“全部邮件”)自动更新，不再出现“未读文件夹有邮件、全部文件夹还空着”的错位；
    // 数据没变化时不赋值，不触发多余重组
    LaunchedEffect(folder) {
        while (true) {
            kotlinx.coroutines.delay(800)
            runCatching {
                val n = loadList()
                if (n != msgs) {
                    if (System.currentTimeMillis() - lastResumeAt < 1500) {
                        // 刚从详情页返回：先只更新缓存，下一轮再赋值，不与返回后的首次交互抢主线程
                        msgListCache[folder] = n
                    } else applyList(n)
                }
            }
        }
    }
    // 供删除/标已读等操作后局部刷新列表(保持当前浏览位置，不强行回顶)
    val reload = { scope.launch { runCatching { loadList() }.getOrNull()?.let { applyList(it) } }; Unit }
    // 从详情页返回(ON_RESUME)的时刻：记录下来供轮询防抖用，返回后 1.5 秒内不立即重建列表，
    // 避免“返回后第一下点击/滑动没反应”
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(life, folder) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                lastResumeAt = System.currentTimeMillis()
            }
        }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }

    // 联系人备注整表一次查询(不再每行各查一次库)：返回列表重建时多行同时发查询会导致
    // 多次重组集中爆发，正是“返回后第一下滑动没反应”的推手之一
    var contactMap by remember { mutableStateOf<Map<String, Contact>>(emptyMap()) }
    LaunchedEffect(Unit) {
        runCatching {
            contactMap = kotlinx.coroutines.withContext(Dispatchers.IO) {
                db.contacts().all().associateBy { it.email }
            }
        }
    }

    // 时间文本随“刚刚/N分钟前”自动更新(每 30 秒重算一次当前时间)
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30000); nowTick = System.currentTimeMillis() } }

    // 多选模式
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var showClearTrash by remember { mutableStateOf(false) }
    fun exitSelection() { selectionMode = false; selected = emptySet() }
    // 取消勾选：若全部取消(一个都没选)，自动退出多选回到正常浏览状态
    fun toggleSelect(id: Long, add: Boolean) {
        val s = if (add) selected + id else selected - id
        selected = s
        if (!add && s.isEmpty()) selectionMode = false
    }

    // 系统返回键：多选模式下先退出多选，而不是退出 APP
    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelection() }

    // 点击防抖：连续快速点两下/两行时只在第一次导航，避免重复压栈详情页；
    // 也不会因连点而吞掉后续点击
    var lastNavClick by remember { mutableStateOf(0L) }

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
                // 首次登录正在从服务器拉取邮件(或首次加载本地库)时：明确显示“正在同步”，
                // 同步期间连续显示不闪烁；同步完成/超时后如实显示空状态
                if (firstLoad || syncingEmpty) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp))
                        Text("正在同步邮件，请稍候…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(when {
                        folder == "trash" -> "回收站为空"
                        folder.startsWith("label:") -> "该标签还没有邮件：打开任意邮件点「标签」即可加入"
                        else -> "暂无邮件"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    // 标签文件夹里：多选后可把这些邮件移出该标签(邮件本身不删)
                    if (folder.startsWith("label:")) {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val lid = folder.substringAfter(":").toLong()
                                selected.forEach { db.mailLabels().remove(lid, it) }
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    exitSelection(); refreshParent(); reload()
                                }
                            }
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("移出标签", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { exitSelection() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("取消", color = Color.White, fontSize = 12.sp) }
                }
            }
            LazyColumn(state = listState) {
                items(msgs, key = { it.id }) { m ->
                    val shownName = contactMap[m.fromAddr]?.remark?.takeIf { it.isNotBlank() } ?: m.fromName.ifBlank { m.fromAddr }
                    val isSel = m.id in selected
                    Row(Modifier.fillMaxWidth().background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                        if (selectionMode) {
                            Checkbox(checked = isSel, onCheckedChange = { toggleSelect(m.id, it) })
                        }
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            toggleSelect(m.id, !isSel)
                                        } else {
                                            val now = System.currentTimeMillis()
                                            if (now - lastNavClick < 700) return@combinedClickable
                                            lastNavClick = now
                                            // 点开即立刻置为已读：先更新本行(蓝点瞬间消失)并同步落库，
                                            // 再进详情；即使马上返回也不会因协程被取消而漏写已读
                                            if (!m.isRead) {
                                                msgs = msgs.map { if (it.id == m.id) it.copy(isRead = true) else it }
                                                // 异步落库已读(之前 runBlocking 同步写库卡主线程，导致列表滑动/点击偶发卡顿)
                                                scope.launch { runCatching { db.messages().setRead(m.id, true) } }
                                            }
                                            nav.navigate("detail/${m.id}")
                                        }
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
                                        Text(fmtTime(m.date, nowTick), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(m.subject.ifBlank { "(无主题)" }, fontSize = 14.sp,
                                        fontWeight = if (!m.isRead) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(m.snippet, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        // 置顶图钉：一眼看出哪些邮件被置顶了
                                        if (m.pinnedAt > 0) Icon(Icons.Default.PushPin, "已置顶", Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        if (m.isAd) Text("广告", fontSize = 10.sp, color = Color.White,
                                            modifier = Modifier.background(Color(0xFFEF6C00), MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 1.dp))
                                        // 退信邮件红色徽标：一眼看出这封信是系统退信(发送未真正送达)，不是普通新邮件
                                        if (isBounceMail(m.fromAddr, m.subject)) Text("退信", fontSize = 10.sp, color = Color.White,
                                            modifier = Modifier.background(Color(0xFFD32F2F), MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 1.dp))
                                        if (m.hasAttachments) Icon(Icons.Default.AttachFile, null, Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (m.isStarred) Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = Color(0xFFFBC02D))
                                        if (m.size > 0) Text(fmtSize(m.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (m.folder == "SENT") Text("已发送至 ${m.toAddr}", fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    // 已发送的邮件：一键再次编辑(原样带入收件人/主题/正文，改完再发)
                                    if (m.folder == "SENT" && !selectionMode) {
                                        Text("✎ 再次编辑", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 2.dp).clickable {
                                                val now2 = System.currentTimeMillis()
                                                if (now2 - lastNavClick > 700) {
                                                    lastNavClick = now2
                                                    nav.navigate("reedit/${m.id}")
                                                }
                                            })
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
        // 一键回到顶部：列表不在最顶部就显示(不管是因为滚动还是刚来了新邮件)；
        // 点一下平滑滚回顶部。第 0 项永远是最新邮件(默认最新优先排序)，
        // 回顶时即使刚来了新邮件，也回到的是新邮件所在的新顶部
        if (listState.canScrollBackward) {
            SmallFloatingActionButton(
                onClick = { scope.launch { runCatching { listState.animateScrollToItem(0) } } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 96.dp)
            ) { Icon(Icons.Default.KeyboardArrowUp, "回到顶部") }
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
    // 实时轮询(每 1 秒)：发送成功(SendWorker 改库)后页面立即把“正在发送”变成真实状态，
    // 不再需要返回重进才能看到最新状态
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            runCatching { val n = kotlinx.coroutines.withContext(Dispatchers.IO) { db.outbox().all() }; if (n != items) items = n }
        }
    }
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
                            o.status == "SENDING" -> {
                                Text("正在发送", fontSize = 10.sp, color = Color.White,
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
