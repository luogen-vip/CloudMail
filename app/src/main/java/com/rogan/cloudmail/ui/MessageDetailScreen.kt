package com.rogan.cloudmail.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.core.content.FileProvider
import com.rogan.cloudmail.data.*
import com.rogan.cloudmail.mail.MailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageDetailScreen(nav: NavHostController, id: Long) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<MailMessage?>(null) }
    var acc by remember { mutableStateOf<Account?>(null) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRecall by remember { mutableStateOf(false) }
    var showImg by remember { mutableStateOf<File?>(null) }
    var starBusy by remember { mutableStateOf(false) }
    var showRemark by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var contact by remember { mutableStateOf<Contact?>(null) }
    var m0 by remember { mutableStateOf<MailMessage?>(null) }
    var recallResult by remember { mutableStateOf<String?>(null) }
    var loadKey by remember { mutableStateOf(0) } // 失败重试：+1 后重新拉取正文
    var showShare by remember { mutableStateOf(false) }
    var showRemind by remember { mutableStateOf(false) }
    var busyShare by remember { mutableStateOf(false) }
    var fileExported by remember { mutableStateOf<Pair<File, String>?>(null) }
    // 正文滚动位置：点图片预览/内置浏览器返回后恢复到之前的位置，不回顶
    var bodyScrollY by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    // 提示条用 Scaffold 标准 SnackbarHost：之前直接放内容层顶部被顶栏遮住看不见
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); toast = null }
    }

    LaunchedEffect(id, loadKey) {
        val m = db.messages().byId(id)
        m0 = m
        if (m == null) { loading = false; return@LaunchedEffect }
        msg = m
        acc = db.accounts().byId(m.accountId)
        contact = db.contacts().byEmail(m.fromAddr)
        if (!m.isRead) { db.messages().setRead(id, true) }
        if (!m.loaded && acc != null) {
            loading = true
            val loaded = withContext(Dispatchers.IO) { MailService.loadFull(ctx, acc!!, m, db) }
            msg = loaded
        }
        loading = false
    }

    val m = msg
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                // 标题位换成 置顶+分享 两个图标(与返回箭头留足间距不误触)：
                // 之前置顶文字按钮紧贴返回箭头，点返回常误触置顶；␣⋮ 折叠菜单里的置顶/分享也提到明面上，
                // 不用再展开菜单；标题「邮件详情」从正文头部已能看出，这里不再重复占位
                title = {
                    if (m != null) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 14.dp)) {
                            IconButton(onClick = {
                                scope.launch {
                                    val nv = if (m.pinnedAt > 0) 0L else System.currentTimeMillis()
                                    db.messages().setPinned(m.id, nv)
                                    msg = m.copy(pinnedAt = nv)
                                    m0 = m0?.copy(pinnedAt = nv)
                                    toast = if (nv > 0) "已置顶，会显示在列表最上面" else "已取消置顶"
                                }
                            }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.PushPin, if (m.pinnedAt > 0) "取消置顶" else "置顶",
                                    tint = if (m.pinnedAt > 0) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = if (m.pinnedAt > 0) Modifier else Modifier.rotate(45f))
                            }
                            IconButton(onClick = { showShare = true }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Share, "分享/导出")
                            }
                        }
                    } else {
                        Text("邮件详情", fontSize = 17.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    if (m != null) {
                        IconButton(onClick = {
                            if (starBusy) return@IconButton
                            starBusy = true
                            val nv = !m.isStarred
                            scope.launch {
                                db.messages().setStar(m.id, nv)
                                msg = m.copy(isStarred = nv)
                                m0 = m0?.copy(isStarred = nv)
                                starBusy = false
                            }
                        }) { Icon(if (m.isStarred) Icons.Default.Star else Icons.Default.StarBorder, "星标",
                            tint = if (m.isStarred) Color(0xFFFBC02D) else MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = {
                            scope.launch {
                                val to = if (m.folder == "SENT") m.toAddr.substringBefore(",").trim() else m.fromAddr
                                nav.navigate("composeTo/$to")
                            }
                        }) { Icon(Icons.Default.Reply, "回复") }
                        if (m.folder != "SENT") {
                            IconButton(onClick = { nav.navigate("forward/${m.id}") }) { Icon(Icons.Default.Shortcut, "转发") }
                        }
                        if (m.folder == "SENT") {
                            // 已发送邮件：置顶/分享已提到标题位(不与返回箭头紧挨)，⋮ 只留 再次编辑/撤回/转发
                            var sentMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { sentMenu = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                            androidx.compose.material3.DropdownMenu(expanded = sentMenu,
                                onDismissRequest = { sentMenu = false }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text("✎ 再次编辑") },
                                    onClick = { sentMenu = false; nav.navigate("reedit/${m.id}") })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("↩ 撤回") },
                                    onClick = { sentMenu = false; confirmRecall = true })
                                androidx.compose.material3.DropdownMenuItem(text = { Text("➦ 转发") },
                                    onClick = { sentMenu = false; nav.navigate("forward/${m.id}") })
                            }
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") }
                    }
                }
            )
        }
    ) { pad ->
        if (m == null) {
            // 加载占位：之前这里完全空白，从列表点进来那一瞬间像“点了没反应”；
            // 现在立即显示转圈，点击反馈明确
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 头部信息(紧凑)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.subject.ifBlank { "(无主题)" }, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (m.size > 0) Text(fmtSize(m.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarOf(displayName(contact?.remark, m.fromAddr, m.fromName), null, 34.dp)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(displayName(contact?.remark, m.fromAddr, m.fromName) + " <" + m.fromAddr + ">",
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${if (m.folder == "SENT") "发送至 " else ""}${m.toAddr}  ${fmtFull(m.date)}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // 功能按钮改成自动换行的 FlowRow(单独一行)：之前全挤在发件人那一行，
                // 发件人名字一长「稍后提醒」就被挤出屏幕外看不见(是布局裁掉了，功能还在)；
                // 现在一行放不下会自动折到下一行，备注/加联系人/标签/往来邮件/稍后提醒全部可见
                FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    // 按钮统一压缩高度(28dp)：之前默认高度太高，第二行按钮把邮件正文滑动区压矮
                    if (m.folder == "INBOX") {
                        TextButton(onClick = { showRemark = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)) {
                            Text(if (contact?.remark.isNullOrBlank()) "备注" else "改备注", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val c = db.contacts().byEmail(m.fromAddr)
                                if (c != null) nav.navigate("contactEdit/${c.id}")
                                else {
                                    val newId = db.contacts().insert(Contact(
                                        email = m.fromAddr, remark = m.fromName, phone = "", org = "", note = "",
                                        isImportant = false, lastReceivedAt = m.date, createdAt = System.currentTimeMillis()))
                                    toast = "已添加联系人"
                                    nav.navigate("contactEdit/$newId")
                                }
                            }
                        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)) { Text("加联系人", fontSize = 11.sp) }
                    }
                    // 标签入口：所有文件夹的邮件都可以打标签(可多选；没有标签时可直接新建并自动加入)
                    TextButton(onClick = { showLabels = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)) {
                        Text("标签", fontSize = 11.sp)
                    }
                    // 往来邮件：一键查看和这个地址的全部往来(我发的+TA发的)
                    TextButton(onClick = {
                        val addr = if (m.folder == "SENT") m.toAddr.substringBefore(",").trim() else m.fromAddr
                        if (addr.isNotBlank()) nav.navigate("conv/" + android.net.Uri.encode(addr))
                    }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)) {
                        Text("往来邮件", fontSize = 11.sp)
                    }
                    // 稍后提醒：到点后系统通知再把这封邮件提醒一遍(点击直达本邮件)
                    TextButton(onClick = { showRemind = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)) {
                        Text("稍后提醒", fontSize = 11.sp)
                    }
                }
                // 收件人/抄送：默认折叠成一行，不挤占头部；点“展开”看完整收件人/抄送详情，
                // 保证下方正文浏览区始终足够大
                var headOpen by remember(m.id) { mutableStateOf(false) }
                // 已发送的邮件同样显示收件人行：之前排除了 SENT，自己发给自己时只看到抄送看不到收件人
                val hasToLine = m.toAddr.isNotBlank()
                val hasCcLine = m.ccAddr.isNotBlank()
                if (hasToLine || hasCcLine) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (headOpen) {
                                if (hasToLine) Text("收件人：${m.toAddr}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (hasCcLine) Text("抄送：${m.ccAddr}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(if (hasToLine) "收件人：${m.toAddr}" + (if (hasCcLine) " 等" else "") else "抄送：${m.ccAddr}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { headOpen = !headOpen }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)) {
                            Text(if (headOpen) "收起 ▴" else "展开 ▾", fontSize = 11.sp)
                        }
                    }
                }
                if (m.isAd) Text("此邮件疑似广告邮件", fontSize = 11.sp, color = Color(0xFFEF6C00),
                    modifier = Modifier.padding(top = 2.dp))
                // 退信提示(判断逻辑与列表/通知共用同一个函数，保证三处一致)；
                // 并解析退信正文给出具体失败原因(域名不存在/地址不存在/已满/被拒收…)
                if (isBounceMail(m.fromAddr, m.subject)) {
                    // 紧凑横幅：默认只占 2 行，点一下展开全部原因；不把正文滑动区挤得只剩一小块
                    var bounceOpen by remember(m.id) { mutableStateOf(false) }
                    val reason = remember(m.id, m.bodyHtml) {
                        bounceReason(m.snippet + " " + m.bodyHtml.take(8000))
                    }
                    val fa = remember(m.id, m.bodyHtml) {
                        bounceFailedAddr(m.snippet + " " + m.bodyHtml.take(8000))
                    }
                    Text(buildString {
                        append("⚠ 退信：邮件未真正送达。原因：$reason")
                        if (fa != null) append("｜失败地址：$fa")
                    }, fontSize = 12.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold,
                        maxLines = if (bounceOpen) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { bounceOpen = !bounceOpen }
                            .padding(top = 4.dp))
                    Text(if (bounceOpen) "收起 ▴(下方正文是服务器退信原文)" else "展开详情 ▾",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { bounceOpen = !bounceOpen }.padding(top = 1.dp))
                }
            }
            HorizontalDivider()
            // 加载中不显示“无正文内容”(避免误以为邮件没内容)：正文拉取完成前显示加载状态，
            // 只有确实加载完且正文为空才显示“无正文内容”
            val html = remember(m.id, m.bodyHtml, m.loaded) { htmlForWebView(m) }
            // WebView 常驻不拆装：之前“加载中/失败/正文”三个分支互切会整体销毁重建视图，
            // 只用透明度和覆盖层控制显示，消除闪白
            Box(Modifier.fillMaxWidth().weight(1f)) {
                androidx.compose.runtime.key(loadKey) {
                androidx.compose.ui.viewinterop.AndroidView(
                factory = { viewCtx ->
                    android.webkit.WebView(viewCtx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean {
                                when {
                                    // 邮件里的链接默认用内置浏览器打开：不跳出 APP，返回键即回到邮件正文
                                    url.startsWith("http://") || url.startsWith("https://") -> {
                                        nav.navigate("browser/" + android.net.Uri.encode(url))
                                        return true
                                    }
                                    // mailto:/tel: 等特殊协议交给系统处理
                                    else -> {
                                        runCatching { viewCtx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                        return true
                                    }
                                }
                            }
                            // 渲染进程崩溃时接管：不让系统杀掉整个 APP，恢复后位置不丢
                            override fun onRenderProcessGone(view: android.webkit.WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                runCatching {
                                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                                    view.destroy()
                                }
                                loadKey++
                                return true
                            }
                        }
                        // 点击正文中的图片 → 全屏预览/保存。
                        // 必须用 GestureDetector 的 onSingleTapConfirmed：之前直接监听 ACTION_UP，
                        // 滑动浏览正文抬手时只要手指落在图片上就会误弹预览，严重影响浏览
                        val gd = android.view.GestureDetector(viewCtx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                                val hr = hitTestResult
                                if (hr.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
                                    hr.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                                    val src = hr.extra ?: return false
                                    val f = File(src.removePrefix("file://").substringBefore("?"))
                                    if (f.exists()) { showImg = f; return true }
                                }
                                return false
                            }
                        })
                        setOnTouchListener { _, ev ->
                            gd.onTouchEvent(ev)
                            false
                        }
                        // 实时记录正文滚动位置：预览/浏览器返回后恢复到原位置
                        setOnScrollChangeListener { v, _, _, _, _ ->
                            bodyScrollY = v.scrollY
                        }
                    }
                },
                update = { wv ->
                    // 只在正文真正变化时才重载：之前弹窗开关等任何重组都会 loadDataWithBaseURL 导致回到顶部
                    if (wv.tag != html) {
                        wv.tag = html
                        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
                        // 页面重建后恢复之前的阅读位置
                        wv.postDelayed({ runCatching { wv.scrollTo(0, bodyScrollY) } }, 250)
                    }
                    wv.alpha = if (loading || !m.loaded) 0f else 1f
                },
                modifier = Modifier.fillMaxSize())
                }
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("正在加载邮件内容…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (!m.loaded) {
                    // 拉取失败(网络抖动/连接被服务器断开)：给出明确状态和一键重试，不再无限转圈
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("正文加载失败，可能是网络波动", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { loading = true; loadKey++ }) { Text("重新加载") }
                        }
                    }
                }
            }
            // 附件区：默认折叠成一行，不再挤占正文浏览区(附件多时正文区域明显变小)；
            // 点一下展开完整列表，再点收起
            val atts = remember(m.attachmentsJson) { AttInfo.fromJson(m.attachmentsJson) }
            if (atts.isNotEmpty()) {
                HorizontalDivider()
                var attOpen by remember(m.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { attOpen = !attOpen }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("附件 (${atts.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (attOpen) "收起 ▴" else "展开 ▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                if (attOpen) {
                    Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp)) {
                        atts.forEach { a ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when {
                                        a.mime.startsWith("image/") -> Icons.Default.Image
                                        a.mime.startsWith("video/") -> Icons.Default.PlayCircle
                                        a.mime.startsWith("audio/") -> Icons.Default.Headphones
                                        else -> Icons.Default.InsertDriveFile
                                    }, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(a.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sz = File(a.path).takeIf { it.exists() }?.length()
                                    Text("${sz?.let { fmtSize(it) } ?: "未知大小"} · ${a.mime.substringAfter('/', "file")}" +
                                            if (a.dur > 0) " · 时长 ${a.dur / 60}分${a.dur % 60}秒" else "",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { openWithProvider(ctx, File(a.path), a.mime, a.name) }) { Text("打开", fontSize = 12.sp) }
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val saved = ShareUtils.saveToDownloads(ctx, File(a.path), a.mime, a.name)
                                        withContext(Dispatchers.Main) { toast = saved }
                                    }
                                }) { Text("保存", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
        // 大图查看
        showImg?.let { img ->
            AlertDialog(onDismissRequest = { showImg = null },
                title = { Text(img.name, fontSize = 15.sp) },
                text = {
                    coil.compose.AsyncImage(model = img, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            val saved = ShareUtils.saveToDownloads(ctx, File(img.path), "", img.name)
                            withContext(Dispatchers.Main) { toast = saved }
                        }
                    }) { Text("保存到相册/下载") }
                },
                dismissButton = { TextButton(onClick = { showImg = null }) { Text("关闭") } })
        }
        // 删除确认
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除邮件") },
                text = {
                    Text(if (m.folder == "TRASH") "将彻底删除这封邮件，不可恢复。"
                         else "删除后将移入「已删除」，可稍后彻底删除。")
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            if (m.folder == "TRASH") {
                                db.messages().deleteForever(m.id)
                            } else {
                                db.messages().setFolder(m.id, "TRASH")
                            }
                            nav.popBackStack()
                        }
                    }) { Text(if (m.folder == "TRASH") "彻底删除" else "删除") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            if (m.folder == "TRASH") {
                                db.messages().setFolder(m.id, "INBOX")
                                toast = "已恢复到收件箱"
                            }
                        }
                    }) { Text(if (m.folder == "TRASH") "恢复邮件" else "取消") }
                }
            )
        }
        // 撤回确认
        if (confirmRecall && acc != null) {
            AlertDialog(
                onDismissRequest = { confirmRecall = false },
                title = { Text("撤回邮件") },
                text = { Text("将从您的服务器已发送中删除该邮件，并向收件人发送撤回通知。\n\n注意：跨服务商邮件受邮件协议限制，无法从对方服务器物理移除；同一服务商(如QQ→QQ)效果最佳。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRecall = false
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    MailService.recall(acc!!, m.messageId, m.toAddr.substringBefore(",").trim(), m.subject)
                                }.getOrElse { false to "撤回失败：${it.message ?: "网络或服务器错误"}" }
                            }
                            recallResult = (if (r.first) "撤回成功！" else "撤回未完全成功。") + "\n" + r.second
                            if (r.first || r.second.contains("已从")) {
                                db.messages().setFolder(m.id, "TRASH")
                            }
                        }
                    }) { Text("确认撤回") }
                },
                dismissButton = { TextButton(onClick = { confirmRecall = false }) { Text("取消") } }
            )
        }
        // 撤回结果醒目弹窗
        recallResult?.let { r ->
            AlertDialog(onDismissRequest = { recallResult = null },
                title = { Text(if (r.startsWith("撤回成功")) "✓ 撤回成功" else "撤回结果") },
                text = { Text(r, fontSize = 14.sp) },
                confirmButton = { TextButton(onClick = { recallResult = null }) { Text("知道了") } })
        }
        // 分享/导出面板：系统分享、复制摘要、导出 EML/HTML/长图(全部本地完成，不需要服务器)
        if (showShare) {
            AlertDialog(onDismissRequest = { if (!busyShare) showShare = false },
                title = { Text("分享 / 导出") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("「分享」会弹出微信/QQ 等面板，直接把邮件内容发过去；导出的文件也可在分享面板里选应用发送。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { ShareUtils.shareText(ctx, m); showShare = false }) {
                            Icon(Icons.Default.Share, null, Modifier.size(16.dp)); Text(" 系统分享(微信/QQ/短信…)") }
                        TextButton(onClick = {
                            ShareUtils.copyToClipboard(ctx, ShareUtils.summaryText(m))
                            toast = "已复制：主题+发件人+正文，可直接粘贴"; showShare = false
                        }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Text(" 复制邮件摘要/全部内容") }
                        HorizontalDivider()
                        TextButton(onClick = {
                            showShare = false
                            scope.launch(Dispatchers.IO) {
                                val f = ShareUtils.exportEml(ctx, m, acc)
                                withContext(Dispatchers.Main) { fileExported = f to "application/octet-stream" }
                            }
                        }) { Icon(Icons.Default.Email, null, Modifier.size(16.dp)); Text(" 导出为 EML 邮件文件") }
                        TextButton(onClick = {
                            showShare = false
                            scope.launch(Dispatchers.IO) {
                                val f = ShareUtils.exportHtml(ctx, m)
                                withContext(Dispatchers.Main) { fileExported = f to "text/html" }
                            }
                        }) { Icon(Icons.Default.Code, null, Modifier.size(16.dp)); Text(" 导出为 HTML 网页文件") }
                        TextButton(onClick = {
                            showShare = false; busyShare = true
                            toast = "正在生成长图…"
                            scope.launch(Dispatchers.IO) {
                                val f = ShareUtils.exportLongImage(ctx, m)
                                withContext(Dispatchers.Main) {
                                    busyShare = false
                                    if (f != null) fileExported = f to "image/png"
                                    else toast = "长图生成失败，请重试"
                                }
                            }
                        }) { Icon(Icons.Default.Image, null, Modifier.size(16.dp)); Text(" 导出为长图(清晰完整)") }
                    }
                },
                confirmButton = { TextButton(onClick = { showShare = false }) { Text("关闭") } })
        }
        // 导出文件完成后的选择：分享给微信/QQ 等、用应用打开、或直接保存到手机「下载」文件夹
        fileExported?.let { (f, mime) ->
            AlertDialog(onDismissRequest = { fileExported = null },
                title = { Text("已导出：${f.name}") },
                text = { Text("可以把文件分享给微信/QQ 等应用，也可以直接保存到手机的「下载」文件夹(文件管理器里能找到)，或用其他应用打开查看。") },
                confirmButton = { TextButton(onClick = { ShareUtils.shareFile(ctx, f, mime); fileExported = null }) { Text("分享给其他应用") } },
                dismissButton = { Row {
                    TextButton(onClick = {
                        toast = ShareUtils.saveToDownloads(ctx, f, mime); fileExported = null
                    }) { Text("保存到手机") }
                    if (mime == "text/html") TextButton(onClick = { ShareUtils.openFile(ctx, f, mime); fileExported = null }) { Text("用浏览器打开") }
                } })
        }
        // 稍后提醒：选一个时间，到点系统通知把这封邮件再提醒一遍(点击直达邮件)。
        // 快捷 10/30 分钟 + 自定义年月日时分(想提多久都行)；已有提醒可一键取消后再重新设
        if (showRemind) {
            val cal0 = java.util.Calendar.getInstance()
            var ry by remember { mutableStateOf(cal0.get(java.util.Calendar.YEAR)) }
            var rmo by remember { mutableStateOf(cal0.get(java.util.Calendar.MONTH) + 1) }
            var rdy by remember { mutableStateOf(cal0.get(java.util.Calendar.DAY_OF_MONTH)) }
            var rh by remember { mutableStateOf(cal0.get(java.util.Calendar.HOUR_OF_DAY)) }
            var rmi by remember { mutableStateOf(cal0.get(java.util.Calendar.MINUTE)) }
            // 查询这封邮件是否已有进行中的提醒(WorkManager 唯一任务 reminder_<id>)
            var hasReminder by remember(m.id) { mutableStateOf(false) }
            LaunchedEffect(m.id) {
                runCatching {
                    val wis = androidx.work.WorkManager.getInstance(ctx)
                        .getWorkInfosForUniqueWork("reminder_${m.id}").get()
                    hasReminder = wis.any { !it.state.isFinished }
                }
            }
            val target: Long = run {
                val c = java.util.Calendar.getInstance()
                c.set(ry, rmo - 1, rdy, rh, rmi, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                c.timeInMillis
            }
            val maxD = when (rmo) { 1, 3, 5, 7, 8, 10, 12 -> 31; 4, 6, 9, 11 -> 30; else -> 29 }
            val dateBad = rdy > maxD
            // 错误提示显示在弹窗内部：之前用 Snackbar，被弹窗盖住看不见，用户以为点了没反应
            var remindErr by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(ry, rmo, rdy, rh, rmi) { remindErr = null }
            AlertDialog(onDismissRequest = { showRemind = false },
                title = { Text("稍后提醒") },
                text = {
                    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        Text("到点后 APP 会发一条提醒通知，点开就是这封邮件(即使 APP 已关闭后台也生效)。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        if (hasReminder) {
                            Text("⏰ 这封邮件已设置了稍后提醒(进行中)。可在下面重新设定新时间覆盖，或点「取消提醒」关掉。",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        } else {
                            Text("当前没有设置提醒。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        // 关闭提醒做成醒目大按钮：之前只有个不起眼的「取消提醒」小字按钮，很多用户找不到关闭入口；
                        // 关闭后到点不再提醒，随时可以重新打开/重新设时间
                        if (hasReminder) {
                            Button(onClick = {
                                com.rogan.cloudmail.mail.ReminderWorker.cancel(ctx, m.id)
                                hasReminder = false
                                toast = "已关闭这封邮件的提醒，到点不再通知；可随时重新设置"
                            }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFF3E0), contentColor = Color(0xFFEF6C00)),
                                modifier = Modifier.fillMaxWidth()) { Text("✕ 关闭提醒", fontSize = 13.sp) }
                            Spacer(Modifier.height(6.dp))
                        }
                        // 快捷选项一屏排开(不再需要往下滑才看到确认按钮)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("10分钟" to 10L, "30分钟" to 30L).forEach { (name, min) ->
                                OutlinedButton(onClick = {
                                    com.rogan.cloudmail.mail.ReminderWorker.schedule(ctx, m.id, System.currentTimeMillis() + min * 60_000)
                                    hasReminder = true
                                    toast = "已设置：${min}分钟后提醒这封邮件"
                                    showRemind = false
                                }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text(name, fontSize = 12.sp) }
                            }
                            OutlinedButton(onClick = {
                                com.rogan.cloudmail.mail.ReminderWorker.schedule(ctx, m.id, System.currentTimeMillis() + 60 * 60_000)
                                hasReminder = true
                                toast = "已设置：1小时后提醒这封邮件"
                                showRemind = false
                            }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("1小时", fontSize = 12.sp) }
                            if (hasReminder) OutlinedButton(onClick = {
                                com.rogan.cloudmail.mail.ReminderWorker.cancel(ctx, m.id)
                                hasReminder = false
                                toast = "已取消这封邮件的稍后提醒，之后可重新设定"
                            }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                Text("取消提醒", fontSize = 12.sp, color = Color(0xFFEF6C00)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        Text("自定义提醒时间(想提多久都可以，含年月日)：", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        com.rogan.cloudmail.ui.Stepper("年份", ry, cal0.get(java.util.Calendar.YEAR), cal0.get(java.util.Calendar.YEAR) + 5) { ry = it }
                        com.rogan.cloudmail.ui.Stepper("月份", rmo, 1, 12) { rmo = it }
                        com.rogan.cloudmail.ui.Stepper("日期", rdy, 1, 31) { rdy = it }
                        if (dateBad) Text("$rmo 月没有 $rdy 日，请修改", fontSize = 12.sp, color = Color(0xFFEF6C00))
                        com.rogan.cloudmail.ui.Stepper("小时", rh, 0, 23) { rh = it }
                        com.rogan.cloudmail.ui.Stepper("分钟", rmi, 0, 59) { rmi = it }
                        Text("将提醒：${fmtFull(target)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        remindErr?.let {
                            Spacer(Modifier.height(6.dp))
                            Text("⚠ $it", fontSize = 13.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                        }
                    }
                },
                // 确认按钮固定在弹窗底栏(不再藏在滚动区最下面)：打开弹窗一眼就能看到「按这个时间提醒」
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            dateBad -> remindErr = "${rmo} 月没有 ${rdy} 日，请修改日期"
                            target <= System.currentTimeMillis() -> remindErr = "所选时间早于现在(${fmtFull(System.currentTimeMillis())})，请把时间往后调"
                            else -> {
                                com.rogan.cloudmail.mail.ReminderWorker.schedule(ctx, m.id, target)
                                hasReminder = true
                                showRemind = false
                                toast = "已设置：${fmtFull(target)} 提醒这封邮件"
                            }
                        }
                    }) { Text("按这个时间提醒") }
                },
                dismissButton = { TextButton(onClick = { showRemind = false }) { Text("关闭") } })
        }
        // 标签对话框：勾选/取消该邮件所属标签；还没有标签时可直接新建，邮件自动加入
        if (showLabels) {
            var labelIds by remember(showLabels) { mutableStateOf<Set<Long>>(emptySet()) }
            var allLabels by remember(showLabels) { mutableStateOf<List<Label>>(emptyList()) }
            var newName by remember { mutableStateOf("") }
            LaunchedEffect(showLabels) {
                val (ls, ids) = withContext(Dispatchers.IO) {
                    db.labels().all() to db.mailLabels().labelIdsOf(m.id).toSet()
                }
                allLabels = ls; labelIds = ids
            }
            AlertDialog(onDismissRequest = { showLabels = false },
                title = { Text("移入标签") },
                text = {
                    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        if (allLabels.isEmpty()) {
                            Text("还没有标签。在下面直接新建一个，这封邮件会自动加入。",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        allLabels.forEach { lb ->
                            // 整行可点：点行=切换勾选(与勾选框等效)，操作方便
                            Row(Modifier.fillMaxWidth().clickable {
                                val add = lb.id !in labelIds
                                scope.launch(Dispatchers.IO) {
                                    if (add) db.mailLabels().insert(MailLabel(lb.id, m.id))
                                    else db.mailLabels().remove(lb.id, m.id)
                                }
                                labelIds = if (add) labelIds + lb.id else labelIds - lb.id
                            }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = lb.id in labelIds,
                                    onCheckedChange = { add ->
                                        scope.launch(Dispatchers.IO) {
                                            if (add) db.mailLabels().insert(MailLabel(lb.id, m.id))
                                            else db.mailLabels().remove(lb.id, m.id)
                                        }
                                        labelIds = if (add) labelIds + lb.id else labelIds - lb.id
                                    })
                                Text(lb.name, fontSize = 14.sp)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(value = newName, onValueChange = { newName = it },
                                placeholder = { Text("新建标签名") }, singleLine = true,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val n = newName.trim()
                                if (n.isEmpty()) return@TextButton
                                scope.launch(Dispatchers.IO) {
                                    val existing = db.labels().all().firstOrNull { it.name == n }
                                    val lid = existing?.id ?: db.labels().insert(Label(name = n, createdAt = System.currentTimeMillis()))
                                    db.mailLabels().insert(MailLabel(lid, m.id))
                                    withContext(Dispatchers.Main) {
                                        allLabels = db.labels().all()
                                        labelIds = labelIds + lid
                                        newName = ""
                                        toast = "已创建标签「$n」并加入这封邮件"
                                    }
                                }
                            }) { Text("新建并加入") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLabels = false }) { Text("完成") } })
        }
        // 备注对话框：无需添加联系人也能备注/取消备注
        if (showRemark) {
            var remarkText by remember { mutableStateOf(contact?.remark ?: "") }
            AlertDialog(onDismissRequest = { showRemark = false },
                title = { Text("给 ${m.fromAddr} 备注") },
                text = {
                    Column {
                        TextField(value = remarkText, onValueChange = { remarkText = it },
                            placeholder = { Text("备注名(留空即取消备注，显示对方原名)") }, singleLine = true)
                        Text("备注后，这位发件人在所有邮件列表(全部/未读/星标等)中都显示备注名。",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showRemark = false
                        scope.launch(Dispatchers.IO) {
                            val c = db.contacts().byEmail(m.fromAddr)
                            if (c != null) {
                                db.contacts().update(c.copy(remark = remarkText.trim()))
                            } else {
                                db.contacts().insert(Contact(
                                    email = m.fromAddr, remark = remarkText.trim(), phone = "", org = "", note = "",
                                    isImportant = false, lastReceivedAt = m.date, createdAt = System.currentTimeMillis()))
                            }
                            withContext(Dispatchers.Main) {
                                contact = db.contacts().byEmail(m.fromAddr)
                                toast = if (remarkText.isBlank()) "已取消备注，恢复显示对方原名" else "备注成功：${remarkText.trim()}"
                            }
                        }
                    }) { Text(if (remarkText.isBlank()) "取消备注" else "保存备注") }
                },
                dismissButton = { TextButton(onClick = { showRemark = false }) { Text("关闭") } })
        }
    }
}

