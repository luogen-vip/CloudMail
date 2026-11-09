package com.rogan.cloudmail.ui

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.*
import com.rogan.cloudmail.mail.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(nav: NavHostController, draftId: Long, presetTo: String) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var sendAccId by remember { mutableStateOf(-1L) }

    var to by remember { mutableStateOf(TextFieldValue(presetTo)) }
    var cc by remember { mutableStateOf(TextFieldValue("")) }
    var bcc by remember { mutableStateOf(TextFieldValue("")) }
    var showCc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(TextFieldValue("")) }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var atts by remember { mutableStateOf(listOf<AttInfo>()) }
    var readReceipt by remember { mutableStateOf(false) }
    var cardMode by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showExit by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var loadedDraft by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        accounts = db.accounts().all()
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
        }
        loadedDraft = true
    }

    val hasContent = to.text.isNotBlank() || cc.text.isNotBlank() || bcc.text.isNotBlank() ||
            subject.text.isNotBlank() || body.text.isNotBlank() || atts.isNotEmpty()

    val acc = accounts.firstOrNull { it.id == sendAccId }

    fun saveDraft(asScheduled: Boolean, whenMillis: Long?): Long {
        val id = runBlocking2 {
            db.outbox().insert(OutboxItem(
                id = if (draftId > 0) draftId else 0,
                accountId = sendAccId,
                toAddr = to.text.trim(), ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                subject = subject.text, bodyHtml = plainToHtml(body.text),
                attachmentsJson = org.json.JSONArray().apply { atts.forEach { put(org.json.JSONObject(it.toJson())) } }.toString(),
                readReceipt = readReceipt, cardMode = cardMode,
                scheduledAt = if (asScheduled) whenMillis else null,
                status = if (asScheduled) "SCHEDULED" else "DRAFT",
                updatedAt = System.currentTimeMillis()
            ))
        }
        return id
    }

    val imagePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> scope.launch(Dispatchers.IO) {
            val f = copyUriToFile(ctx, u, "img_${System.currentTimeMillis()}")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                atts = atts + AttInfo(f.name, f.absolutePath, "image/jpeg", true, "img${atts.size}${System.currentTimeMillis()}")
            }
        } }
    }
    val filePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> scope.launch(Dispatchers.IO) {
            val f = copyUriToFile(ctx, u, "file_${System.currentTimeMillis()}")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                atts = atts + AttInfo(f.name, f.absolutePath, guessMime(f.name), false, "")
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
        runCatching { recorder?.stop() }
        recorder?.release(); recorder = null
        recording = false
        if (save && recFile != null && recFile!!.exists()) {
            atts = atts + AttInfo(recFile!!.name, recFile!!.absolutePath, "audio/mp4", false, "")
            toast = "录音已添加为附件"
        } else recFile?.delete()
        recFile = null
    }

    fun doSend(scheduledAt: Long?) {
        val rcpt = to.text.split(",", ";", "，", "；", " ").map { it.trim() }.filter { it.isNotBlank() }
        if (rcpt.isEmpty()) { toast = "请填写收件人邮箱"; return }
        if (sendAccId <= 0 || acc == null) { toast = "请先添加邮箱账号"; return }
        val finalTo = rcpt.joinToString(",")
        scope.launch(Dispatchers.IO) {
            val itemId = if (draftId > 0 && db.outbox().byId(draftId) != null) draftId
            else db.outbox().insert(OutboxItem(
                accountId = sendAccId, toAddr = finalTo, ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                subject = subject.text, bodyHtml = plainToHtml(body.text),
                attachmentsJson = org.json.JSONArray().apply { atts.forEach { put(org.json.JSONObject(it.toJson())) } }.toString(),
                readReceipt = readReceipt, cardMode = cardMode,
                scheduledAt = scheduledAt,
                status = if (scheduledAt != null) "SCHEDULED" else "DRAFT",
                updatedAt = System.currentTimeMillis()))
            // 更新已有草稿内容
            if (draftId > 0) {
                db.outbox().byId(draftId)?.let { o ->
                    db.outbox().update(o.copy(
                        toAddr = finalTo, ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                        subject = subject.text, bodyHtml = plainToHtml(body.text),
                        attachmentsJson = org.json.JSONArray().apply { atts.forEach { put(org.json.JSONObject(it.toJson())) } }.toString(),
                        readReceipt = readReceipt, cardMode = cardMode,
                        scheduledAt = scheduledAt,
                        status = if (scheduledAt != null) "SCHEDULED" else "DRAFT",
                        accountId = sendAccId, updatedAt = System.currentTimeMillis()))
                }
            }
            if (scheduledAt != null) {
                SendWorker.sendAt(ctx, itemId, scheduledAt)
                kotlinx.coroutines.withContext(Dispatchers.Main) { toast = "定时发送已设置：${fmtFull(scheduledAt)}" }
            } else {
                SendWorker.sendNow(ctx, itemId)
                kotlinx.coroutines.withContext(Dispatchers.Main) { toast = "正在发送…" }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                kotlinx.coroutines.delay(600)
                nav.popBackStack()
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
            AlertDialog(onDismissRequest = {}, title = { Text("正在录音…") },
                confirmButton = { TextButton(onClick = { stopRecording(true) }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { stopRecording(false) }) { Text("取消录音") } })
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            // 发件账号选择
            item {
                if (accounts.isEmpty()) {
                    Text("还没有添加邮箱账号，请先在侧边栏「添加邮箱账号」中添加",
                        color = Color(0xFFEF6C00), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("发件账号：", fontSize = 13.sp)
                        accounts.forEach { a ->
                            Row(
                                Modifier.padding(end = 10.dp).clickable {
                                    sendAccId = a.id
                                    cardMode = a.cardDefault
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = sendAccId == a.id, onClick = { sendAccId = a.id; cardMode = a.cardDefault })
                                Text(a.nickname.ifBlank { a.email } + if (a.isDefault) "(主)" else "", fontSize = 13.sp)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            item { FieldRow("收件人", to, { to = it }, placeholder = "可填多个邮箱，逗号分隔(默认上限5个)", isError = to.text.split(",", ";", " ").filter { it.isNotBlank() }.size > 5) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showCc = !showCc }) { Text(if (showCc) "隐藏抄送/密送" else "抄送/密送", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("已读回执", fontSize = 13.sp)
                    Switch(checked = readReceipt, onCheckedChange = { readReceipt = it }, modifier = Modifier.scale(0.8f))
                    Text("名片", fontSize = 13.sp)
                    Switch(checked = cardMode, onCheckedChange = { cardMode = it }, modifier = Modifier.scale(0.8f))                }
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
                    placeholder = { Text("正文内容…") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
            }
            item {
                Row {
                    TextButton(onClick = { imagePick.launch("image/*") }) { Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Text(" 图片") }
                    TextButton(onClick = { filePick.launch("*/*") }) { Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)); Text(" 文件") }
                }
            }
            items(atts) { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            a.mime.startsWith("image/") -> Icons.Default.Image
                            a.mime.startsWith("video/") -> Icons.Default.PlayCircle
                            a.mime.startsWith("audio/") -> Icons.Default.Headphones
                            else -> Icons.Default.InsertDriveFile
                        }, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(a.name, fontSize = 13.sp, maxLines = 1)
                        Text(if (a.isInline) "内联图片(显示在正文中)" else a.mime, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { atts = atts - a }) { Text("移除", color = Color(0xFFEF6C00)) }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }

        if (showLink) {
            var lt by remember { mutableStateOf(TextFieldValue("")) }
            var lu by remember { mutableStateOf(TextFieldValue("")) }
            AlertDialog(
                onDismissRequest = { showLink = false },
                title = { Text("插入链接文字") },
                text = {
                    Column {
                        TextField(value = lt, onValueChange = { lt = it }, placeholder = { Text("显示的文字，如：你好") })
                        TextField(value = lu, onValueChange = { lu = it }, placeholder = { Text("链接地址，如：https://example.com") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (lt.text.isNotBlank() && lu.text.startsWith("http")) {
                            body = TextFieldValue(body.text + "[${lt.text}](${lu.text})")
                            showLink = false
                        } else toast = "文字和链接(http/https开头)都要填写"
                    }) { Text("插入") }
                },
                dismissButton = { TextButton(onClick = { showLink = false }) { Text("取消") } }
            )
        }
        if (showSchedule) {
            var picked by remember { mutableStateOf<Long?>(null) }
            AlertDialog(
                onDismissRequest = { showSchedule = false },
                title = { Text("定时发送(24小时制)") },
                text = {
                    Column {
                        Button(onClick = {
                            val c = Calendar.getInstance()
                            DatePickerDialog(ctx, { _, y, mo, d ->
                                TimePickerDialog(ctx, { _, h, mi ->
                                    val cal = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0) }
                                    picked = cal.timeInMillis
                                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                        }) { Text("选择日期和时间") }
                        picked?.let { Text("将定时发送：${fmtFull(it)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = picked != null, onClick = {
                        showSchedule = false; doSend(picked)
                    }) { Text("确定定时发送") }
                },
                dismissButton = { TextButton(onClick = { showSchedule = false }) { Text("取消") } }
            )
        }
        if (showExit) {
            AlertDialog(
                onDismissRequest = { showExit = false },
                title = { Text("返回") },
                text = { Text("你已输入内容，离开前要保存草稿吗？") },
                confirmButton = {
                    TextButton(onClick = {
                        showExit = false
                        scope.launch(Dispatchers.IO) { saveDraft(false, null) }
                        nav.popBackStack()
                    }) { Text("保存草稿") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showExit = false; nav.popBackStack() }) { Text("不保存离开") }
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

/** 纯文本 → HTML：转义、换行、[文字](链接)、自动高亮真实URL */
fun plainToHtml(plain: String): String {
    val esc = plain.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val mdLink = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    val url = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")
    var html = esc
    html = mdLink.replace(html) { mr -> "<a href=\"${mr.groupValues[2]}\">${mr.groupValues[1]}</a>" }
    // 防止上面生成的 <a> 被再次处理：拆分处理已链接部分
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

// 阻塞式小工具(仅存草稿这种毫秒级操作)
private fun <T> runBlocking2(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
