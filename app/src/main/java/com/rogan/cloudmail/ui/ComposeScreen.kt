package com.rogan.cloudmail.ui

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.*
import com.rogan.cloudmail.mail.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(nav: NavHostController, draftId: Long, presetTo: String, forwardId: Long = -1) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var sendAccId by remember { mutableStateOf(-1L) }
    var showAccMenu by remember { mutableStateOf(false) }
    var showContactMenu by remember { mutableStateOf(false) }

    var to by remember { mutableStateOf(TextFieldValue(presetTo)) }
    var cc by remember { mutableStateOf(TextFieldValue("")) }
    var bcc by remember { mutableStateOf(TextFieldValue("")) }
    var showCc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(TextFieldValue("")) }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var atts by remember { mutableStateOf(listOf<AttInfo>()) }
    var readReceipt by remember { mutableStateOf(false) }
    var cardMode by remember { mutableStateOf(false) }
    var showCardPreview by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showExit by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var loadedDraft by remember { mutableStateOf(false) }
    var previewAtt by remember { mutableStateOf<AttInfo?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        accounts = db.accounts().all()
        contacts = db.contacts().all()
        val def = accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull()
        sendAccId = def?.id ?: -1
        cardMode = def?.cardDefault ?: false
        if (draftId > 0) {
            db.outbox().byId(draftId)?.let { o ->
                to = TextFieldValue(o.toAddr); cc = TextFieldValue(o.ccAddr); bcc = TextFieldValue(o.bccAddr)
                subject = TextFieldValue(o.subject)
                body = TextFieldValue(htmlToPlain(o.bodyHtml))
                atts = AttInfo.fromJson(o.attachmentsJson)
                readReceipt = o.readReceipt; cardMode = o.cardMode; sendAccId = o.accountId
            }
        } else if (forwardId > 0) {
            // 转发：原样带入原邮件(可编辑主题/正文，附件同样带入)
            db.messages().byId(forwardId)?.let { m ->
                subject = TextFieldValue("转发：" + m.subject.ifBlank { "(无主题)" })
                val hdr = buildString {
                    appendLine("---------- 转发的邮件 ----------")
                    appendLine("发件人：${m.fromName.ifBlank { m.fromAddr }} <${m.fromAddr}>")
                    if (m.toAddr.isNotBlank()) appendLine("收件人：${m.toAddr}")
                    appendLine("日期：${fmtFull(m.date)}")
                    appendLine()
                }
                if (!m.loaded) {
                    db.accounts().byId(m.accountId)?.let { a ->
                        runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.rogan.cloudmail.mail.MailService.loadFull(ctx, a, m, db) } }
                    }
                }
                val fresh = db.messages().byId(forwardId) ?: m
                body = TextFieldValue(hdr + htmlToPlain(fresh.bodyHtml))
                atts = AttInfo.fromJson(fresh.attachmentsJson)
            }
        }
        loadedDraft = true
    }

    // 录音计时(真实时长)
    LaunchedEffect(recording) {
        recSeconds = 0
        while (recording) { kotlinx.coroutines.delay(1000); recSeconds++ }
    }

    val hasContent = to.text.isNotBlank() || cc.text.isNotBlank() || bcc.text.isNotBlank() ||
            subject.text.isNotBlank() || body.text.isNotBlank() || atts.isNotEmpty()

    val acc = accounts.firstOrNull { it.id == sendAccId }

    fun saveDraftNow(): Long = kotlinx.coroutines.runBlocking {
        if (!loadedDraft || !hasContent) return@runBlocking -1L
        try {
            if (draftId > 0) {
                db.outbox().byId(draftId)?.let { o ->
                    db.outbox().update(o.copy(
                        accountId = sendAccId,
                        toAddr = to.text.trim(), ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                        subject = subject.text, bodyHtml = plainToHtml(body.text),
                        attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                        readReceipt = readReceipt, cardMode = cardMode,
                        status = if (o.status == "FAILED" || o.status == "SENDING") "DRAFT" else o.status,
                        scheduledAt = o.scheduledAt, error = "", updatedAt = System.currentTimeMillis()))
                }
                draftId
            } else {
                db.outbox().insert(OutboxItem(
                    accountId = sendAccId,
                    toAddr = to.text.trim(), ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                    subject = subject.text, bodyHtml = plainToHtml(body.text),
                    attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                    readReceipt = readReceipt, cardMode = cardMode,
                    scheduledAt = null, status = "DRAFT", updatedAt = System.currentTimeMillis()))
            }
        } catch (e: Exception) { -1L }
    }

    // 离开页面/进程被回收前自动保存草稿，保证内容不丢
    var savedOnExit by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (!savedOnExit && loadedDraft && hasContent && sendAccId > 0) saveDraftNow()
            runCatching { player?.release() }
        }
    }
    BackHandler(enabled = hasContent) { showExit = true }

    val imagePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> scope.launch(Dispatchers.IO) {
            val f = copyUriToFile(ctx, u, "img_${System.currentTimeMillis()}")
            val mime = ctx.contentResolver.getType(u) ?: guessMime(f.name)
            val cid = "img${System.currentTimeMillis()}"
            val size = f.length()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                atts = atts + AttInfo(f.name, f.absolutePath, mime, true, cid)
                // 图片插入正文标记：可剪切到正文任意位置，删除标记即从正文中移除
                val pos = body.selection.end.coerceIn(0, body.text.length)
                val marker = (if (body.text.isNotBlank()) "\n" else "") + "[图片:$cid]"
                body = TextFieldValue(body.text.substring(0, pos) + marker + body.text.substring(pos),
                    androidx.compose.ui.text.TextRange(pos + marker.length))
                toast = "图片已插入正文(大小 ${fmtSize(size)})；标记 [图片:$cid] 可移动或删除"
            }
        } }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> scope.launch(Dispatchers.IO) {
            val f = copyUriToFile(ctx, u, "file_${System.currentTimeMillis()}")
            val mime = ctx.contentResolver.getType(u) ?: guessMime(f.name)
            val size = f.length()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                atts = atts + AttInfo(f.name, f.absolutePath, mime, false, "")
                toast = "附件已添加(大小 ${fmtSize(size)})，附件作为文件发送"
            }
        } }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val f = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            recFile = f
            runCatching {
                MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(f.absolutePath)
                    prepare(); start()
                }.also { recorder = it }
                recording = true
            }.onFailure { toast = "录音启动失败：${it.message}" }
        } else toast = "需要麦克风权限才能录音"
    }

    fun stopRecording(save: Boolean) {
        val secs = recSeconds
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        recording = false
        if (save && recFile != null && recFile!!.exists() && recFile!!.length() > 0) {
            val mm = secs / 60; val ss = secs % 60
            val name = "录音(${mm}分${ss}秒).m4a"
            val target = File(ctx.filesDir, "rec_${System.currentTimeMillis()}.m4a")
            recFile!!.copyTo(target, overwrite = true)
            atts = atts + AttInfo(name, target.absolutePath, "audio/mp4", false, "", dur = secs)
            toast = "录音已添加(${mm}分${ss}秒，${fmtSize(target.length())})，点击可试听"
        } else recFile?.delete()
        recFile = null
    }

    fun removeAtt(a: AttInfo) {
        atts = atts - a
        // 从正文中移除对应图片标记
        body = TextFieldValue(body.text.replace("[图片:${a.cid}]", "").replace(Regex("\n{3,}"), "\n\n"))
    }

    fun doSend(scheduledAt: Long?) {
        val rcpt = to.text.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() }
        if (rcpt.isEmpty()) { toast = "请填写收件人邮箱"; return }
        val bad = rcpt.filter { !emailRegex.matches(it) }
        if (bad.isNotEmpty()) { toast = "这些不是有效的邮箱地址：${bad.joinToString("、")}，请检查后重试"; return }
        if (cc.text.isNotBlank()) {
            val badCc = cc.text.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() && !emailRegex.matches(it) }
            if (badCc.isNotEmpty()) { toast = "抄送地址无效：${badCc.joinToString("、")}"; return }
        }
        if (bcc.text.isNotBlank()) {
            val badBcc = bcc.text.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() && !emailRegex.matches(it) }
            if (badBcc.isNotEmpty()) { toast = "密送地址无效：${badBcc.joinToString("、")}"; return }
        }
        if (sendAccId <= 0 || acc == null) { toast = "请先添加邮箱账号"; return }
        val finalTo = rcpt.joinToString(",")
        savedOnExit = true
        scope.launch(Dispatchers.IO) {
            val bodyHtml = plainToHtml(body.text)
            val item = if (draftId > 0 && db.outbox().byId(draftId) != null) {
                db.outbox().byId(draftId)!!.copy(
                    accountId = sendAccId, toAddr = finalTo, ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                    subject = subject.text, bodyHtml = bodyHtml,
                    attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                    readReceipt = readReceipt, cardMode = cardMode,
                    scheduledAt = scheduledAt,
                    status = if (scheduledAt != null) "SCHEDULED" else "SENDING",
                    error = "", updatedAt = System.currentTimeMillis())
            } else {
                OutboxItem(
                    accountId = sendAccId, toAddr = finalTo, ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                    subject = subject.text, bodyHtml = bodyHtml,
                    attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                    readReceipt = readReceipt, cardMode = cardMode,
                    scheduledAt = scheduledAt,
                    status = if (scheduledAt != null) "SCHEDULED" else "SENDING",
                    updatedAt = System.currentTimeMillis())
            }
            val itemId = if (item.id > 0) { db.outbox().update(item); item.id } else db.outbox().insert(item)
            if (scheduledAt != null) {
                SendWorker.sendAt(ctx, itemId, scheduledAt)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    toast = "定时发送已设置：${fmtFull(scheduledAt)}，APP 关闭后到点仍会自动真实发送"
                    kotlinx.coroutines.delay(1200); nav.popBackStack()
                }
            } else {
                SendWorker.sendNow(ctx, itemId)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    toast = "正在发送…成功或失败都会在通知栏提示"
                    kotlinx.coroutines.delay(800); nav.popBackStack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("写邮件", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = {
                    if (hasContent) showExit = true else nav.popBackStack()
                }) { Icon(Icons.Default.Close, "返回") } },
                actions = {
                    IconButton(onClick = { showLink = true }) { Icon(Icons.Default.Link, "插入链接") }
                    IconButton(onClick = { if (recording) stopRecording(true) else micPerm.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, "录音")
                    }
                    IconButton(onClick = { showSchedule = true }) { Icon(Icons.Default.Schedule, "定时发送") }
                    IconButton(onClick = { doSend(null) }) { Icon(Icons.Default.Send, "发送") }
                }
            )
        }
    ) { pad ->
        if (recording) {
            AlertDialog(onDismissRequest = {},
                title = { Text("正在录音…") },
                text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${recSeconds / 60}:${(recSeconds % 60).toString().padStart(2, '0')}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("录音时长实时计时，无上限", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                confirmButton = { TextButton(onClick = { stopRecording(true) }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { stopRecording(false) }) { Text("取消录音") } })
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            // 发件账号选择：下拉选择所有已添加账号
            item {
                if (accounts.isEmpty()) {
                    Text("还没有添加邮箱账号，请先在侧边栏「添加邮箱账号」中添加",
                        color = Color(0xFFEF6C00), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    ExposedDropdownMenuBox(expanded = showAccMenu, onExpandedChange = { showAccMenu = it }) {
                        OutlinedTextField(
                            value = acc?.let { "${it.nickname.ifBlank { it.email }}  <${it.email}>" + if (it.isDefault) "  (主账号)" else "" } ?: "",
                            onValueChange = {}, readOnly = true, label = { Text("发件账号(点击选择)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showAccMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor().padding(vertical = 4.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = true)
                        ExposedDropdownMenu(expanded = showAccMenu, onDismissRequest = { showAccMenu = false }) {
                            accounts.forEach { a ->
                                DropdownMenuItem(text = { Text("${a.nickname.ifBlank { a.email }}  <${a.email}>" + if (a.isDefault) " (主)" else "", fontSize = 13.sp) },
                                    onClick = { sendAccId = a.id; cardMode = a.cardDefault; showAccMenu = false })
                            }
                            if (accounts.size == 1) DropdownMenuItem(text = { Text("只有 1 个账号，可在侧边栏添加更多", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                        }
                    }
                    HorizontalDivider()
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("收件人", fontSize = 14.sp, modifier = Modifier.width(56.dp))
                    TextField(value = to, onValueChange = { to = it },
                        placeholder = { Text("可填多个邮箱，逗号分隔", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        isError = to.text.split(",", ";", "，", "；").map { it.trim() }.any { it.isNotBlank() && !emailRegex.matches(it) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    IconButton(onClick = { showContactMenu = !showContactMenu }) { Icon(Icons.Default.Contacts, "选择联系人") }
                }
                if (showContactMenu) {
                    if (contacts.isEmpty()) Text("还没有联系人，可在「加联系人」或联系人页添加", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp))
                    contacts.take(10).forEach { c ->
                        Text("· ${c.remark.ifBlank { c.email }} <${c.email}>", fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val cur = to.text.trimEnd(',', '，')
                                to = TextFieldValue(if (cur.isBlank()) c.email else "$cur,${c.email}")
                                showContactMenu = false
                            }.padding(start = 56.dp, top = 6.dp, bottom = 6.dp))
                    }
                    HorizontalDivider()
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showCc = !showCc }) { Text(if (showCc) "隐藏抄送/密送" else "抄送/密送", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("已读回执", fontSize = 13.sp)
                    Switch(checked = readReceipt, onCheckedChange = { readReceipt = it }, modifier = Modifier.scale(0.8f))
                    Text("名片", fontSize = 13.sp)
                    Switch(checked = cardMode, onCheckedChange = { cardMode = it }, modifier = Modifier.scale(0.8f))
                    TextButton(onClick = { showCardPreview = true }, contentPadding = PaddingValues(4.dp), modifier = Modifier.scale(0.85f)) { Text("预览", fontSize = 12.sp) }
                }
                Text("名片内容在「设置→点击邮箱账号→名片(资料卡)信息」中编辑；开启后随邮件一起发出，收件人可见",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
            if (showCc) {
                item { FieldRow("抄送", cc, { cc = it }) }
                item { FieldRow("密送", bcc, { bcc = it }) }
            }
            item {
                TextField(value = subject, onValueChange = { subject = it },
                    placeholder = { Text("主题") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                HorizontalDivider()
            }
            item {
                TextField(value = body, onValueChange = { body = it },
                    placeholder = { Text("正文内容…（插入图片后会出现 [图片:xxx] 标记，代表图片在正文中的位置，可移动/删除）") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
            }
            item {
                Row {
                    TextButton(onClick = { imagePick.launch("image/*") }) { Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Text(" 图片") }
                    TextButton(onClick = { filePick.launch("*/*") }) { Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)); Text(" 文件") }
                    Text("插入「图片」= 图片直接显示在正文中；插入「文件」= 作为附件文件发送", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                }
            }
            items(atts) { a ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { previewAtt = a }) {
                    Icon(
                        when {
                            a.mime.startsWith("image/") -> Icons.Default.Image
                            a.mime.startsWith("video/") -> Icons.Default.PlayCircle
                            a.mime.startsWith("audio/") -> Icons.Default.Headphones
                            else -> Icons.Default.InsertDriveFile
                        }, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(a.name, fontSize = 13.sp, maxLines = 1)
                        val sz = File(a.path).takeIf { it.exists() }?.length() ?: 0L
                        Text(buildString {
                            append(if (a.isInline) "内联图片(显示在正文中) · 点击预览" else "${a.mime} · 点击${if (a.mime.startsWith("audio/")) "试听" else "预览"}")
                            if (sz > 0) append(" · ${fmtSize(sz)}")
                            if (a.dur > 0) append(" · 时长 ${a.dur / 60}分${a.dur % 60}秒")
                        }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { removeAtt(a) }) { Text("移除", color = Color(0xFFEF6C00)) }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }

        // 附件/图片/录音预览
        previewAtt?.let { a ->
            AlertDialog(onDismissRequest = {
                runCatching { player?.stop(); player?.release() }; player = null; previewAtt = null
            }, title = { Text(a.name, fontSize = 15.sp) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (a.mime.startsWith("image/")) {
                            AsyncImage(model = File(a.path), contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                        } else {
                            Text("点击下方按钮播放试听", fontSize = 13.sp)
                        }
                        val sz = File(a.path).takeIf { it.exists() }?.length() ?: 0L
                        if (sz > 0) Text("大小：${fmtSize(sz)}${if (a.dur > 0) " · 时长 ${a.dur / 60}分${a.dur % 60}秒" else ""}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    if (!a.mime.startsWith("image/")) TextButton(onClick = {
                        runCatching { player?.release() }
                        player = MediaPlayer().apply {
                            setDataSource(a.path); prepare(); start()
                            setOnCompletionListener { it.release(); player = null }
                        }
                    }) { Text("播放") }
                    else {}
                },
                dismissButton = { TextButton(onClick = {
                    runCatching { player?.stop(); player?.release() }; player = null; previewAtt = null
                }) { Text("关闭") } })
        }

        // 名片预览
        if (showCardPreview && acc != null) {
            AlertDialog(onDismissRequest = { showCardPreview = false },
                title = { Text("名片预览(收件人将看到的效果)") },
                text = {
                    AndroidView(factory = { vctx ->
                        android.webkit.WebView(vctx).apply { settings.javaScriptEnabled = false }
                    }, update = { wv ->
                        wv.loadDataWithBaseURL(null,
                            "<html><body style=\"font-family:sans-serif\">${SendWorker.cardBlock(acc)}</body></html>",
                            "text/html", "utf-8", null)
                    }, modifier = Modifier.fillMaxWidth().height(220.dp))
                },
                confirmButton = { TextButton(onClick = { showCardPreview = false; nav.navigate("editAccount/${acc.id}") }) { Text("去编辑名片") } },
                dismissButton = { TextButton(onClick = { showCardPreview = false }) { Text("关闭") } })
        }

        if (showLink) {
            var lt by remember { mutableStateOf(TextFieldValue("")) }
            var lu by remember { mutableStateOf(TextFieldValue("")) }
            var linkErr by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { showLink = false },
                title = { Text("插入链接文字") },
                text = {
                    Column {
                        TextField(value = lt, onValueChange = { lt = it }, placeholder = { Text("显示的文字，如：点我打开") })
                        TextField(value = lu, onValueChange = { lu = it }, placeholder = { Text("链接地址，如：https://example.com") })
                        linkErr?.let { Text(it, fontSize = 12.sp, color = Color(0xFFEF6C00)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val u = lu.text.trim()
                        val okUrl = Regex("^https?://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*$").matches(u)
                        when {
                            lt.text.isBlank() -> linkErr = "请填写要显示的文字"
                            !u.startsWith("http") -> linkErr = "链接地址无效：必须以 http:// 或 https:// 开头"
                            !okUrl -> linkErr = "链接地址无效：请输入可访问的网址(如 https://www.example.com)，不能是普通文字"
                            else -> {
                                val ins = "[${lt.text}]($u)"
                                val pos = body.selection.end.coerceIn(0, body.text.length)
                                body = TextFieldValue(body.text.substring(0, pos) + ins + body.text.substring(pos),
                                    pos + ins.length)
                                showLink = false
                            }
                        }
                    }) { Text("插入到光标位置") }
                },
                dismissButton = { TextButton(onClick = { showLink = false }) { Text("取消") } }
            )
        }
        if (showSchedule) {
            // APP 风格的日期时间选择(月/日/时/分步进)，避免系统弹窗风格不搭
            val cal = Calendar.getInstance()
            var mo by remember { mutableStateOf(cal.get(Calendar.MONTH) + 1) }
            var dy by remember { mutableStateOf(cal.get(Calendar.DAY_OF_MONTH)) }
            var hh by remember { mutableStateOf(cal.get(Calendar.HOUR_OF_DAY)) }
            var mi by remember { mutableStateOf(cal.get(Calendar.MINUTE)) }
            fun validDate(m: Int, d: Int): Boolean {
                val max = when (m) { 1,3,5,7,8,10,12 -> 31; 4,6,9,11 -> 30; else -> 29 }
                return d in 1..max
            }
            val target: Long = run {
                val c = Calendar.getInstance()
                val now = c.timeInMillis
                c.set(c.get(Calendar.YEAR), mo - 1, dy, hh, mi, 0)
                if (c.timeInMillis <= now) { c.add(Calendar.YEAR, 1) }
                c.timeInMillis
            }
            val invalid = !validDate(mo, dy)
            AlertDialog(
                onDismissRequest = { showSchedule = false },
                title = { Text("定时发送") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("目标时间：${fmtFull(target)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Stepper("月份", mo, 1, 12) { mo = it }
                        Stepper("日期", dy, 1, 31) { dy = it }
                        if (invalid) Text("$mo 月没有 $dy 日，请修改", fontSize = 12.sp, color = Color(0xFFEF6C00))
                        Stepper("小时", hh, 0, 23) { hh = it }
                        Stepper("分钟", mi, 0, 59) { mi = it }
                        Text("到点自动真实发送；APP 不打开(被清理后台)也会生效", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !invalid, onClick = { showSchedule = false; doSend(target) }) { Text("确定定时发送") }
                },
                dismissButton = { TextButton(onClick = { showSchedule = false }) { Text("取消") } }
            )
        }
        if (showExit) {
            AlertDialog(
                onDismissRequest = { showExit = false },
                title = { Text("返回") },
                text = { Text("你已输入内容，离开前要保存草稿吗？(草稿在侧边栏「草稿/待发送」中，随时可继续编辑发送)") },
                confirmButton = {
                    TextButton(onClick = {
                        showExit = false; savedOnExit = true
                        scope.launch(Dispatchers.IO) { saveDraftNow() }
                        nav.popBackStack()
                    }) { Text("保存草稿") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showExit = false; savedOnExit = true; nav.popBackStack() }) { Text("不保存离开") }
                        TextButton(onClick = { showExit = false }) { Text("继续编辑") }
                    }
                }
            )
        }
        toast?.let { t ->
            Snackbar(Modifier.padding(16.dp)) { Text(t) }
            LaunchedEffect(t) { kotlinx.coroutines.delay(3000); toast = null }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 14.sp, modifier = Modifier.width(52.dp))
        IconButton(onClick = { if (value > min) onChange(value - 1) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Remove, null, Modifier.size(18.dp)) }
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(value.toString().padStart(2, '0'), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        }
        IconButton(onClick = { if (value < max) onChange(value + 1) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }
    }
}

@Composable
private fun FieldRow(label: String, value: TextFieldValue, onChange: (TextFieldValue) -> Unit,
                     placeholder: String = "", isError: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.width(56.dp))
        TextField(value = value, onValueChange = onChange, placeholder = { Text(placeholder, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(), isError = isError,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
    }
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 纯文本 → HTML：转义、换行、[文字](链接)、自动高亮真实URL、内联图片标记 */
fun plainToHtml(plain: String): String {
    val esc = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val mdLink = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    val inlineImg = Regex("\\[图片:([A-Za-z0-9]+)\\]")
    val url = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")
    var html = esc
    // 内联图片标记 → cid 图片(与附件一一对应)
    html = inlineImg.replace(html) { mr -> "<img src=\"cid:${mr.groupValues[1]}\" style=\"max-width:96%;border-radius:8px\"/>" }
    html = mdLink.replace(html) { mr -> "<a href=\"${mr.groupValues[2]}\">${mr.groupValues[1]}</a>" }
    val parts = Regex("(<a href=\"[^\"]+\">[^<]*</a>)").split(html)
    val out = parts.joinToString("") { seg ->
        if (seg.startsWith("<a href=")) seg
        else url.replace(seg) { mr ->
            val u = mr.value.trimEnd('.', ',', ')', '，', '。')
            if (isValidUrl(u)) "<a href=\"$u\">${u}</a>" else mr.value
        }
    }
    return out.replace("\n", "<br/>")
}

/** 真实地址校验：必须能解析出合法域名(不是仅凭 https:// 前缀就高亮) */
fun isValidUrl(u: String): Boolean {
    return runCatching {
        val uri = Uri.parse(u)
        val host = uri.host ?: return false
        host.contains('.') && !host.endsWith(".") && host.any { it.isLetterOrDigit() } &&
                (host.endsWith(".com") || host.endsWith(".net") || host.endsWith(".org") ||
                 host.endsWith(".cn") || host.endsWith(".io") || host.endsWith(".dev") ||
                 host.endsWith(".edu") || host.endsWith(".gov") || host.endsWith(".info") ||
                 host.endsWith(".me") || host.endsWith(".app") || host.contains(".com/") ||
                 host.length > 4) && try {
            java.net.InetAddress.getByName(host) != null
        } catch (e: Exception) { false }
    }.getOrDefault(false)
}

fun htmlToPlain(html: String): String = html
    .replace(Regex("<img src=\"cid:([A-Za-z0-9]+)\"[^>]*/>")) { "[图片:${it.groupValues[1]}]" }
    .replace(Regex("<a href=\"([^\"]+)\">([^<]*)</a>")) { "[${it.groupValues[2]}](${it.groupValues[1]})" }
    .replace("<br/>", "\n").replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "mp4" -> "video/mp4"; "avi" -> "video/x-msvideo"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "wav" -> "audio/wav"; "aac" -> "audio/aac"
        "pdf" -> "application/pdf"; "txt" -> "text/plain"; "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}

fun copyUriToFile(ctx: Context, uri: Uri, name: String): File {
    val f = File(ctx.filesDir, name)
    ctx.contentResolver.openInputStream(uri)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
    return f
}