/** 把 cid 引用换成本地文件路径，插入基础样式 */
private fun htmlForWebView(m: MailMessage): String {
    var html = m.bodyHtml.ifBlank { "<p style='color:#999'>(无正文内容)</p>" }
    AttInfo.fromJson(m.attachmentsJson).filter { it.isInline && it.cid.isNotBlank() }.forEach { a ->
        // 本地文件确实存在才替换，避免换成不存在的路径出现裂图
        if (a.path.isNotBlank() && java.io.File(a.path).exists())
            html = html.replace("cid:${a.cid}", "file://${a.path}")
    }
    // 没有对应内联附件的 cid 图片(如名片头像丢失/历史数据)直接隐藏，避免出现裂图图标
    html = html.replace(Regex("<img[^>]*src=[\\\\\"']cid:[^\\\\\"']*[\\\\\"'][^>]*>?"), "")
    return """
    <html><head><meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    ${fontFaceCss()}
    <style>body{font-family:sans-serif;padding:12px;color:#222;word-break:break-word}
    img{max-width:100%;height:auto;border-radius:6px}
    a{color:#1565C0}</style></head><body>$html</body></html>
    """.trimIndent()
}

fun openWithProvider(ctx: android.content.Context, f: File, mime: String, name: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "com.rogan.cloudmail.fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
