package com.rogan.cloudmail.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import java.io.File
import java.util.*

/* ================== 正文块模型 ==================
   正文由「文本块 / 图片块 / 链接块 / 卡片块」顺序组成：
   - 图片块：正文里直接显示图片本身，点击可全屏预览或从正文删除
   - 链接块：正文里显示蓝色高亮链接文字，点击可编辑或删除；发送时真实生成 <a> 标签
   - 卡片块：插入一张预设样式的 HTML 卡片(可填标题/正文/链接)，编辑时预览效果，
     收件人在 QQ/163/Outlook 等邮箱里看到的是同一张卡片(内联样式，兼容性好)
   插入位置 = 当前光标所在文本块的光标处 */
sealed class BodyBlock {
    abstract val id: Long
    // color: 空串=默认黑色；font: 空串=默认字体，其他值为通用字体族(serif/monospace/cursive/fantasy/sans-serif)
    data class T(override val id: Long, val tv: TextFieldValue, val color: String = "", val font: String = "") : BodyBlock()
    data class Img(override val id: Long, val att: AttInfo) : BodyBlock()
    data class Lk(override val id: Long, val txt: String, val url: String) : BodyBlock()
    data class Card(override val id: Long, val html: String) : BodyBlock()
}

private var blockSeq = System.currentTimeMillis()
private fun nextBlockId(): Long = ++blockSeq

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ComposeScreen(nav: NavHostController, draftId: Long, presetTo: String, forwardId: Long = -1, reeditId: Long = -1) {
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
    // 插入图片/链接/卡片后自动把光标定位到新块后面的文本块：用户可直接继续输入文字，
    // 不用再手动点一下下面才知道哪里能输入(autoFocusId 匹配的文本块会自动请求焦点并弹键盘)
    // 实时记录真实光标位置(哪个文本块+第几个字)：每次输入/聚焦都更新。
    // 之前只记 focusBlockId，点插入链接弹窗后焦点丢到弹窗上，插入位置回退逻辑可能算错，
    // 导致链接插到离正文几行远的地方；记录真实光标后插入位置永远精确
    var cursorBlockId by remember { mutableStateOf<Long?>(null) }
    var cursorPos by remember { mutableStateOf(0) }
    var autoFocusId by remember { mutableStateOf<Long?>(null) }
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
    var showCardTpl by remember { mutableStateOf(false) }
    var editCard by remember { mutableStateOf<BodyBlock.Card?>(null) }
    // 插入图片/卡片/链接后自动把光标落到它下面新加的文本块，直接就能接着打字
    var pendingFocusId by remember { mutableStateOf<Long?>(null) }
    // 发送前的硬校验用弹窗(而不是底部 Snackbar)：键盘弹出时 Snackbar 会被输入法挡住，
    // 用户看不到「请填写收件人」这类提示，误以为点了发送没反应
    var alertMsg by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    // 内置字体(子集化后的衬线/楷体，各约1.7MB)：编辑区即时用真实字体渲染，
    // 不再依赖设备系统有没有对应中文字体(很多国产 ROM 没有 serif/cursive 中文，选了看不出变化)
    val fontSerifFam = remember { assetFontFamily(ctx, "serif_sc.otf", androidx.compose.ui.text.font.FontFamily.Serif) }
    val fontKaiFam = remember { assetFontFamily(ctx, "kai_sc.otf", androidx.compose.ui.text.font.FontFamily.Cursive) }

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
        } else if (reeditId > 0) {
            // 再次编辑(已发送)：收件人/抄送/主题/正文原样带入，可改可删，再点发送就是重新发一封
            db.messages().byId(reeditId)?.let { m ->
                toRows.clear(); toRows.addAll(m.toAddr.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { listOf("") })
                if (m.ccAddr.isNotBlank()) { cc = TextFieldValue(m.ccAddr); showCc = true }
                subject = TextFieldValue(m.subject)
                sendAccId = m.accountId
                if (!m.loaded) {
                    db.accounts().byId(m.accountId)?.let { a ->
                        runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.rogan.cloudmail.mail.MailService.loadFull(ctx, a, m, db) } }
                    }
                }
                val fresh = db.messages().byId(reeditId) ?: m
                atts = AttInfo.fromJson(fresh.attachmentsJson)
                blocks.clear()
                blocks.addAll(htmlToBlocks(fresh.bodyHtml, atts))
                // 名片已作为 <!--VCARD--> 块还原在正文里，关闭名片开关，避免再发时附上第二张名片
                cardMode = false
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

    /** 在光标处插入块：把光标所在文本块在光标处拆开，中间插入新块。
     *  - 光标位置用实时记录的 cursorBlockId/cursorPos(精确到字)，不再依赖弹窗打开后已丢失的焦点状态
     *  - 拆分后若光标后面只剩空白，不再生成空文本块(否则链接下面会多出几行空白)
     *  - 插入完成后自动把光标定位到新块后面的文本块(没有就在新块后面补一个)，直接可输入文字 */
    fun insertIntoBody(b: BodyBlock) {
        val cid = cursorBlockId
        val idx = if (cid != null) blocks.indexOfFirst { it.id == cid && it is BodyBlock.T } else -1
        var newTextId = -1L
        if (idx >= 0) {
            val t = blocks[idx] as BodyBlock.T
            val pos = cursorPos.coerceIn(0, t.tv.text.length)
            val before = t.tv.text.substring(0, pos)
            val after = t.tv.text.substring(pos)
            when {
                before.isEmpty() && after.isEmpty() -> {
                    // 光标所在的空正文块直接换成插入的内容，下面补一段同款样式的新正文
                    blocks[idx] = b
                    newTextId = nextBlockId()
                    blocks.add(idx + 1, BodyBlock.T(newTextId, TextFieldValue(""), t.color, t.font))
                }
                before.isEmpty() -> {
                    blocks[idx] = b
                    newTextId = nextBlockId()
                    blocks.add(idx + 1, BodyBlock.T(newTextId, TextFieldValue(after), t.color, t.font))
                }
                else -> {
                    blocks[idx] = BodyBlock.T(t.id, TextFieldValue(before), t.color, t.font)
                    blocks.add(idx + 1, b)
                    // 光标后面本来就有空正文块时直接复用它，避免反复插入后堆出一串空块
                    val nxt = blocks.getOrNull(idx + 2)
                    if (after.isBlank() && nxt is BodyBlock.T && nxt.tv.text.isBlank()) {
                        newTextId = nxt.id
                    } else {
                        newTextId = nextBlockId()
                        blocks.add(idx + 2, BodyBlock.T(newTextId, TextFieldValue(after), t.color, t.font))
                    }
                }
            }
        } else {
            // 找不到光标记录(如刚打开页面还没点进正文)：
            // - 正文只有唯一空块 → 插入内容排在最上面，正文块跟在后面
            // - 已有正文 → 插到最后一段非空正文紧后面，紧贴正文内容
            val onlyBlank = blocks.size == 1 && blocks[0] is BodyBlock.T && (blocks[0] as BodyBlock.T).tv.text.isBlank()
            if (onlyBlank) {
                blocks[0] = b
                newTextId = nextBlockId()
                blocks.add(1, BodyBlock.T(newTextId, TextFieldValue("")))
            } else {
                val lastIdx = blocks.indexOfLast { it is BodyBlock.T && it.tv.text.isNotBlank() }
                val at = if (lastIdx >= 0) lastIdx + 1 else blocks.size
                blocks.add(at, b)
                val nxt = blocks.getOrNull(at + 1)
                if (nxt is BodyBlock.T && nxt.tv.text.isBlank()) {
                    newTextId = nxt.id
                } else {
                    newTextId = nextBlockId()
                    blocks.add(at + 1, BodyBlock.T(newTextId, TextFieldValue("")))
                }
            }
        }
        // 插入后自动光标：找到新块后面的第一个文本块；没有就在新块正下方补一个(继承旁边的字体/字色)，
        // 并把光标定位过去 → 插入图片/卡片/链接后立刻就能继续打字，不用再找哪里能点
        run {
            val bi = blocks.indexOfFirst { it.id == b.id }
            var target: BodyBlock.T? = null
            if (bi >= 0) {
                for (i in bi + 1 until blocks.size) { val x = blocks[i]; if (x is BodyBlock.T) { target = x; break } }
                if (target == null) {
                    val prev = blocks.getOrNull(bi - 1) as? BodyBlock.T
                    val nt = BodyBlock.T(nextBlockId(), TextFieldValue(""), prev?.color ?: "", prev?.font ?: "")
                    blocks.add(bi + 1, nt); target = nt
                }
                target.let { t2 -> autoFocusId = t2!!.id; cursorBlockId = t2!!.id; cursorPos = 0 }
            }
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
                // 相同颜色/字体才合并，否则保留前块的样式(边界文本少，几乎不影响)
                blocks[i] = BodyBlock.T(a.id, TextFieldValue(merged, androidx.compose.ui.text.TextRange(merged.length)),
                    a.color.ifBlank { n.color }, a.font.ifBlank { n.font })
                blocks.removeAt(i + 1)
            } else i++
        }
        // 2) 只剩图片/链接/卡片块时补一个文本块方便继续输入
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
            is BodyBlock.T -> if (b.tv.text.isBlank()) null else {
                // 彩色字体/字体样式：包一层 span 内联样式，对方邮箱原样显示同样颜色/字体；
                // font-family 写真实字体名+各平台回退(宋体/楷体)，不再只写泛型名导致对方端看不出变化
                val style = buildString {
                    if (b.color.isNotBlank()) append("color:${b.color};")
                    val ff = fontFamilyCss(b.font)
                    if (ff.isNotBlank()) append("font-family:$ff;")
                }
                if (style.isBlank()) plainToHtml(b.tv.text)
                else "<span style=\"$style\">${plainToHtml(b.tv.text)}</span>"
            }
            is BodyBlock.Img -> "<img src=\"cid:${b.att.cid}\" style=\"max-width:96%;border-radius:8px\"/>"
            is BodyBlock.Lk -> "<a href=\"${b.url}\">${escXml(b.txt)}</a>"
            is BodyBlock.Card -> b.html
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
                    is BodyBlock.T -> { append('t'); append(b.tv.text); append(b.color); append(b.font); append('|') }
                    is BodyBlock.Img -> { append('i'); append(b.att.path); append('|') }
                    is BodyBlock.Lk -> { append('l'); append(b.url); append('|') }
                    is BodyBlock.Card -> { append('c'); append(b.html.hashCode()); append('|') }
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
            toast = "录音已添加(${mm}分${ss}秒)"
        } else recFile?.delete()
        recFile = null
    }

    // 录音/附件「保存」：用系统存储位置选择器(SAF)把文件复制到用户选的目录，
    // 保存成功后文件既留在邮件附件里、也有了一份本地副本
    var saveAtt by remember { mutableStateOf<AttInfo?>(null) }
    val saveDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val a = saveAtt; saveAtt = null
        if (uri != null && a != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    File(a.path).inputStream().use { it.copyTo(os) }
                }
            }.onSuccess { toast = "录音已保存到所选位置" }
                .onFailure { toast = "保存失败：${it.message}" }
        }
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
        // 收件人为空：必须弹窗明确提示(不能只靠底部 Snackbar，键盘挡住用户根本看不见)
        if (rcpt.isEmpty()) { alertMsg = "请填写收件人邮箱地址"; return }
        val bad = rcpt.filter { !emailRegex.matches(it) }
        if (bad.isNotEmpty()) { alertMsg = "这些不是有效的邮箱地址：${bad.joinToString("、")}，请检查后重试"; return }
        if (cc.text.isNotBlank()) {
            val badCc = cc.text.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() && !emailRegex.matches(it) }
            if (badCc.isNotEmpty()) { alertMsg = "抄送地址无效：${badCc.joinToString("、")}"; return }
        }
        if (bcc.text.isNotBlank()) {
            val badBcc = bcc.text.split(",", ";", "，", "；").map { it.trim() }.filter { it.isNotBlank() && !emailRegex.matches(it) }
            if (badBcc.isNotEmpty()) { alertMsg = "密送地址无效：${badBcc.joinToString("、")}"; return }
        }
        if (sendAccId <= 0 || acc == null) { alertMsg = "请先在侧边栏「添加邮箱账号」里添加一个邮箱账号"; return }
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

    // 提示条放在 Scaffold 的 snackbarHost 槽位(不会被顶栏遮住，之前“未填收件人点发送没提示”就是这个原因)。
    // 时长缩短：Snackbar 默认一挂就是 4 秒左右，插入图片/卡片后挡着正文没法马上接着打字；
    // 改成自绘小黑条 1.5 秒自动消失，想继续写正文直接点正文区域即可
    Scaffold(
        snackbarHost = {
            LaunchedEffect(toast) {
                if (toast != null) { delay(1500); toast = null }
            }
            toast?.let { t ->
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE6323232),
                    shadowElevation = 4.dp, modifier = Modifier.padding(bottom = 72.dp)) {
                    Text(t, color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
                }
            }
        },
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
        // imePadding：弹出键盘时整个编辑区自动上移，键盘永远不挡住正在输入的正文光标处。
        // 键盘弹出时再把正在输入的正文块滚进可视区：之前插入图片/卡片后正文被推到键盘下面，
        // 继续打字看不见自己输了什么；现在键盘一弹出/切换输入块就自动滚到正在编辑的那一块
        val bodyListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime
            .getBottom(androidx.compose.ui.platform.LocalDensity.current)
        // 键盘跟随(彻底版)：不止键盘弹出时滚一次，而是每次输入/光标落到某个正文块时都检查：
        // 该块整块不在可视区→按 key 精确滚到它；块底部被键盘/屏幕裁掉→把被裁的部分滚上来。
        // 这样不管是纯文字、图片/卡片/链接下面接着输入、还是多块混排，输入内容永远在键盘上方可见。
        val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
        fun ensureBlockVisible(id: Long) {
            val info = bodyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
            if (info == null) {
                val idx = blocks.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    // 固定区块数=账号/收件人/名片行/主题/工具行共 5 个，抄送/密送打开时再多 2 个
                    val header = 5 + if (showCc) 2 else 0
                    scrollScope.launch { runCatching { bodyListState.animateScrollToItem(idx + header) } }
                }
            } else {
                val overflow = info.offset + info.size + 60 - bodyListState.layoutInfo.viewportSize.height
                if (overflow > 0) scrollScope.launch { runCatching { bodyListState.animateScrollBy(overflow.toFloat()) } }
            }
        }
        LaunchedEffect(imeBottom, focusBlockId) {
            if (imeBottom > 0 && focusBlockId != null) {
                androidx.compose.runtime.withFrameNanos { }
                ensureBlockVisible(focusBlockId!!)
            }
        }
        LazyColumn(state = bodyListState, modifier = Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
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
                // FlowRow 自动换行：不管抄送/密送有没有打开，名片开关/「预览」都完整可见可点，
                // 不再因为一行挤不下被裁掉(之前预览按钮被挤出屏幕，用户以为只有打开抄送才能预览名片)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(onClick = { showCc = !showCc }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                        Text(if (showCc) "隐藏抄送/密送" else "抄送/密送", fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                    Text("已读回执", fontSize = 13.sp)
                    Switch(checked = readReceipt, onCheckedChange = { readReceipt = it; userEdited = true })
                    Spacer(Modifier.width(8.dp))
                    Text("名片", fontSize = 13.sp)
                    Switch(checked = cardMode, onCheckedChange = { cardMode = it })
                    TextButton(onClick = { showCardPreview = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                        Text("名片预览", fontSize = 13.sp) }
                }
                Text("名片内容在「设置→点击邮箱账号→名片(资料卡)信息」中编辑", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
            if (showCc) {
                item { FieldRow("抄送", cc, { cc = it; userEdited = true }) }
                item { FieldRow("密送", bcc, { bcc = it; userEdited = true }) }
            }
            item {
                // 主题行用 BasicTextField(不带 M3 TextField 的固定内边距)：固定 height(46.dp) 会把
                // 「主题」两个字纵向裁掉一半，BasicTextField 高度自适应，占位文字完整显示，行高保持紧凑
                androidx.compose.foundation.text.BasicTextField(
                    value = subject, onValueChange = { subject = it; userEdited = true },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    decorationBox = { inner ->
                        androidx.compose.foundation.layout.Box {
                            if (subject.text.isEmpty()) Text("主题", fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                            inner()
                        }
                    })
                HorizontalDivider()
            }
            // 常驻样式工具行(在正文上方，不用滑动就能看到)：图片/文件/卡片 + 字色/字体。
            // 之前放在页面最底部，用户根本不知道下面还有这些功能
            item { ComposeStyleToolbar(
                blocks = blocks, cursorBlockId = cursorBlockId,
                onPickImage = { imagePick.launch("image/*") },
                onPickFile = { filePick.launch("*/*") },
                onShowCard = { showCardTpl = true },
                onUserEdit = { userEdited = true }) }
            // 正文块(文本/图片/链接/卡片)
            // 正文：块式编辑(文本/图片/链接)；正文块默认更高，方便写长内容
            items(blocks, key = { it.id }) { b ->
                when (b) {
                    is BodyBlock.T -> {
                        // 本块的彩色字体/字体样式：编辑时即时生效，发送后对方看到一样的颜色/字体
                        val tvColor = if (b.color.isNotBlank()) {
                            runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(b.color)) }.getOrDefault(Color.Unspecified)
                        } else Color.Unspecified
                        val tvFont = when (b.font) {
                            "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                            "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                            "cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
                            "sans-serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                            else -> androidx.compose.ui.text.font.FontFamily.Default
                        }
                        // 插入图片/卡片/链接后自动把光标定位到这个文本块：直接可继续输入文字，
                        // 不用再手动点下面哪里能输入；点其他任意位置输入也仍然照常可用
                        val focusReq = remember { FocusRequester() }
                        androidx.compose.runtime.LaunchedEffect(autoFocusId) {
                            if (autoFocusId == b.id) {
                                runCatching { focusReq.requestFocus() }
                                if (autoFocusId == b.id) autoFocusId = null
                            }
                        }
                        TextField(
                        value = b.tv,
                        onValueChange = { i ->
                            val idx = blocks.indexOfFirst { it.id == b.id }
                            if (idx >= 0) blocks[idx] = BodyBlock.T(b.id, i, b.color, b.font)
                            // 每次输入都记录真实光标位置：插入链接时永远精确落在这里
                            cursorBlockId = b.id; cursorPos = i.selection.end
                            userEdited = true
                            runCatching { ensureBlockVisible(b.id) } // 每次输入都跟随：光标所在行永远在键盘上方
                        },
                        placeholder = {
                            // 空占位文字只在唯一正文块显示：插入链接/图片后拆出的空块不再显示
                            // “正文内容…”，避免看起来像链接下面又多了一个正文
                            if (blocks.size == 1) Text("正文内容…", fontSize = 14.sp) else Spacer(Modifier.height(0.dp))
                        },
                        // 所见即所得：正文里的 [文字](链接) 显示成蓝色下划线的「文字」(括号/地址不显示)，
                        // 发送时仍真实转成 <a> 超链接。光标/选区偏移在 MdLinkOffsetMap 中精确映射，编辑不受影响
                        visualTransformation = mdLinkVisual(),
                        modifier = Modifier.fillMaxWidth().defaultMinSize(
                            // 只有唯一正文块(空邮件初始)才撑满高度；链接/图片插入后拆出的相邻文本块保持紧凑，
                            // 视觉上仍是一个连续正文区，不再出现“两个大正文块”的观感
                            minHeight = if (blocks.size == 1) 180.dp else 40.dp)
                            .focusRequester(focusReq)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    focusBlockId = b.id
                                    cursorBlockId = b.id
                                    cursorPos = b.tv.selection.end
                                }
                            },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            // 底部指示线透明：多块正文(草稿中的图片/链接)之间不再显示一条横线，视觉上就是一个连续的正文区
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent),
                        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                            color = if (tvColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tvColor,
                            fontFamily = when (b.font) {
                                "serif" -> fontSerifFam; "cursive" -> fontKaiFam; else -> tvFont }))
                    }
                    // 编辑视图与发送效果一致：图片块就只显示图片本身，下方不再挂“删除/预览”文字行；
                    // 点击图片=全屏预览(弹窗里可删除)，长按图片=直接从正文删除。
                    // 点击热区严格限定在图片自身的显示范围：图片后面/下面的空白属于正文输入区，
                    // 之前热区跟着整行/整块走，点图片下面两三行也会弹出图片编辑，挡住了接着打字
                    is BodyBlock.Img -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center) {
                        // 图片块加圆角+淡描边(只在编辑区显示)：一眼看出“这是一张图片块”不是文字，
                        // 点它=编辑图片，点旁边空白=继续打字
                        AsyncImage(model = File(b.att.path), contentDescription = b.att.name,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0x33808080), RoundedCornerShape(10.dp))
                                .combinedClickable(
                                onClick = { editImg = b },
                                onLongClick = { removeImgBlock(b); toast = "已从正文中删除这张图片" }),
                            contentScale = ContentScale.FillWidth)
                    }
                    // 编辑视图与发送效果一致：链接块就只显示蓝色下划线的链接文字(发送后就是这个样子)；
                    // 点击=用内置浏览器打开链接，长按=弹出编辑/删除(编辑弹窗里可改文字地址或删除)
                    // 链接块加淡蓝底+圆角(只在编辑区显示)：一眼看出“这是一个链接块”不是正文文字
                    is BodyBlock.Lk -> Text(b.txt, color = Color(0xFF1565C0), fontSize = 15.sp,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .background(Color(0x141565C0), RoundedCornerShape(8.dp))
                            .combinedClickable(
                            onClick = {
                                runCatching { nav.navigate("browser/" + android.net.Uri.encode(b.url)) }.onFailure {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(b.url))) }
                                }
                            },
                            onLongClick = { editLink = b }))
                    // 编辑视图与发送效果一致：卡片块就只显示卡片本身，下方不再挂“重新编辑/删除”文字行；
                    // 点击卡片=弹出操作菜单(重新编辑/删除)，长按卡片=直接重新编辑。
                    // 点击热区精确到卡片本身：WebView 用 wrap-content 自身量内容高(之前手动量一次、
                    // 量偏大/失败时卡片下方多出一截“空白却点得出菜单”的区域)，内容到哪热区就到哪，
                    // 卡片下面第一行就能直接打字
                    is BodyBlock.Card -> {
                        var cardMenu by remember(b.id) { mutableStateOf(false) }
                        val previewHtml = buildString {
                            var h = b.html
                            atts.filter { it.cid.isNotBlank() && it.path.isNotBlank() && File(it.path).exists() }
                                .forEach { a -> h = h.replace("cid:${a.cid}", "file://${a.path}") }
                            append("<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/>${fontFaceCss()}</head>")
                            append("<body style='margin:0;font-family:sans-serif'>")
                            append(h)
                            append("</body></html>")
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            AndroidView(factory = { vctx ->
                                android.webkit.WebView(vctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.allowFileAccess = true
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    // 不抢触摸/焦点：首次点卡片的响应更快，也不会被 WebView 吞掉第一次点击
                                    isFocusable = false; isClickable = false; isLongClickable = false
                                    isHapticFeedbackEnabled = false
                                    // 卡片里的链接一律走 APP 内置浏览器(可复制链接/再用系统浏览器打开)，
                                        // 不再一点就跳出 APP
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: android.webkit.WebView, u: String): Boolean {
                                            return if (u.startsWith("http://") || u.startsWith("https://")) {
                                                runCatching { nav.navigate("browser/" + android.net.Uri.encode(u)) }
                                                true
                                            } else false
                                        }
                                    }
                                }
                            }, update = { wv ->
                                // 内容没变就不重复 loadData(避免每次重组整页重载，点卡片时更跟手)
                                if (wv.tag != previewHtml) {
                                    wv.tag = previewHtml
                                    wv.loadDataWithBaseURL("file:///android_asset/", previewHtml, "text/html", "utf-8", null)
                                }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp))
                            Box(Modifier.matchParentSize().combinedClickable(
                                onClick = { cardMenu = true },
                                onLongClick = { editCard = b }))
                        }
                        androidx.compose.material3.DropdownMenu(expanded = cardMenu, onDismissRequest = { cardMenu = false }) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("✎ 重新编辑") },
                                onClick = { cardMenu = false; editCard = b })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("🗑 删除卡片") },
                                onClick = { cardMenu = false; blocks.remove(b); normalizeBlocks(); toast = "卡片已删除" })
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
                    if (a.mime.startsWith("audio/")) TextButton(onClick = {
                        saveAtt = a; saveDocLauncher.launch(a.name)
                    }) { Text("保存", fontSize = 13.sp) }
                    TextButton(onClick = { removeAtt(a) }) { Text("移除", color = Color(0xFFEF6C00)) }
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
            // 按真实内容识别文件类型(读文件头魔数)：有些文件没有后缀/系统没认出类型，
            // 只凭 mime 会把图片当成“未知文件”弹“重新播放”；现在图片/视频/音频都能认出来
            val sniffed = runCatching { sniffKind(a.path) }.getOrNull() ?: ""
            val isImage = a.mime.startsWith("image/") || sniffed == "image"
            val isVideo = a.mime.startsWith("video/") || sniffed == "video"
            val isAudio = a.mime.startsWith("audio/") || sniffed == "audio"
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
                        if (isImage) {
                            // 图片(含没写后缀但实际是图片的文件)：直接预览这张图
                            AsyncImage(model = File(a.path), contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit)
                        } else {
                            Text(when {
                                isAudio && player != null -> "正在播放中…"
                                isAudio -> "播放已结束，可点下方「重新播放」再听一次"
                                isVideo -> "视频文件可点下方「用其他应用打开」播放"
                                else -> "此类型暂不支持内嵌预览"
                            }, fontSize = 13.sp)
                        }
                        val sz = File(a.path).takeIf { it.exists() }?.length() ?: 0L
                        if (sz > 0) Text("大小：${fmtSize(sz)}${if (a.dur > 0) " · 时长 ${a.dur / 60}分${a.dur % 60}秒" else ""}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    // 播放按钮只给音频/视频(图片弹“重新播放”是错的)；其他类型给“用其他应用打开”
                    when {
                        isAudio -> TextButton(onClick = {
                            if (player != null) {
                                runCatching { player?.stop(); player?.release() }; player = null
                            } else startPlay(a)
                        }) { Text(if (player != null) "停止" else "重新播放") }
                        else -> TextButton(onClick = {
                            runCatching { ShareUtils.openFile(ctx, File(a.path), a.mime.ifBlank { "application/octet-stream" }) }
                                .onFailure { toast = "打开失败：${it.message}" }
                            previewAtt = null
                        }) { Text("用其他应用打开") }
                    }
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
        // 插入卡片：选一种样式，填标题/正文/链接，生成一张 HTML 卡片插到光标处。
        // 内联样式渲染，QQ/163/Outlook/Gmail 等主流邮箱都能原样显示；
        // 弹窗里实时预览：输入什么立刻看到什么，预览用的就是真实发送的那段 HTML
        if (showCardTpl || editCard != null) {
            val editing = editCard
            var tpl by remember(editing?.id) { mutableStateOf(if (editing != null) cardTplOf(editing.html) else 0) }
            var ct by remember(editing?.id) { mutableStateOf(TextFieldValue(cardField(editing?.html, "t"))) }
            var cb by remember(editing?.id) { mutableStateOf(TextFieldValue(cardField(editing?.html, "b"))) }
            var clt by remember(editing?.id) { mutableStateOf(TextFieldValue(cardField(editing?.html, "lt"))) }
            var clu by remember(editing?.id) { mutableStateOf(TextFieldValue(cardField(editing?.html, "lu"))) }
            // 链接文字颜色跟随模板背景：深色模板用白色，浅色模板用深色，
            // 之前一律用白色，浅色背景上链接文字“看不见”(但点击有效)，看起来像链接丢了
            fun linkHtmlNow(): String =
                if (clt.text.isNotBlank() && clu.text.trim().startsWith("http"))
                    "<a href=\"${clu.text.trim()}\" style=\"color:${cardLinkColor(tpl)};text-decoration:underline\">${escXml(clt.text.trim())}</a>" else ""
            AlertDialog(onDismissRequest = { showCardTpl = false; editCard = null },
                title = { Text(if (editing == null) "插入卡片" else "重新编辑卡片") },
                text = {
                    // 样式选择和实时预览同屏：上面选样式/填内容(内容多时内部滚动)，预览固定在下方，不用来回滑动找。
                    // 整个弹窗限高，保证小屏手机上「插入/保存」按钮不会被挤出屏幕
                    Column(Modifier.heightIn(max = 460.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 8 种样式分两行排开(不再横向滑动)，一眼能看全所有样式
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            (0..7).forEach { i ->
                                FilterChip(selected = tpl == i, onClick = { tpl = i },
                                    label = { Text(cardName(i), fontSize = 12.sp) })
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextField(value = ct, onValueChange = { ct = it }, placeholder = { Text("卡片标题") },
                                singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                            TextField(value = cb, onValueChange = { cb = it },
                                placeholder = { Text("卡片正文") },
                                minLines = 4, textStyle = MaterialTheme.typography.bodySmall)
                            TextField(value = clt, onValueChange = { clt = it }, placeholder = { Text("链接文字(留空则卡片不带链接)") },
                                singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                            if (clt.text.isNotBlank())
                                TextField(value = clu, onValueChange = { clu = it }, placeholder = { Text("链接地址 https://…") },
                                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                        }
                        // 实时预览：和真实发送的 HTML 完全同一段，所见即对方所得
                        Text("实时预览", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AndroidView(factory = { vctx ->
                            android.webkit.WebView(vctx).apply {
                                settings.javaScriptEnabled = false
                                settings.allowFileAccess = true
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                // 预览里的链接默认用 APP 内置浏览器打开(可复制链接/再用系统浏览器打开)，
                                // 之前直接跳系统浏览器，看邮件时突兀地切出去
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, u: String): Boolean {
                                        return if (u.startsWith("http://") || u.startsWith("https://")) {
                                            nav.navigate("browser/" + android.net.Uri.encode(u)); true
                                        } else false
                                    }
                                }
                            }
                        }, update = { wv ->
                            wv.loadDataWithBaseURL("file:///android_asset/",
                                "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'/>${fontFaceCss()}</head>" +
                                "<body style='margin:0;font-family:sans-serif'>" +
                                buildCardHtml(tpl, escXml(ct.text.trim()), escXml(cb.text.trim()).replace("\n", "<br/>"), linkHtmlNow()) +
                                "</body></html>", "text/html", "utf-8", null)
                        }, modifier = Modifier.fillMaxWidth().height(170.dp))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (ct.text.isBlank() && cb.text.isBlank() && clt.text.isBlank()) {
                            toast = "卡片还是空的：标题/正文/链接任意填一项就能插入"; return@TextButton }
                        val linkHtml = if (clt.text.isNotBlank() && clu.text.trim().startsWith("http"))
                            "<a href=\"${clu.text.trim()}\" style=\"color:${cardLinkColor(tpl)};text-decoration:underline\">${escXml(clt.text.trim())}</a>" else ""
                        val html = buildCardHtml(tpl, escXml(ct.text.trim()), escXml(cb.text.trim()).replace("\n", "<br/>"), linkHtml)
                        val wrapped = "<!--CARD:$tpl-->$html<!--/CARD-->"
                        if (editing != null) {
                            val idx = blocks.indexOfFirst { it.id == editing.id }
                            if (idx >= 0) blocks[idx] = BodyBlock.Card(editing.id, wrapped)
                        } else {
                            insertIntoBody(BodyBlock.Card(nextBlockId(), wrapped))
                        }
                        userEdited = true
                        showCardTpl = false
                        editCard = null
                        toast = if (editing == null) "卡片已插入到光标处，发送后对方看到同样的卡片" else "卡片已更新"
                    }) { Text(if (editing == null) "插入到光标位置" else "保存") }
                },
                dismissButton = { TextButton(onClick = { showCardTpl = false; editCard = null }) { Text("取消") } }
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
            // 每秒刷新一次当前时间。宽限 61 秒：弹窗默认时间=当前时间的整分(秒被清 0)，
            // 实际当前秒最多 59 秒，之前只留 30 秒宽限，打开弹窗就报“早于当前时间超过 1 分钟”
            // 且确定一直灰着；现在 61 秒内都算有效，默认状态直接可点「确定定时发送」(= 立即发送)
            var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); nowTick = System.currentTimeMillis() } }
            val target: Long = run {
                val c = Calendar.getInstance()
                c.set(yr, mo - 1, dy, hh, mi, 0)
                c.set(Calendar.MILLISECOND, 0)
                c.timeInMillis
            }
            val invalid = !validDate(mo, dy)
            val inPast = target < nowTick - 61_000
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
                        if (!invalid && inPast) Text("所选时间早于当前时间，请重选(可选今天稍晚/明天/后天等)；不改时间直接点确定 = 立即发送",
                            fontSize = 12.sp, color = Color(0xFFEF6C00))
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
        // 发送前的硬性校验(收件人为空/地址格式不对/没账号)：用弹窗提示，一定看得见
        alertMsg?.let { am ->
            AlertDialog(
                onDismissRequest = { alertMsg = null },
                title = { Text("无法发送") },
                text = { Text(am, fontSize = 14.sp) },
                confirmButton = { TextButton(onClick = { alertMsg = null }) { Text("知道了") } })
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
    }
}

fun escXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** 读文件头魔数识别真实类型(有些附件没写后缀/系统认不出 mime)：返回 "image"/"video"/"audio"/""(认不出) */
fun sniffKind(path: String): String = runCatching {
    java.io.RandomAccessFile(path, "r").use { raf ->
        val b = ByteArray(16); val n = raf.read(b)
        if (n < 4) return@runCatching ""
        fun at(off: Int, s: String) = n >= off + s.length && String(b, off, s.length, Charsets.US_ASCII) == s
        when {
            b[0] == 0xFF.toByte() && (b[1] == 0xD8.toByte()) -> "image"                    // JPEG
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "image"                      // PNG
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image"              // GIF
            b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image"              // BMP
            String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && at(8, "WEBP") -> "image"       // WebP
            at(4, "ftyp") -> "video"                                                       // MP4/MOV 等容器
            at(0, "ID3") || (b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) -> "audio" // MP3
            String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && at(8, "WAVE") -> "audio"       // WAV
            String(b, 0, 4, Charsets.US_ASCII) == "OggS" -> "audio"                        // OGG
            at(0, "flac") || (b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte()) -> "audio"
            else -> ""
        }
    }
}.getOrDefault("")

fun fontLabel(f: String) = when (f) {
    // 按钮上直接显示所选字体的真实名字(不带头缀/括号)：默认时显示「默认字体」，
    // 选了哪个立刻变成哪个的名字，一眼看清当前用的字体
    "sans-serif" -> "黑体"; "serif" -> "宋体"; "monospace" -> "等宽"
    "cursive" -> "楷体"
    else -> "默认字体"
}

/** 字体选项 → 发送 HTML 里的 CSS font-family(真实字体名+各平台回退：收件方电脑/手机上
 *  宋体/楷体都能正确落到对应风格，不再因为只写泛型名看不出变化) */
fun fontFamilyCss(f: String) = when (f) {
    "serif" -> "'Noto Serif SC','Songti SC','SimSun',serif"
    "cursive" -> "'LXGW WenKai','KaiTi','STKaiti','Kaiti SC',cursive"
    "monospace" -> "'Sarasa Mono SC','Menlo','Consolas',monospace"
    "sans-serif" -> "'MiSans','HarmonyOS Sans SC','PingFang SC','Microsoft YaHei',sans-serif"
    else -> ""
}

/** 给 WebView 正文包一层 @font-face：收到的邮件/卡片预览/长图都用 APP 内置的衬线/楷体
 *  字体渲染，和发送时编辑区看到的完全一致(不依赖手机系统有没有这些字体)。
 *  字体文件在 assets/fonts/ 下，配合 loadDataWithBaseURL("file:///android_asset/",…) 相对路径加载 */
fun fontFaceCss(): String = "<style>" +
    "@font-face{font-family:'Noto Serif SC';src:url('fonts/serif_sc.otf')}" +
    "@font-face{font-family:'LXGW WenKai';src:url('fonts/kai_sc.otf')}" +
    "</style>"

/** 从 assets 加载内置字体文件(首次使用时复制到私有目录，Compose 的 Font 可以从文件读)。
 *  加载失败时回退到系统对应字体族，保证不崩 */
fun assetFontFamily(ctx: Context, assetName: String, fallback: androidx.compose.ui.text.font.FontFamily): androidx.compose.ui.text.font.FontFamily {
    return runCatching {
        val dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
        val f = File(dir, assetName)
        if (!f.exists() || f.length() == 0L)
            ctx.assets.open("fonts/$assetName").use { ins -> f.outputStream().use { ins.copyTo(it) } }
        androidx.compose.ui.text.font.Font(f)
            .let { androidx.compose.ui.text.font.FontFamily(it) }
    }.getOrDefault(fallback)
}

/** 常驻样式工具行(正文上方)：图片/文件/卡片 + 字色色板 + 字体。
 *  字色直观化：色球下方显示当前颜色名字，选中的色球带描边圈，一眼看出选了哪个色 */
private val PALETTE = listOf(
    "" to "默认黑", "#D32F2F" to "红色", "#E64A19" to "橙色", "#F9A825" to "黄色",
    "#2E7D32" to "绿色", "#1565C0" to "蓝色", "#6A1FB0" to "紫色", "#795548" to "棕色")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeStyleToolbar(blocks: MutableList<BodyBlock>, cursorBlockId: Long?,
                        onPickImage: () -> Unit, onPickFile: () -> Unit, onShowCard: () -> Unit,
                        onUserEdit: () -> Unit) {
    // 当前光标所在文本块；没记录时用最后一个文本块
    val curT = blocks.firstOrNull { it.id == cursorBlockId && it is BodyBlock.T } as? BodyBlock.T
        ?: blocks.lastOrNull { it is BodyBlock.T } as? BodyBlock.T
    var fontMenu by remember { mutableStateOf(false) }
    Column {
        // 第 1 行：图片/文件/卡片 + 字体(右侧)。整行固定宽度内放得下，不用横向滑动
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPickImage, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Icon(Icons.Default.Image, null, Modifier.size(16.dp)); Text(" 图片", fontSize = 12.sp) }
            TextButton(onClick = onPickFile, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp)); Text(" 文件", fontSize = 12.sp) }
            TextButton(onClick = onShowCard, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Icon(Icons.Default.Style, null, Modifier.size(16.dp)); Text(" 卡片", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { fontMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    enabled = curT != null) {
                    // 直接显示当前选中的字体名(冒号去掉、名称完整)，不用猜冒号后面藏了什么
                    Text(fontLabel(curT?.font ?: ""), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1)
                }
                DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                    listOf("" to "默认字体", "sans-serif" to "黑体", "serif" to "宋体",
                        "monospace" to "等宽", "cursive" to "楷体").forEach { (v, name) ->
                        DropdownMenuItem(text = { Text(name, fontSize = 13.sp,
                            fontWeight = if ((curT?.font ?: "") == v) FontWeight.Bold else FontWeight.Normal) },
                            onClick = {
                                val idx = blocks.indexOfFirst { it.id == (curT?.id ?: -1L) }
                                if (idx >= 0 && blocks[idx] is BodyBlock.T) {
                                    val t = blocks[idx] as BodyBlock.T
                                    blocks[idx] = BodyBlock.T(t.id, t.tv, t.color, v)
                                }
                                fontMenu = false
                                onUserEdit()
                            })
                    }
                }
            }
        }
        // 第 2 行：8 个色球 + 当前颜色名。缩紧间距后一屏内全部可见，
        // 不再需要向左滑动才能看到最右边的字体/颜色(观感更整齐)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            PALETTE.forEach { (c, name) ->
                val selected = (curT?.color ?: "") == c
                Box(Modifier.padding(horizontal = 2.dp)
                    .size(24.dp)
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.CircleShape) else Modifier)
                    .padding(2.dp)
                    .background(if (c.isBlank()) MaterialTheme.colorScheme.onSurface
                        else androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(c)),
                        androidx.compose.foundation.shape.CircleShape)
                    .clickable {
                        val idx = blocks.indexOfFirst { it.id == (curT?.id ?: -1L) }
                        if (idx >= 0 && blocks[idx] is BodyBlock.T) {
                            val t = blocks[idx] as BodyBlock.T
                            blocks[idx] = BodyBlock.T(t.id, t.tv, c, t.font)
                        }
                        onUserEdit()
                    })
            }
            Spacer(Modifier.width(6.dp))
            // 直接显示当前选中的颜色名(完整、不带冒号)，一眼知道现在用的是什么颜色
            Text(PALETTE.firstOrNull { it.first == (curT?.color ?: "") }?.second ?: "自定义",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** 卡片 HTML(内联样式)：主流邮箱兼容。
 *  样式 index：0=蓝紫渐变 1=简约蓝条 2=深色 3=暖橙渐变 4=清新绿 5=粉紫渐变 6=米金描边 7=极简黑框 */
fun cardName(tpl: Int) = when (tpl) {
    0 -> "蓝紫渐变"; 1 -> "简约蓝条"; 2 -> "深色"; 3 -> "暖橙渐变"; 4 -> "清新绿"
    5 -> "粉紫渐变"; 6 -> "米金描边"; else -> "极简黑框"
}

/** 卡片链接文字颜色跟随模板：深色背景用白色、浅色背景用深色/蓝色，保证链接文字肉眼可见 */
fun cardLinkColor(tpl: Int) = when (tpl) {
    1 -> "#1565C0"; 6 -> "#EF6C00"; 7 -> "#1565C0"; else -> "#ffffff"
}

fun buildCardHtml(tpl: Int, title: String, body: String, linkHtml: String): String {
    // 链接文字颜色必须跟卡片底色有对比度：
    // 之前所有样式都统一写白色，浅色卡片(简约蓝条/米金描边/极简黑框)上白字白底 → 链接文字看不见，
    // 只能点到位置才跳转(用户反馈“链接文字不显示但点击有效”)
    val linkColor = when (tpl) { 1, 6, 7 -> "#1565C0"; else -> "#ffffff" }
    val link = linkHtml.replace("color:#ffffff", "color:$linkColor")
    val inner = buildString {
        if (title.isNotBlank()) append("<div style='font-size:18px;font-weight:bold;line-height:26px'>")
            .append(title).append("</div>")
        if (body.isNotBlank()) append("<div style='font-size:14px;line-height:24px;margin-top:8px'>")
            .append(body).append("</div>")
        if (link.isNotBlank()) append("<div style='margin-top:14px'>")
            .append(link).append("</div>")
    }
    val table = { bg: String -> """<table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse"><tr><td style="$bg;font-family:sans-serif">$inner</td></tr></table>""" }
    return when (tpl) {
        0 -> table("background:linear-gradient(135deg,#1565C0,#6A1FB0);border-radius:14px;padding:20px 22px;color:#ffffff")
        1 -> table("background:#f5f7fa;border:1px solid #e0e0e0;border-left:4px solid #1565C0;border-radius:10px;padding:18px 20px;color:#333333")
        2 -> table("background:#263238;border-radius:14px;padding:20px 22px;color:#eceff1")
        3 -> table("background:linear-gradient(135deg,#EF6C00,#E53935);border-radius:14px;padding:20px 22px;color:#ffffff")
        4 -> table("background:linear-gradient(135deg,#2E7D32,#66BB6A);border-radius:14px;padding:20px 22px;color:#ffffff")
        5 -> table("background:linear-gradient(135deg,#EC407A,#8E24AA);border-radius:14px;padding:20px 22px;color:#ffffff")
        6 -> table("background:#FFF8E1;border:1px solid #F9A825;border-left:5px solid #F9A825;border-radius:10px;padding:18px 20px;color:#5D4037")
        else -> table("background:#ffffff;border:2px solid #212121;border-radius:6px;padding:18px 20px;color:#212121")
    }
}

/** 从已保存的卡片 HTML 里还原模板号/各字段(重新编辑用)；不是卡片 HTML 时返回默认值 */
fun cardTplOf(html: String): Int = Regex("<!--CARD:(\\d)-->").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0
fun cardField(html: String?, key: String): String {
    if (html == null) return ""
    val inner = html.substringAfter("<!--", "").let { if (it.contains("-->") && html.contains("CARD:")) html.substringAfter("-->").substringBefore("<!--/CARD") else html }
    val plain = htmlToPlain(inner)
    return when (key) {
        "t" -> Regex("font-size:18px[^>]*>([^<]*)").find(inner)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
        "b" -> plain.substringAfter(Regex("font-size:18px[^>]*>([^<]*)").find(inner)?.groupValues?.get(1) ?: "").trim()
        "lt" -> Regex("<a [^>]*>([^<]*)</a>").find(inner)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
        "lu" -> Regex("<a href=\"([^\"]+)\"").find(inner)?.groupValues?.get(1)?.replace("&amp;", "&") ?: ""
        else -> ""
    }
}

@Composable
internal fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
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

/** HTML → 正文块列表：图片块(cid) / 链接块(a 标签) / 卡片块(CARD 标记) / 名片块(VCARD 标记) /
 *  彩色字体块(span style color/font-family) / 文本块 */
fun htmlToBlocks(html: String, atts: List<AttInfo>): MutableList<BodyBlock> {
    val out = mutableListOf<BodyBlock>()
    val tagRe = Regex("<!--CARD:(\\d)-->((?s).*?)<!--/CARD-->|<!--VCARD-->((?s).*?)<!--/VCARD-->|" +
            "<img src=\"cid:([^\"]+)\"[^>]*>|<a href=\"([^\"]+)\">([^<]*)</a>|" +
            "<span style=\"([^\"]*)\">([^<]*)</span>")
    var last = 0
    fun addText(seg: String, color: String = "", font: String = "") {
        val plain = htmlToPlain(seg)
        if (plain.isNotBlank()) out.add(BodyBlock.T(nextBlockId(), TextFieldValue(plain), color, font))
    }
    for (mr in tagRe.findAll(html)) {
        val g = mr.groupValues
        addText(html.substring(last, mr.range.first))
        when {
            g[2].isNotEmpty() -> out.add(BodyBlock.Card(nextBlockId(), mr.value))
            g[3].isNotEmpty() -> out.add(BodyBlock.Card(nextBlockId(), mr.value))
            g[4].isNotEmpty() -> {
                atts.firstOrNull { it.cid == g[4] }?.let { out.add(BodyBlock.Img(nextBlockId(), it)) }
            }
            g[5].isNotEmpty() -> out.add(BodyBlock.Lk(nextBlockId(),
                g[6].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"),
                g[5].replace("&amp;", "&")))
            g[7].isNotEmpty() -> addText(g[8],
                Regex("color:([^;\"]+)").find(g[7])?.groupValues?.get(1)?.trim() ?: "",
                cssToFontKey(Regex("font-family:([^;\"]+)").find(g[7])?.groupValues?.get(1)?.trim() ?: ""))
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

/** HTML 里的 CSS font-family(真实字体名或泛型名) → 编辑器字体选项 key(serif/cursive/monospace/sans-serif)。
 *  再次编辑已发送邮件时用：发送出去的是 'Noto Serif SC','Songti SC',serif 这类真实字体名，要归一化回来 */
fun cssToFontKey(raw: String): String {
    val r = raw.trim().lowercase()
    if (r.isBlank()) return ""
    return when {
        "sans-serif" in r || "misans" in r || "harmonyos" in r || "pingfang" in r || "yahei" in r -> "sans-serif"
        "mono" in r || "menlo" in r || "consolas" in r -> "monospace"
        "wenkai" in r || "kaiti" in r || "cursive" in r -> "cursive"
        "serif" in r || "simsun" in r || "songti" in r -> "serif"
        else -> ""
    }
}

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
