package com.rogan.cloudmail.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.core.content.FileProvider
import com.rogan.cloudmail.data.Account
import com.rogan.cloudmail.data.AttInfo
import com.rogan.cloudmail.data.MailMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

/** 分享/导出工具：系统分享、导出 EML / HTML / 长图、复制摘要。
 *  导出的文件放在 cacheDir/exports 下，通过 FileProvider 分享给微信/QQ 等(不依赖任何服务器) */
object ShareUtils {

    private fun exportDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun safeName(m: MailMessage): String {
        val t = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(m.date))
        val base = (m.subject.ifBlank { "邮件" }).take(20).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        return "${base}_$t"
    }

    /** 复制邮件摘要/全部内容到剪贴板(主题+发件人+正文纯文本，直接可粘贴到微信/备忘录) */
    fun summaryText(m: MailMessage): String = buildString {
        append("【邮件】${m.subject.ifBlank { "(无主题)" }}\n")
        append("发件人：${m.fromName.ifBlank { m.fromAddr }} <${m.fromAddr}>\n")
        append("时间：${fmtFull(m.date)}\n")
        append("──────────────\n")
        append(htmlToPlain(m.bodyHtml).ifBlank { m.snippet })
    }

    fun copyToClipboard(ctx: Context, text: String, label: String = "cloudmail") {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    /** 系统分享：弹出微信/QQ 等分享面板，直接把邮件内容发过去 */
    fun shareText(ctx: Context, m: MailMessage) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, m.subject.ifBlank { "(无主题)" })
            putExtra(Intent.EXTRA_TEXT, summaryText(m))
        }
        ctx.startActivity(Intent.createChooser(i, "分享邮件"))
    }

    /** 导出 HTML 网页文件：样式与邮件正文一致(内联图片换成真实文件路径)，浏览器直接打开，完全离线 */
    suspend fun exportHtml(ctx: Context, m: MailMessage): File = withContext(Dispatchers.IO) {
        var html = m.bodyHtml.ifBlank { "<p>(无正文内容)</p>" }
        AttInfo.fromJson(m.attachmentsJson).filter { it.isInline && it.cid.isNotBlank() }.forEach { a ->
            if (a.path.isNotBlank() && File(a.path).exists()) html = html.replace("cid:${a.cid}", "file://${a.path}")
        }
        val full = """
            <html><head><meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>${escXml(m.subject.ifBlank { "(无主题)" })}</title>
            <style>body{font-family:sans-serif;max-width:760px;margin:0 auto;padding:16px;color:#222;word-break:break-word}
            img{max-width:100%;height:auto;border-radius:6px} a{color:#1565C0}
            .hdr{border-bottom:1px solid #e0e0e0;padding-bottom:10px;margin-bottom:14px;color:#666;font-size:14px}</style>
            </head><body>
            <div class="hdr"><b>${escXml(m.subject.ifBlank { "(无主题)" })}</b><br/>
            发件人：${escXml(m.fromName.ifBlank { m.fromAddr })} &lt;${escXml(m.fromAddr)}&gt;<br/>
            收件人：${escXml(m.toAddr)}<br/>${fmtFull(m.date)}</div>
            $html</body></html>
        """.trimIndent()
        val out = File(exportDir(ctx), safeName(m) + ".html")
        out.writeText(full)
        out
    }

    /** 导出 EML 标准邮件文件(Outlook/Thunderbird/Windows Mail 可直接打开)。
     *  内联图片转 base64 嵌入，正文样式与 APP 里看到的一致 */
    suspend fun exportEml(ctx: Context, m: MailMessage, acc: Account?): File = withContext(Dispatchers.IO) {
        val atts = AttInfo.fromJson(m.attachmentsJson)
        val boundary = "----cloudmail-boundary-${System.currentTimeMillis()}"
        val sb = StringBuilder()
        sb.append("Message-ID: <${m.messageId.ifBlank { "local-${System.currentTimeMillis()}@cloudmail" }}>\r\n")
        sb.append("Date: ${SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(Date(m.date))}\r\n")
        sb.append("From: ${mimeHeader(m.fromName.ifBlank { m.fromAddr })} <${m.fromAddr}>\r\n")
        if (m.toAddr.isNotBlank()) sb.append("To: ${mimeHeader(m.toAddr)}\r\n")
        if (m.ccAddr.isNotBlank()) sb.append("Cc: ${m.ccAddr}\r\n")
        sb.append("Subject: ${mimeHeader(m.subject.ifBlank { "(无主题)" })}\r\n")
        sb.append("MIME-Version: 1.0\r\n")
        sb.append("Content-Type: multipart/related; boundary=\"$boundary\"\r\n\r\n")

        // 正文 html 部分
        var html = m.bodyHtml.ifBlank { "<p>(无正文内容)</p>" }
        val inlineAtts = atts.filter { it.isInline && it.cid.isNotBlank() && File(it.path).exists() }
        inlineAtts.forEach { a -> /* cid 保留，图片在下方以 base64 内联资源出现 */ }
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/html; charset=utf-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n")
        sb.append(html.replace("\r\n", "\n").replace("\n", "\r\n"))
        sb.append("\r\n")

        // 内联图片(base64)
        inlineAtts.forEach { a ->
            val data = Base64.getEncoder().encodeToString(File(a.path).readBytes())
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: ${a.mime.ifBlank { "application/octet-stream" }}; name=\"${a.name}\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-ID: <${a.cid}>\r\n")
            sb.append("Content-Disposition: inline; filename=\"${a.name}\"\r\n\r\n")
            data.chunked(76).forEach { sb.append(it).append("\r\n") }
        }
        // 普通附件(base64)
        atts.filter { !it.isInline && File(it.path).exists() }.forEach { a ->
            val data = Base64.getEncoder().encodeToString(File(a.path).readBytes())
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: ${a.mime.ifBlank { "application/octet-stream" }}; name=\"${a.name}\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"${a.name}\"\r\n\r\n")
            data.chunked(76).forEach { sb.append(it).append("\r\n") }
        }
        sb.append("--$boundary--\r\n")
        val out = File(exportDir(ctx), safeName(m) + ".eml")
        out.writeText(sb.toString())
        out
    }

    private fun mimeHeader(s: String): String =
        if (s.all { it.code in 33..126 }) s else "=?UTF-8?B?${Base64.getEncoder().encodeToString(s.toByteArray())}?="

    /** 用系统分享面板把导出的文件发给微信/QQ(不需要服务器) */
    fun shareFile(ctx: Context, f: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "com.rogan.cloudmail.fileprovider", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "分享「${f.name}」"))
        }
    }

    /** 打开导出的文件(HTML 用浏览器打开) */
    fun openFile(ctx: Context, f: File, mime: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "com.rogan.cloudmail.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        }
    }

    /** 正文渲染成长图(清晰完整)：离屏 WebView 按内容高度渲染成一张 PNG */
    suspend fun exportLongImage(ctx: Context, m: MailMessage): File? = withContext(Dispatchers.Main) {
        val inlineAtts = AttInfo.fromJson(m.attachmentsJson).filter { it.isInline && it.cid.isNotBlank() }
        var html = m.bodyHtml.ifBlank { "<p style='color:#999'>(无正文内容)</p>" }
        inlineAtts.forEach { a ->
            if (a.path.isNotBlank() && File(a.path).exists()) html = html.replace("cid:${a.cid}", "file://${a.path}")
        }
        val full = """
            <html><head><meta charset="utf-8"/>
            <style>body{font-family:sans-serif;padding:16px;color:#222;word-break:break-word;background:#ffffff}
            img{max-width:100%;height:auto;border-radius:6px} a{color:#1565C0}</style></head>
            <body>$html</body></html>
        """.trimIndent()
        val out = File(exportDir(ctx), safeName(m) + ".png")
        suspendCancellableCoroutine { cont ->
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = false
            wv.settings.allowFileAccess = true
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            val density = ctx.resources.displayMetrics.density
            val widthPx = (720 * density).toInt().coerceAtMost(
                ctx.resources.displayMetrics.widthPixels * 2)
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    view.post {
                        val h = (view.contentHeight * view.scale).toInt().coerceAtLeast(200)
                        try {
                            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                            val cvs = android.graphics.Canvas(bmp)
                            cvs.drawColor(android.graphics.Color.WHITE)
                            view.layout(0, 0, widthPx, h)
                            view.draw(cvs)
                            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bmp.recycle()
                        } catch (e: Throwable) { /* 渲染失败返回 null */ }
                        wv.destroy()
                        if (cont.isActive) cont.resume(out.takeIf { it.exists() && it.length() > 0 })
                    }
                }
            }
            wv.layout(0, 0, widthPx, 400)
            wv.loadDataWithBaseURL(null, full, "text/html", "utf-8", null)
        }
    }
}
