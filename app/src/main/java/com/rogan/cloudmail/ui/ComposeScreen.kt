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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.*
import com.rogan.cloudmail.mail.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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

    // 自动填入的收件人(联系人/草稿/转发/预置)：不算用户亲手输入的内容，返回时不应触发“保存草稿”提醒
    val autoTo = remember { mutableStateListOf<String>() }
    remember(presetTo) { if (presetTo.isNotBlank()) autoTo.add(presetTo); Unit }
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
                autoTo.clear(); autoTo.addAll(rows) // 草稿里带入的收件人同样不算手动输入
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

    /** 删除图片/链接块后把正文重新合并成一个连续输入区：
     *  相邻的文本块合二为一，多余的空文本块去掉，避免出现“两个正文内容中间一条线”。 */
    fun normalizeBlocks() {
        // 1) 合并相邻文本块
        var i = 0
        while (i < blocks.size - 1) {
            val a = blocks[i]; val n = blocks[i + 1]
            if (a is BodyBlock.T && n is BodyBlock.T) {
                val merged = a.tv.text + n.tv.text
                blocks[i] = BodyBlock.T(a.id, TextFieldValue(merged, androidx.compose.ui.text.TextRange(merged.length)))
                blocks.removeAt(i + 1)
            } else i++
        }
        // 2) 只剩图片/链接块时补一个文本块方便继续输入
        if (blocks.none { it is BodyBlock.T }) blocks.add(BodyBlock.T(nextBlockId(), TextFieldValue("")))
    }

    fun removeImgBlock(b: BodyBlock.Img) {
        blocks.remove(b)
        atts = atts.filter { it.cid != b.att.cid }
        normalizeBlocks()
    }

    fun removeLinkBlock(b: BodyBlock.Lk) {
        blocks.remove(b)
        normalizeBlocks()
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

    // 用户亲手输入的内容(不含联系人/草稿/转发自动填入的收件人)：
    // 输入后全部删空(正文/主题/收件人/附件/链接/图片)则视为没输入过内容：返回不弹「保存草稿」提醒、也不自动存草稿；
    // 只有仍保留着至少一项手动输入的内容时才提醒/存草稿
    val hasManualContent = cc.text.isNotBlank() || bcc.text.isNotBlank() ||
            subject.text.isNotBlank() || blocks.any { it !is BodyBlock.T || it.tv.text.isNotBlank() } ||
            atts.isNotEmpty() || toRows.any { r ->
                val v = r.trim()
                v.isNotBlank() && toRows.count { it.trim() == v } > autoTo.count { it.trim() == v }
            }

    // 自动保存产生的草稿 id：首次自动保存创建草稿行后记下来，后续都更新这一行，避免重复插入多份草稿
    var autosaveDraftId by remember { mutableStateOf(-1L) }
    var savedOnExit by remember { mutableStateOf(false) }

    // 是否弹「保存草稿」提醒：只有用户亲手输入过且当前仍保留着内容才算：
    // - 从联系人选的收件人 / 切换发件账号 / 打开已有草稿未再编辑：都不提醒，直接返回
    // - 输入后全选删空再返回：不算输入过内容，不提醒
    // - 手动输入的收件人/主题/正文/附件/录音仍保留着：提醒(防误丢内容)
    val shouldAskSave = userEdited && hasManualContent

    // 改为 suspend：不再 runBlocking 卡主线程。之前自动保存草稿用 runBlocking 在主线程同步写库，
    // 停止输入 1.5 秒后主线程被卡住，此时点发送/滑动列表都会没反应(点击被吞)，这是“点发送没反应/滑动顿一下”的根因
    suspend fun saveDraftNow(): Long {
        if (!loadedDraft || !hasContent) return -1L
        return try {
            val bodyHtml = blocksToHtml()
            val effId = if (draftId > 0) draftId else autosaveDraftId
            val existing = if (effId > 0) db.outbox().byId(effId) else null
            if (existing != null) {
                db.outbox().update(existing.copy(
                    accountId = sendAccId,
                    toAddr = toRows.map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
                    ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                    subject = subject.text, bodyHtml = bodyHtml,
                    attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                    readReceipt = readReceipt, cardMode = cardMode,
                    status = if (existing.status == "FAILED" || existing.status == "SENDING") "DRAFT" else existing.status,
                    scheduledAt = existing.scheduledAt, error = "", updatedAt = System.currentTimeMillis()))
                effId
            } else {
                val newId = db.outbox().insert(OutboxItem(
                    accountId = sendAccId,
                    toAddr = toRows.map { it.trim() }.filter { it.isNotBlank() }.joinToString(","),
                    ccAddr = cc.text.trim(), bccAddr = bcc.text.trim(),
                    subject = subject.text, bodyHtml = bodyHtml,
                    attachmentsJson = atts.joinToString(",", "[", "]") { it.toJson() },
                    readReceipt = readReceipt, cardMode = cardMode,
                    scheduledAt = null, status = "DRAFT", updatedAt = System.currentTimeMillis()))
                autosaveDraftId = newId
                newId
            }
        } catch (e: Exception) { -1L }
    }

    // 进程被清理也能保住内容：内容停止变化 1.5 秒后自动存入草稿库(后台被杀前已落库，
    // 重新打开 APP 侧边栏「草稿/待发送」里原样保留，可继续编辑或直接发送)。未发送的邮件只要编辑过就一定会出现在草稿里。
    LaunchedEffect(Unit) {
        var lastSig = ""
        androidx.compose.runtime.snapshotFlow {
            buildString {
                append(toRows.joinToString("|")); append('#')
                append(cc.text); append('#'); append(bcc.text); append('#'); append(subject.text); append('#')
                blocks.forEach { b -> when (b) {
                    is BodyBlock.T -> { append('t'); append(b.tv.text); append('|') }
                    is BodyBlock.Img -> { append('i'); append(b.att.path); append('|') }
                    is BodyBlock.Lk -> { append('l'); append(b.url); append('|') }
                } }
                append('#'); atts.forEach { append(it.name); append('@'); append(it.path); append('|') }
                append('#'); append(sendAccId); append('#'); append(readReceipt); append(cardMode)
            }
        }.collect { sig ->
            if (loadedDraft && !savedOnExit && sig != lastSig && sig.isNotEmpty()) {
                lastSig = sig
                kotlinx.coroutines.delay(1500) // 停止输入 1.5 秒后落库，避免每敲一个字都写库
                if (userEdited && hasManualContent && sendAccId > 0) runCatching { saveDraftNow() }
            }
        }
    }

    // 离开页面/进程被回收前自动保存草稿，保证内容不丢
    DisposableEffect(Unit) {
        onDispose {
            // 只有用户手动输入过内容才自动存草稿；从联系人/转发自动带入的内容不算，返回时不误存也不误弹
            // 必须在后台线程写库：之前用 runBlocking 在主线程同步写库，切换到其他 APP 再回来时
            // 主线程可能正被这次写库占住，点发送的点击全被吞掉(看起来就是“完全没反应”)
            if (!savedOnExit && loadedDraft && userEdited && hasManualContent && sendAccId > 0)
                kotlinx.coroutines.GlobalScope.launch { runCatching { saveDraftNow() } }
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
            toast = "录音已添加(${mm}分${ss}秒，${fmtSize(target.length())})，点击即可试听"
        } else recFile?.delete()
        recFile = null
    }

    fun removeAtt(a: AttInfo) {
        atts = atts - a
        // 从正文中移除对应图片块，并把正文重新合并为一个连续输入区
        val had = blocks.removeAll { it is BodyBlock.Img && it.att.cid == a.cid }
        if (had) normalizeBlocks()
    }

    var sending by remember { mutableStateOf(false) }

    // 发送看门狗：万一发送流程被异常卡住(数据库/系统繁忙)，45 秒后自动恢复发送按钮并提示，
    // 避免“发送图标永远变成转圈、再点没反应”卡死
    LaunchedEffect(sending) {
        if (sending) {
            kotlinx.coroutines.delay(45000)
            if (sending) { sending = false; savedOnExit = false; toast = "发送超时，请检查网络后重试" }
        }
    }

    fun doSend(scheduledAt: Long?) {
        // 发送中重复点击：给出可见提示而不是静默忽略(之前静默 return 会被误解为“点了没反应”)
        if (sending) { toast = "正在发送中，请稍候…"; return }
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
        // 立即给出“发送中”反馈(右上角转圈)，避免“点了发送没反应”；整个过程 try/catch，任何异常都会提示原因而不是默默无反应
        sending = true
        savedOnExit = true
        scope.launch(Dispatchers.IO) {
          try {
            val bodyHtml = blocksToHtml()
            // 发送前如果自动保存已经建过草稿行，直接更新那一行，避免草稿里残留一份重复的旧内容
            val effId = if (draftId > 0) draftId else autosaveDraftId
            val base = if (effId > 0) db.outbox().byId(effId) else null
            val item = if (base != null) {
                base.copy(
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
                    // 发送状态由系统通知栏真实展示(正在发送/发送成功/失败)，不再在 APP 内弹文字提示
                    kotlinx.coroutines.delay(600); nav.popBackStack()
                }
            }
          } catch (e: Throwable) {
            // 捕获 Throwable：任何异常(包括罕见系统错误)都必须恢复按钮并给出提示，绝不静默无反应
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                sending = false
                savedOnExit = false
                toast = "发送失败：${e.message ?: "未知错误"}，请重试"
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
                    if (sending) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                    } else IconButton(onClick = { doSend(null) }) { Icon(Icons.Default.Send, "发送") }
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
        // imePadding：弹出键盘时整个编辑区自动上移，键盘永远不挡住正在输入的正文光标处
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
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
                                    autoTo.add(c.email) // 从联系人选的不算手动输入
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
                        // 所见即所得：正文里的 [文字](链接) 显示成蓝色下划线的「文字」(括号/地址不显示)，
                        // 发送时仍真实转成 <a> 超链接。光标/选区偏移在 MdLinkOffsetMap 中精确映射，编辑不受影响
                        visualTransformation = mdLinkVisual(),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(
                            // 只有唯一正文块(空邮件初始)才撑满高度；链接/图片插入后拆出的相邻文本块保持紧凑，
                            // 视觉上仍是一个连续正文区，不再出现“两个大正文块”的观感
                            minHeight = if (blocks.size == 1) 180.dp else 40.dp)
                            .onFocusChanged { if (it.isFocused) focusBlockId = b.id },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            // 底部指示线透明：多块正文(草稿中的图片/链接)之间不再显示一条横线，视觉上就是一个连续的正文区
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent))
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
                        IconButton(onClick = { removeLinkBlock(b) }, modifier = Modifier.size(28.dp)) {
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
                    TextButton(onClick = { removeLinkBlock(lk); editLink = null; toast = "链接已删除" }) { Text("删除链接", color = Color(0xFFEF6C00)) }
                    TextButton(onClick = { editLink = null }) { Text("关闭") }
                } })
        }

        // 附件/录音预览。录音/音频附件点击即直接播放(不再需要弹窗里再点一次「播放」)
        previewAtt?.let { a ->
            val isAudio = a.mime.startsWith("audio/")
            fun startPlay(p: AttInfo) {
                runCatching {
                    player?.release()
                    player = MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        setDataSource(p.path); prepare(); start()
                        setOnCompletionListener { it.release(); player = null }
                    }
                }.onFailure { toast = "播放失败：${it.message}" }
            }
            // 「点击试听」一键出声：点列表里的录音立刻开始播放，弹窗里显示播放状态，可停止/重播
            LaunchedEffect(a.path) { if (isAudio) startPlay(a) }
            AlertDialog(onDismissRequest = {
                runCatching { player?.stop(); player?.release() }; player = null; previewAtt = null
            }, title = { Text(a.name, fontSize = 15.sp) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (a.mime.startsWith("image/")) {
                            AsyncImage(model = File(a.path), contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                        } else {
                            Text(when {
                                isAudio && player != null -> "正在播放中…"
                                isAudio -> "播放已结束，可点下方「重新播放」再听一次"
                                else -> "此类型暂不支持内嵌预览"
                            }, fontSize = 13.sp)
                        }
                        val sz = File(a.path).takeIf { it.exists() }?.length() ?: 0L
                        if (sz > 0) Text("大小：${fmtSize(sz)}${if (a.dur > 0) " · 时长 ${a.dur / 60}分${a.dur % 60}秒" else ""}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (player != null) {
                            runCatching { player?.stop(); player?.release() }; player = null
                        } else startPlay(a)
                    }) { Text(if (player != null) "停止" else "重新播放") }
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
                                // 改回独立的链接块(发送时真实生成 <a>，收件人可点击)：
                                // 之前把 [文字](链接) 塞进文本块，输入法在映射边界插入文字会悄悄破坏标记，
                                // 导致发送后链接丢失；链接块所见即所得且发送路径稳定可靠
                                val blk = BodyBlock.Lk(nextBlockId(), lt.text.trim(), u)
                                insertIntoBody(blk)
                                userEdited = true
                                showLink = false
                                toast = "链接已插入到光标处(蓝色高亮)"
                            }
                        }
                    }) { Text("插入到光标位置") }
                },
                dismissButton = { TextButton(onClick = { showLink = false }) { Text("取消") } }
            )
        }
        if (showSchedule) {
            val cal = Calendar.getInstance()
            var yr by remember { mutableStateOf(cal.get(Calendar.YEAR)) }
            var mo by remember { mutableStateOf(cal.get(Calendar.MONTH) + 1) }
            var dy by remember { mutableStateOf(cal.get(Calendar.DAY_OF_MONTH)) }
            var hh by remember { mutableStateOf(cal.get(Calendar.HOUR_OF_DAY)) }
            var mi by remember { mutableStateOf(cal.get(Calendar.MINUTE)) }
            val maxYear = cal.get(Calendar.YEAR) + 5
            fun validDate(m: Int, d: Int): Boolean {
                val max = when (m) { 1,3,5,7,8,10,12 -> 31; 4,6,9,11 -> 30; else -> 29 }
                return d in 1..max
            }
            // 每秒刷新一次当前时间：之前只在校验输入变化时重算，"21:09 整分被认为早于当前 21:09:xx"
            // 且调整后提示不消失、确定按钮一直灰着；现在每秒重算 + 30 秒宽限，同一分钟内也能确定
            var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); nowTick = System.currentTimeMillis() } }
            val target: Long = run {
                val c = Calendar.getInstance()
                c.set(yr, mo - 1, dy, hh, mi, 0)
                c.set(Calendar.MILLISECOND, 0)
                c.timeInMillis
            }
            val invalid = !validDate(mo, dy)
            val inPast = target < nowTick - 30_000
            AlertDialog(
                onDismissRequest = { showSchedule = false },
                title = { Text("定时发送") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("目标时间：${fmtFull(target)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Stepper("年份", yr, cal.get(Calendar.YEAR), maxYear) { yr = it }
                        Stepper("月份", mo, 1, 12) { mo = it }
                        Stepper("日期", dy, 1, 31) { dy = it }
                        if (invalid) Text("$mo 月没有 $dy 日，请修改", fontSize = 12.sp, color = Color(0xFFEF6C00))
                        Stepper("小时", hh, 0, 23) { hh = it }
                        Stepper("分钟", mi, 0, 59) { mi = it }
                        if (!invalid && inPast) Text("所选时间早于当前时间超过 1 分钟，请重选(可选今天稍晚/明天/后天等)",
                            fontSize = 12.sp, color = Color(0xFFEF6C00))
                        Text("到点自动真实发送；APP 不打开(被清理后台)也会生效", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !invalid && !inPast, onClick = {
                        showSchedule = false
                        // 若所选时间已过(含宽限内的同分钟)，到点改为 1 分钟后发送，保证一定能发出
                        val t = if (target <= System.currentTimeMillis()) System.currentTimeMillis() + 60_000 else target
                        doSend(t)
                    }) { Text("确定定时发送") }
                },
                dismissButton = { TextButton(onClick = { showSchedule = false }) { Text("取消") } }
            )
        }
        if (showExit) {
            AlertDialog(
                onDismissRequest = { showExit = false },
                title = { Text("返回") },
                text = { Text("你已输入内容，离开前要保存草稿吗？(草稿在侧边栏「草稿/待发送」中，随时可继续编辑发送；即使 APP 被清理后台，草稿也会自动保留)") },
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

/** 正文里的 [文字](链接) 编辑区所见即所得变换：显示成蓝色下划线的「文字」，隐藏括号/地址。
 *  内部精确维护原始↔显示双向光标偏移映射，编辑、选区、插入均不受影响 */
fun mdLinkVisual(): VisualTransformation = VisualTransformation { tfv ->
    val src = tfv.text
    val re = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
    val b = AnnotatedString.Builder()
    val o2t = IntArray(src.length + 1)
    val t2o = IntArray(src.length + 1)
    var oi = 0   // 原始文本下标
    var ti = 0   // 显示文本下标
    for (m in re.findAll(src)) {
        // 链接前的普通文本逐字映射
        while (oi < m.range.first) {
            o2t[oi] = ti; t2o[ti] = oi
            b.append(src[oi]); oi++; ti++
        }
        // '['：原位置映射到链接文字开头(光标停在链接前)
        o2t[oi] = ti; oi++
        val labelStart = ti
        val label = m.groupValues[1]
        for (ch in label) { o2t[oi] = ti; t2o[ti] = oi; b.append(ch); oi++; ti++ }
        // 边界也必须显式写入：否则两个链接紧挨着时边界映射停留在默认值 0，光标会跳到开头
        t2o[ti] = oi
        b.addStyle(SpanStyle(color = Color(0xFF1565C0), textDecoration = TextDecoration.Underline), labelStart, ti)
        // ']','(',url,')' 全部隐藏：原位置都映射到链接文字结尾
        o2t[oi] = ti; oi++ // ']'
        o2t[oi] = ti; oi++ // '('
        repeat(m.groupValues[2].length) { o2t[oi] = ti; oi++ }
        o2t[oi] = ti; oi++ // ')'
        t2o[ti] = oi
    }
    while (oi < src.length) {
        o2t[oi] = ti; t2o[ti] = oi
        b.append(src[oi]); oi++; ti++
    }
    o2t[oi] = ti          // 原文末尾 → 显示末尾
    t2o[ti] = oi          // 显示末尾 → 原文末尾
    val om = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int = o2t[offset.coerceIn(0, src.length)]
        override fun transformedToOriginal(offset: Int): Int = t2o[offset.coerceIn(0, ti)]
    }
    TransformedText(b.toAnnotatedString(), om)
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
