package com.rogan.cloudmail.ui

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
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

/* ================== 正文块模型 ==================
   正文由「文本块 / 图片块 / 链接块」顺序组成：
   - 图片块：正文里直接显示图片本身，点击可全屏预览或从正文删除
   - 链接块：正文里显示蓝色高亮链接文字，点击可编辑或删除；发送时真实生成 <a> 标签
   插入位置 = 当前光标所在文本块的光标处 */
sealed class BodyBlock {
    abstract val id: Long
    data class T(override val id: Long, val tv: TextFieldValue) : BodyBlock()
    data class Img(override val id: Long, val att: AttInfo) : BodyBlock()
    data class Lk(override val id: Long, val txt: String, val url: String) : BodyBlock()
}

private var blockSeq = System.currentTimeMillis()
private fun nextBlockId(): Long = ++blockSeq

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

    // 收件人：一行一个邮箱，发送时同时发给所有人
    val toRows = remember { mutableStateListOf(presetTo) }

    var cc by remember { mutableStateOf(TextFieldValue("")) }
    var bcc by remember { mutableStateOf(TextFieldValue("")) }
    var showCc by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf(TextFieldValue("")) }
    val blocks = remember { mutableStateListOf<BodyBlock>(BodyBlock.T(nextBlockId(), TextFieldValue(""))) }
    var focusBlockId by remember { mutableStateOf<Long?>(null) }
    var atts by remember { mutableStateOf(listOf<AttInfo>()) }
    var readReceipt by remember { mutableStateOf(false) }
    var cardMode by remember { mutableStateOf(false) }
    var showCardPreview by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showExit by remember { mutableStateOf(false) }
    // 用户是否手动输入过内容(收件人/主题/正文/附件等)；从联系人点进去自动填入的不算，返回时不误弹保存提醒
    var userEdited by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var loadedDraft by remember { mutableStateOf(false) }
    var previewAtt by remember { mutableStateOf<AttInfo?>(null) }
    var editImg by remember { mutableStateOf<BodyBlock.Img?>(null) }
    var editLink by remember { mutableStateOf<BodyBlock.Lk?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        accounts = db.accounts().all()
        contacts = db.contacts().all()
        val def = accounts.firstOrNull { it.isDefault } ?: accounts.firstOrNull()
        sendAccId = def?.id ?: -1
        cardMode = def?.cardDefault ?: false
        if (draftId > 0) {
            db.outbox().byId(draftId)?.let { o ->
                cc = TextFieldValue(o.ccAddr); bcc = TextFieldValue(o.bccAddr)
                subject = TextFieldValue(o.subject)
                atts = AttInfo.fromJson(o.attachmentsJson)
                readReceipt = o.readReceipt; cardMode = o.cardMode; sendAccId = o.accountId
                val rows = o.toAddr.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() }
                toRows.clear(); toRows.addAll(rows.ifEmpty { listOf("") })
                blocks.clear(); blocks.addAll(htmlToBlocks(o.bodyHtml, atts))
            }
        } else if (forwardId > 0) {
            // 转发：原样带入原邮件(可编辑主题/正文，附件同样带入)
            db.messages().byId(forwardId)?.let { m ->
                subject = TextFieldValue("转发：" + m.subject.ifBlank { "(无主题)" })
                if (!m.loaded) {
                    db.accounts().byId(m.accountId)?.let { a ->
                        runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.rogan.cloudmail.mail.MailService.loadFull(ctx, a, m, db) } }
                    }
                }
                val fresh = db.messages().byId(forwardId) ?: m
                atts = AttInfo.fromJson(fresh.attachmentsJson)
                val hdrText = buildString {
                    appendLine("---------- 转发的邮件 ----------")
                    appendLine("发件人：${m.fromName.ifBlank { m.fromAddr }} <${m.fromAddr}>")
                    if (m.toAddr.isNotBlank()) appendLine("收件人：${m.toAddr}")
                    appendLine("日期：${fmtFull(m.date)}")
                    appendLine()
                }
                blocks.clear()
                blocks.add(BodyBlock.T(nextBlockId(), TextFieldValue(hdrText)))
                blocks.addAll(htmlToBlocks(fresh.bodyHtml, atts))
            }
        }
        loadedDraft = true
    }

    // 录音计时(真实时长)
    LaunchedEffect(recording) {
        recSeconds = 0
        while (recording) { kotlinx.coroutines.delay(1000); recSeconds++ }
    }

    val acc = accounts.firstOrNull { it.id == sendAccId }

    // 是否弹「保存草稿」提醒：只有用户手动输入过内容，或从空白页输入了任何内容才提醒；
    // 从联系人/转发带入的自动内容不提醒；已保存草稿未再编辑也不重复提醒
    val shouldAskSave = userEdited || (hasContent && draftId <= 0 && presetTo.isBlank() && forwardId <= 0)

    /* ---------- 正文块操作 ---------- */

    /** 在光标处插入块：把焦点所在文本块在光标处拆开，中间插入新块 */
    fun insertIntoBody(b: BodyBlock) {
        val idx = blocks.indexOfFirst { it.id == focusBlockId && it is BodyBlock.T }
        if (idx >= 0) {
            val t = blocks[idx] as BodyBlock.T
            val pos = t.tv.selection.end.coerceIn(0, t.tv.text.length)
            val before = t.tv.text.substring(0, pos)
            val after = t.tv.text.substring(pos)
            blocks[idx] = BodyBlock.T(t.id, TextFieldValue(before))
            blocks.add(idx + 1, b)
            blocks.add(idx + 2, BodyBlock.T(nextBlockId(), TextFieldValue(after)))
        } else {
            val last = blocks.lastOrNull()
            if (last is BodyBlock.T) blocks.add(blocks.size - 1, b)
            else { blocks.add(BodyBlock.T(nextBlockId(), TextFieldValue(""))); blocks.add(b) }
        }
    }

    fun removeImgBlock(b: BodyBlock.Img) {
        blocks.remove(b)
        atts = atts.filter { it.cid != b.att.cid }
        // 若剩下的全是图片/链接块，补一个文本块方便继续输入
        if (blocks.none { it is BodyBlock.T }) blocks.add(BodyBlock.T(nextBlockId(), TextFieldValue("")))
    }

    /** 全部块 → 发送用 HTML */
    fun blocksToHtml(): String = blocks.mapNotNull { b ->
        when (b) {
            is BodyBlock.T -> if (b.tv.text.isBlank()) null else plainToHtml(b.tv.text)
            is BodyBlock.Img -> "<img src=\"cid:${b.att.cid}\" style=\"max-width:96%;border-radius:8px\"/>"
            is BodyBlock.Lk -> "<a href=\"${b.url}\">${escXml(b.txt)}</a>"
        }
    }.joinToString("<br/>")

    val fileAtts = atts.filter { !it.isInline }
    val hasContent = toRows.any { it.isNotBlank() } || cc.text.isNotBlank() || bcc.text.isNotBlank() ||
            subject.text.isNotBlank() || blocks.any { it !is BodyBlock.T || it.tv.text.isNotBlank() } || atts.isNotEmpty()

    fun saveDraftNow(): Long = kotlinx.coroutines.runBlocking {
        if (!loadedDraft || !hasContent) return@runBlocking -1L
        try {
            val bodyHtml = blocksToHtml()
            if (draftId > 0) {
                db.outbox().byId(draftId)?.let { o ->
                    db.outbox().update(o.copy(
                        accountId = sendAccId,
                        toAddr = toRows.map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
                        ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                        subject = subject.text, bodyHtml = bodyHtml,
                        attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                        readReceipt = readReceipt, cardMode = cardMode,
                        status = if (o.status == "FAILED" || o.status == "SENDING") "DRAFT" else o.status,
                        scheduledAt = o.scheduledAt, error = "", updatedAt = System.currentTimeMillis()))
                }
                draftId
            } else {
                db.outbox().insert(OutboxItem(
                    accountId = sendAccId,
                    toAddr = toRows.map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
                    ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                    subject = subject.text, bodyHtml = bodyHtml,
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
    BackHandler(enabled = shouldAskSave) { showExit = true }

    val imagePick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> scope.launch(Dispatchers.IO) {
            val f = copyUriToFile(ctx, u, "img_${System.currentTimeMillis()}")
            val mime = ctx.contentResolver.getType(u) ?: guessMime(f.name)
            val cid = "img${System.currentTimeMillis()}"
            val size = f.length()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val ai = AttInfo(f.name, f.absolutePath, mime, true, cid)
                atts = atts + ai
                userEdited = true
                // 图片直接显示到正文光标处
                insertIntoBody(BodyBlock.Img(nextBlockId(), ai))
                toast = "图片已插入正文(大小 ${fmtSize(size)})，点击图片可预览或删除"
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
                userEdited = true
                toast = "附件已添加(大小 ${fmtSize(size)})，将作为文件发送"
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
            userEdited = true
            toast = "录音已添加(${mm}分${ss}秒，${fmtSize(target.length())})，点击可试听"
        } else recFile?.delete()
        recFile = null
    }

    fun removeAtt(a: AttInfo) {
        atts = atts - a
        // 从正文中移除对应图片块
        blocks.removeAll { it is BodyBlock.Img && it.att.cid == a.cid }
    }

    fun doSend(scheduledAt: Long?) {
        val rcpt = toRows.map { it.trim() }.filter { it.isNotBlank() }
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
            val bodyHtml = blocksToHtml()
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
                    toast = "正在发送，成功或失败将以提示音通知"
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
                    if (shouldAskSave) showExit = true else nav.popBackStack()
                }) { Icon(Icons.Default.Close, "返回") } },
                actions = {
                    IconButton(onClick = { showLink = true }) { Icon(Icons.Default.Link, "插入超链接(插到光标处)") }
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
            // 发件账号选择
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
                        }
                    }
                    HorizontalDivider()
                }
            }
            // 收件人：一行一个邮箱，可添加/删除多行
            item {
                Column {
                    toRows.forEachIndexed { i, _ ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == 0) "收件人" else "", fontSize = 14.sp, modifier = Modifier.width(56.dp))
                            TextField(value = toRows[i], onValueChange = { toRows[i] = it; userEdited = true },
                                placeholder = { Text("输入邮箱地址", fontSize = 13.sp) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                isError = toRows[i].isNotBlank() && !emailRegex.matches(toRows[i].trim()),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                            if (toRows.size > 1) IconButton(onClick = { toRows.removeAt(i) }) {
                                Icon(Icons.Default.RemoveCircleOutline, "删除此收件人", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { toRows.add("") }) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text(" 添加收件人", fontSize = 13.sp)
                        }
                        TextButton(onClick = { showContactMenu = !showContactMenu }) {
                            Icon(Icons.Default.Contacts, null, Modifier.size(16.dp)); Text(" 从联系人选", fontSize = 13.sp)
                        }
                    }
                    if (showContactMenu) {
                        if (contacts.isEmpty()) Text("还没有联系人，可在「加联系人」或联系人页添加", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp))
                        contacts.take(10).forEach { c ->
                            Text("· ${c.remark.ifBlank { c.email }} <${c.email}>", fontSize = 13.sp,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val blankIdx = toRows.indexOfFirst { it.isBlank() }
                                    if (blankIdx >= 0) toRows[blankIdx] = c.email else toRows.add(c.email)
                                    showContactMenu = false
                                }.padding(start = 56.dp, top = 6.dp, bottom = 6.dp))
                        }
                        HorizontalDivider()
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showCc = !showCc }) { Text(if (showCc) "隐藏抄送/密送" else "抄送/密送", fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("已读回执", fontSize = 13.sp)
                    Switch(checked = readReceipt, onCheckedChange = { readReceipt = it; userEdited = true })
                    Text("名片", fontSize = 13.sp)
                    Switch(checked = cardMode, onCheckedChange = { cardMode = it })
                    TextButton(onClick = { showCardPreview = true }, contentPadding = PaddingValues(4.dp)) { Text("预览", fontSize = 12.sp) }
                }
                Text("名片内容在「设置→点击邮箱账号→名片(资料卡)信息」中编辑", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
            if (showCc) {
                item { FieldRow("抄送", cc, { cc = it; userEdited = true }) }
                item { FieldRow("密送", bcc, { bcc = it; userEdited = true }) }
            }
            item {
                TextField(value = subject, onValueChange = { subject = it; userEdited = true },
                    placeholder = { Text("主题") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                HorizontalDivider()
            }
            // 正文：块式编辑(文本/图片/链接)；正文块默认更高，方便写长内容
            items(blocks, key = { it.id }) { b ->
                when (b) {
                    is BodyBlock.T -> TextField(
                        value = b.tv,
                        onValueChange = { i -> val idx = blocks.indexOfFirst { it.id == b.id }; if (idx >= 0) blocks[idx] = BodyBlock.T(b.id, i); userEdited = true },
                        placeholder = { Text("正文内容…", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 180.dp)
                            .onFocusChanged { if (it.isFocused) focusBlockId = b.id },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    is BodyBlock.Img -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        AsyncImage(model = File(b.att.path), contentDescription = b.att.name,
                            modifier = Modifier.fillMaxWidth().clickable { editImg = b },
                            contentScale = ContentScale.FillWidth)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正文图片 · 点击预览", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = { editImg = b }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("删除", fontSize = 12.sp, color = Color(0xFFEF6C00)) }
                        }
                    }
                    is BodyBlock.Lk -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("🔗 ${b.txt}", color = Color(0xFF1565C0), fontSize = 15.sp,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable { editLink = b })
                        IconButton(onClick = { editLink = b }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, "编辑链接", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { blocks.remove(b) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "删除链接", Modifier.size(16.dp), tint = Color(0xFFEF6C00))
                        }
                    }
                }
            }
            // 附件列表(在工具栏之前展示，工具栏下沉到底部，正文区域更大)
            items(fileAtts) { a ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { previewAtt = a }) {
                    Icon(
                        when {
                            a.mime.startsWith("video/") -> Icons.Default.PlayCircle
                            a.mime.startsWith("audio/") -> Icons.Default.Headphones
                            else -> Icons.Default.InsertDriveFile
                        }, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(a.name, fontSize = 13.sp, maxLines = 1)
                        val sz = File(a.path).takeIf { it.exists() }?.length() ?: 0L
                        Text(buildString {
                            append("点击${if (a.mime.startsWith("audio/")) "试听" else "预览"}")
                            if (sz > 0) append(" · ${fmtSize(sz)}")
                            if (a.dur > 0) append(" · 时长 ${a.dur / 60}分${a.dur % 60}秒")
                        }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { removeAtt(a) }) { Text("移除", color = Color(0xFFEF6C00)) }
                }
            }
            // 图片/文件按钮放在最后(靠下)，让正文输入区域更宽敞
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { imagePick.launch("image/*") }) { Icon(Icons.Default.Image, null, Modifier.size(18.dp)); Text(" 图片") }
                    TextButton(onClick = { filePick.launch("*/*") }) { Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)); Text(" 文件") }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }

        // 正文图片：全屏预览 / 删除
        editImg?.let { b ->
            AlertDialog(onDismissRequest = { editImg = null },
                title = { Text(b.att.name, fontSize = 15.sp) },
                text = {
                    AsyncImage(model = File(b.att.path), contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
                },
                confirmButton = { TextButton(onClick = { removeImgBlock(b); editImg = null; toast = "已从正文中删除这张图片" }) { Text("从正文删除", color = Color(0xFFEF6C00)) } },
                dismissButton = { TextButton(onClick = { editImg = null }) { Text("关闭") } })
        }

        // 链接：编辑 / 删除
        editLink?.let { lk ->
            var lt by remember(lk.id) { mutableStateOf(TextFieldValue(lk.txt)) }
            var lu by remember(lk.id) { mutableStateOf(TextFieldValue(lk.url)) }
            var linkErr by remember { mutableStateOf<String?>(null) }
            AlertDialog(onDismissRequest = { editLink = null },
                title = { Text("编辑超链接") },
                text = {
                    Column {
                        TextField(value = lt, onValueChange = { lt = it }, label = { Text("显示的文字") })
                        TextField(value = lu, onValueChange = { lu = it }, label = { Text("链接地址") })
                        linkErr?.let { Text(it, fontSize = 12.sp, color = Color(0xFFEF6C00)) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val u = lu.text.trim()
                        val okUrl = Regex("^https?://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*$").matches(u)
                        when {
                            lt.text.isBlank() -> linkErr = "请填写要显示的文字"
                            !okUrl -> linkErr = "链接地址无效：必须以 http:// 或 https:// 开头且域名可访问"
                            else -> {
                                val idx = blocks.indexOfFirst { it.id == lk.id }
                                if (idx >= 0) blocks[idx] = BodyBlock.Lk(lk.id, lt.text.trim(), u)
                                editLink = null
                            }
                        }
                    }) { Text("保存") }
                },
                dismissButton = { Row {
                    TextButton(onClick = { blocks.remove(lk); editLink = null; toast = "链接已删除" }) { Text("删除链接", color = Color(0xFFEF6C00)) }
                    TextButton(onClick = { editLink = null }) { Text("关闭") }
                } })
        }

        // 附件/录音预览
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
                            Text(if (a.mime.startsWith("audio/")) "点击下方「播放」试听录音" else "此类型暂不支持内嵌预览", fontSize = 13.sp)
                        }
                        val sz = File(a.path).takeIf { it.exists() }?.length() ?: 0L
                        if (sz > 0) Text("大小：${fmtSize(sz)}${if (a.dur > 0) " · 时长 ${a.dur / 60}分${a.dur % 60}秒" else ""}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            player?.release()
                            player = MediaPlayer().apply {
                                setAudioAttributes(AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                setDataSource(a.path); prepare(); start()
                                setOnCompletionListener { it.release(); player = null }
                            }
                        }.onFailure { toast = "播放失败：${it.message}" }
                    }) { Text("播放") }
                },
                dismissButton = { TextButton(onClick = {
                    runCatching { player?.stop(); player?.release() }; player = null; previewAtt = null
                }) { Text("关闭") } })
        }

        // 名片预览
        if (showCardPreview && acc != null) {
            val cardHtml = remember(acc.id, acc.vcard, acc.avatarPath) {
                SendWorker.cardBlock(acc).replace(
                    "cid:${SendWorker.CARD_AVATAR_CID}",
                    acc.avatarPath?.let { "file://$it" } ?: "noavatar")
            }
            AlertDialog(onDismissRequest = { showCardPreview = false },
                title = { Text("名片预览(收件人将看到的效果)") },
                text = {
                    AndroidView(factory = { vctx ->
                        android.webkit.WebView(vctx).apply { settings.javaScriptEnabled = false }
                    }, update = { wv ->
                        wv.loadDataWithBaseURL(null,
                            "<html><body style=\"font-family:sans-serif\">$cardHtml</body></html>",
                            "text/html", "utf-8", null)
                    }, modifier = Modifier.fillMaxWidth().height(220.dp))
                },
                confirmButton = { TextButton(onClick = { showCardPreview = false; nav.navigate("editAccount/${acc.id}") }) { Text("去编辑名片") } },
                dismissButton = { TextButton(onClick = { showCardPreview = false }) { Text("关闭") } })
        }

        // 插入超链接(插到光标处)
        if (showLink) {
            var lt by remember { mutableStateOf(TextFieldValue("")) }
            var lu by remember { mutableStateOf(TextFieldValue("")) }
            var linkErr by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { showLink = false },
                title = { Text("插入超链接") },
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
                            !okUrl -> linkErr = "链接地址无效：请输入可访问的网址(如 https://www.example.com)"
                            else -> {
                                insertIntoBody(BodyBlock.Lk(nextBlockId(), lt.text.trim(), u))
                                userEdited = true
                                showLink = false
                                toast = "链接已插入正文光标处，显示为蓝色高亮，点击可编辑或删除"
                            }
                        }
                    }) { Text("插入到光标位置") }
                },
                dismissButton = { TextButton(onClick = { showLink = false }) { Text("取消") } }
            )
        }
        if (showSchedule) {
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

fun escXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

/** 单段纯文本 → HTML：转义、换行、[文字](链接)、自动高亮真实URL */
fun plainToHtml(plain: String): String {
    val esc = escXml(plain)
    val mdLink = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    val url = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+")
    var html = mdLink.replace(esc) { mr -> "<a href=\"${mr.groupValues[2]}\">${mr.groupValues[1]}</a>" }
    val parts = Regex("(<a href=\"[^\"]+\">[^<]*</a>)").split(html)
    val out = parts.joinToString("") { seg ->
        if (seg.startsWith("<a href=")) seg
        else url.replace(seg) { mr ->
            val u = mr.value.trimEnd('.', ',', ')', '，', '。')
            "<a href=\"$u\">${u}</a>"
        }
    }
    return out.replace("\n", "<br/>")
}

/** HTML → 正文块列表：图片块(cid) / 链接块(a 标签) / 文本块 */
fun htmlToBlocks(html: String, atts: List<AttInfo>): MutableList<BodyBlock> {
    val out = mutableListOf<BodyBlock>()
    val tagRe = Regex("<img src=\"cid:([^\"]+)\"[^>]*>|<a href=\"([^\"]+)\">([^<]*)</a>")
    var last = 0
    fun addText(seg: String) {
        val plain = htmlToPlain(seg)
        if (plain.isNotBlank()) out.add(BodyBlock.T(nextBlockId(), TextFieldValue(plain)))
    }
    for (mr in tagRe.findAll(html)) {
        addText(html.substring(last, mr.range.first))
        val cid = mr.groupValues[1]
        if (cid.isNotBlank()) {
            atts.firstOrNull { it.cid == cid }?.let { out.add(BodyBlock.Img(nextBlockId(), it)) }
        } else {
            out.add(BodyBlock.Lk(nextBlockId(),
                mr.groupValues[3].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"),
                mr.groupValues[2].replace("&amp;", "&")))
        }
        last = mr.range.last + 1
    }
    addText(html.substring(last))
    if (out.isEmpty()) out.add(BodyBlock.T(nextBlockId(), TextFieldValue("")))
    return out
}

fun htmlToPlain(html: String): String = html
    .replace(Regex("<img[^>]*>"), "")
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
