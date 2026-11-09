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
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
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
    var showImg by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRecall by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val m = db.messages().byId(id) ?: return@LaunchedEffect
        msg = m
        acc = db.accounts().byId(m.accountId)
        if (!m.isRead) { m.isRead = true; db.messages().setRead(id, true) }
        if (!m.loaded && acc != null) {
            loading = true
            val loaded = withContext(Dispatchers.IO) { MailService.loadFull(ctx, acc!!, m, db) }
            msg = loaded; loading = false
        } else loading = false
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
                            m.isStarred = !m.isStarred
                            db.messages().setStar(m.id, m.isStarred)
                            msg = m.copy()
                        }) { Icon(if (m.isStarred) Icons.Default.Star else Icons.Default.StarBorder, "星标",
                            tint = if (m.isStarred) Color(0xFFFBC02D) else MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = {
                            scope.launch {
                                val to = if (m.folder == "SENT") m.toAddr.substringBefore(",").trim() else m.fromAddr
                                nav.navigate("composeTo/$to")
                            }
                        }) { Icon(Icons.Default.Reply, "回复") }
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
            // 头部信息
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(m.subject.ifBlank { "(无主题)" }, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val contact = remember(m.fromAddr) { mutableStateOf<Contact?>(null) }
                    LaunchedEffect(m.fromAddr) { contact.value = db.contacts().byEmail(m.fromAddr) }
                    AvatarOf(contact.value?.remark?.ifBlank { null } ?: m.fromName, null, 38.dp)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(displayName(contact.value?.remark, m.fromAddr, m.fromName), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("<${m.fromAddr}>", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (m.folder == "INBOX") {
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
                        }) { Text("加联系人", fontSize = 12.sp) }
                    }
                    Text(fmtFull(m.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (m.toAddr.isNotBlank()) Text("收件人：${m.toAddr}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.ccAddr.isNotBlank()) Text("抄送：${m.ccAddr}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.isAd) Text("此邮件疑似广告邮件", fontSize = 12.sp, color = Color(0xFFEF6C00),
                    modifier = Modifier.padding(top = 4.dp))
            }
            HorizontalDivider()
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // 正文 WebView
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
                                Text("${File(a.path).length() / 1024} KB · ${a.mime.substringAfter('/', "file")}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                val f = File(a.path)
                                if (f.exists()) {
                                    if (a.mime.startsWith("image/")) showImg = a.path
                                    else openWithProvider(ctx, f, a.mime, a.name)
                                } else toast = "附件文件已不存在"
                            }) { Text("打开") }
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val f = File(a.path)
                                    if (f.exists()) {
                                        val saved = saveToDownloads(f, a.name)
                                        withContext(Dispatchers.Main) {
                                            toast = "已保存到下载文件夹：${saved.name}"
                                        }
                                    } else toast = "附件文件已不存在"
                                }
                            }) { Text("保存") }
                        }
                    }
                }
            }
        }
        // 图片大图预览
        showImg?.let { path ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { showImg = null }) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    AsyncImage(model = File(path), contentDescription = null,
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val saved = saveToDownloads(File(path), File(path).name)
                                withContext(Dispatchers.Main) { toast = "已保存到下载文件夹：${saved.name}" }
                            }
                        }) { Text("保存到相册/下载") }
                        TextButton(onClick = { showImg = null }) { Text("关闭") }
                    }
                }
            }
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
                        if (m.folder == "TRASH") {
                            db.messages().deleteForever(m.id)
                        } else {
                            m.folder = "TRASH"; db.messages().update(m)
                        }
                        nav.popBackStack()
                    }) { Text(if (m.folder == "TRASH") "彻底删除" else "删除") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        if (m.folder == "TRASH") {
                            db.messages().setFolder(m.id, "INBOX")
                            toast = "已恢复到收件箱"
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
                                MailService.recall(acc!!, m.messageId, m.toAddr.substringBefore(",").trim(), m.subject)
                            }
                            toast = r.second
                            if (r.first || r.second.contains("已从")) {
                                m.folder = "TRASH"; db.messages().update(m)
                            }
                        }
                    }) { Text("确认撤回") }
                },
                dismissButton = { TextButton(onClick = { confirmRecall = false }) { Text("取消") } }
            )
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
