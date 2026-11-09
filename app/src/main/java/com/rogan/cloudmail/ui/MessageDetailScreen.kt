package com.rogan.cloudmail.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.core.content.FileProvider
import com.rogan.cloudmail.data.*
import com.rogan.cloudmail.mail.MailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
    var contact by remember { mutableStateOf<Contact?>(null) }
    var m0 by remember { mutableStateOf<MailMessage?>(null) }
    var recallResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
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
        topBar = {
            TopAppBar(
                title = { Text("邮件详情", fontSize = 18.sp) },
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
                        IconButton(onClick = { nav.navigate("forward/${m.id}") }) { Icon(Icons.Default.Shortcut, "转发") }
                        if (m.folder == "SENT") {
                            IconButton(onClick = { confirmRecall = true }) { Icon(Icons.Default.Undo, "撤回") }
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "删除") }
                    }
                }
            )
        }
    ) { pad ->
        if (m == null) { Box(Modifier.padding(pad).fillMaxSize()); return@Scaffold }
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
                    if (m.folder == "INBOX") {
                        TextButton(onClick = { showRemark = true }, contentPadding = PaddingValues(4.dp)) {
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
                        }, contentPadding = PaddingValues(4.dp)) { Text("加联系人", fontSize = 11.sp) }
                    }
                }
                if (m.ccAddr.isNotBlank()) Text("抄送：${m.ccAddr}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (m.isAd) Text("此邮件疑似广告邮件", fontSize = 11.sp, color = Color(0xFFEF6C00),
                    modifier = Modifier.padding(top = 2.dp))
                // 退信提示
                val isBounce = m.fromAddr.contains("mailer-daemon", true) ||
                        m.fromAddr.contains("postmaster", true) ||
                        m.subject.contains("Undeliverable", true) ||
                        m.subject.contains("退回", false) ||
                        m.subject.contains("Mail Delivery", true)
                if (isBounce) {
                    Text("⚠ 这是退信通知：你之前发送的邮件未能成功送达对方服务器(可能对方邮箱不存在/已满/拒收)。详情见正文。",
                        fontSize = 12.sp, color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            HorizontalDivider()
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // 正文 WebView(占据剩余全部空间，滑动范围更大)
            val html = remember(m.id, m.bodyHtml) { htmlForWebView(m) }
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { viewCtx ->
                    android.webkit.WebView(viewCtx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean {
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    runCatching { viewCtx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)) ) }
                                    return true
                                }
                                return false
                            }
                        }
                        // 点击正文中的图片 → 全屏预览/保存
                        setOnTouchListener { _, ev ->
                            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                                val hr = hitTestResult
                                if (hr.type == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
                                    hr.type == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                                    val src = hr.extra ?: return@setOnTouchListener false
                                    val path = src.removePrefix("file://").substringBefore("?")
                                    val f = File(path)
                                    if (f.exists()) { showImg = f; return@setOnTouchListener true }
                                }
                            }
                            false
                        }
                    }
                },
                update = { wv -> wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            // 附件区
            val atts = remember(m.attachmentsJson) { AttInfo.fromJson(m.attachmentsJson) }
            if (atts.isNotEmpty()) {
                HorizontalDivider()
                Column(Modifier.padding(12.dp)) {
                    Text("附件 (${atts.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
                                    val saved = saveToDownloads(File(a.path), File(a.path).name)
                                    withContext(Dispatchers.Main) { toast = "已保存到下载文件夹：${saved.name}" }
                                }
                            }) { Text("保存", fontSize = 12.sp) }
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
                            val saved = saveToDownloads(File(img.path), img.name)
                            withContext(Dispatchers.Main) { toast = "已保存到下载文件夹：${saved.name}" }
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
        toast?.let { t ->
            Snackbar(Modifier.padding(16.dp)) { Text(t) }
            LaunchedEffect(t) { kotlinx.coroutines.delay(3000); toast = null }
        }
    }
}

/** 把 cid 引用换成本地文件路径，插入基础样式 */
private fun htmlForWebView(m: MailMessage): String {
    var html = m.bodyHtml.ifBlank { "<p style='color:#999'>(无正文内容)</p>" }
    AttInfo.fromJson(m.attachmentsJson).filter { it.isInline && it.cid.isNotBlank() }.forEach { a ->
        html = html.replace("cid:${a.cid}", "file://${a.path}")
    }
    return """
    <html><head><meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
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
