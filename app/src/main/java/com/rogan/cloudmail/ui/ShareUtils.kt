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

/** 分享/导出工具：系统分享、导出 EML / HTML / 长图、复制摘要、保存到下载文件夹。
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

        val html = m.bodyHtml.ifBlank { "<p>(无正文内容)</p>" }
        val inlineAtts = atts.filter { it.isInline && it.cid.isNotBlank() && File(it.path).exists() }
        sb.append("--$boundary\r\n")
        sb.append("Content-Type: text/html; charset=utf-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n")
        sb.append(html.replace("\r\n", "\n").replace("\n", "\r\n"))
        sb.append("\r\n")

        inlineAtts.forEach { a ->
            val data = Base64.getEncoder().encodeToString(File(a.path).readBytes())
            sb.append("--$boundary\r\n")
            sb.append("Content-Type: ${a.mime.ifBlank { "application/octet-stream" }}; name=\"${a.name}\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-ID: <${a.cid}>\r\n")
            sb.append("Content-Disposition: inline; filename=\"${a.name}\"\r\n\r\n")
            data.chunked(76).forEach { sb.append(it).append("\r\n") }
        }
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

    /** 保存文件到手机(不需要存储权限)：
     *  - 图片 → MediaStore.Images(Pictures/CloudMail)，图库里能直接看到；
     *  - 视频 → MediaStore.Video(Movies/CloudMail)；
     *  - 其他文件 → MediaStore.Downloads(Download/CloudMail)，文件管理器里能找到。
     *  之前用 File API 直接写公共 Download 目录，Android 10+ 有限制，容易“提示保存成功但实际没写进去”；
     *  现在全部走 MediaStore，写入成功才提示成功，失败会给出真实原因 */
    fun saveToDownloads(ctx: Context, f: File, mime: String): String = runCatching {
        if (!f.exists() || f.length() == 0L)
            return@runCatching "保存失败：文件不存在或为空(${f.name})，可能是正文还没加载完，请重新打开这封邮件再试"
        val mime2 = mime.ifBlank { guessMime(f.name) }
        val resolver = ctx.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime2)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val isImg = mime2.startsWith("image/")
            val isVid = mime2.startsWith("video/")
            val uri: android.net.Uri = (if (isImg) {
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CloudMail")
                resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else if (isVid) {
                values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/CloudMail")
                resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/CloudMail")
                resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }) ?: throw IllegalStateException("无法创建保存条目")
            val os = resolver.openOutputStream(uri) ?: throw IllegalStateException("无法写入文件")
            os.use { outs -> f.inputStream().use { ins -> ins.copyTo(outs) } }
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            when {
                isImg -> "已保存到图库(相册里的 Pictures/CloudMail)：${f.name}"
                isVid -> "已保存到视频库(Movies/CloudMail)：${f.name}"
                else -> "已保存到手机「下载/CloudMail」文件夹(文件管理器里能找到)：${f.name}"
            }
        } else {
            val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "CloudMail")
            dir.mkdirs()
            val out = java.io.File(dir, f.name)
            f.inputStream().use { ins -> out.outputStream().use { outs -> ins.copyTo(outs) } }
            android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), arrayOf(mime2), null)
            if (mime2.startsWith("image/")) "已保存到下载/CloudMail(图库稍后自动收录)：${out.absolutePath}"
            else "已保存到下载/CloudMail：${out.absolutePath}"
        }
    }.getOrElse {
        val dir = File(ctx.getExternalFilesDir(null), "Download").apply { mkdirs() }
        val out = File(dir, f.name)
        runCatching { f.inputStream().use { ins -> out.outputStream().use { outs -> ins.copyTo(outs) } } }
        if (out.exists() && out.length() > 0) "已保存到应用文件夹(文件管理器→Android/data/com.rogan.cloudmail/files/Download)：${f.name}"
        else "保存失败：${it.message ?: "未知错误"}，请重试"
    }

    private fun widthPxOf(ctx: Context): Int {
        val density = ctx.resources.displayMetrics.density
        // 上限 1080 物理像素：之前 720dp*density 在高分屏上是 2000+ 像素宽，
        // 乘上长邮件的高度后位图内存过大，易导致下半部分渲染失败留白
        return (720 * density).toInt()
            .coerceAtMost(ctx.resources.displayMetrics.widthPixels * 2)
            .coerceAtMost(1080)
    }

    /** 正文渲染成长图(清晰完整)：WebView 按内容高度渲染成一张 PNG(含主题/发件人头部)。
     *  必须把 WebView 临时挂到当前窗口(移到屏幕外，用户看不见)才能真实渲染：
     *  之前不挂窗口直接 draw，很多机型画出来是空白；
     *  高度测量改为轮询到稳定：之前只在 onPageFinished 量一次，正文里的图片/字体还在异步加载，
     *  高度定格太小，长图只有上半部分；现在每 400ms 量一次内容高度，连续 3 次稳定才截图 */
    suspend fun exportLongImage(ctx: Context, m: MailMessage): File? = withContext(Dispatchers.Main) {
        val inlineAtts = AttInfo.fromJson(m.attachmentsJson).filter { it.isInline && it.cid.isNotBlank() }
        var html = m.bodyHtml.ifBlank { "<p style='color:#999'>(无正文内容)</p>" }
        inlineAtts.forEach { a ->
            if (a.path.isNotBlank() && File(a.path).exists()) html = html.replace("cid:${a.cid}", "file://${a.path}")
        }
        val full = """
            <html><head><meta charset="utf-8"/>
            ${fontFaceCss()}
            <style>body{font-family:sans-serif;padding:16px;color:#222;word-break:break-word;background:#ffffff}
            img{max-width:100%;height:auto;border-radius:6px} a{color:#1565C0}
            .hdr{border-bottom:1px solid #e0e0e0;padding-bottom:10px;margin-bottom:14px;color:#666;font-size:14px}</style></head>
            <body><div class="hdr"><b style="color:#111;font-size:17px">${escXml(m.subject.ifBlank { "(无主题)" })}</b><br/>
            发件人：${escXml(m.fromName.ifBlank { m.fromAddr })} ｜ ${fmtFull(m.date)}</div>$html</body></html>
        """.trimIndent()
        val out = File(exportDir(ctx), safeName(m) + ".png")
        // 找到当前 Activity 的根布局，把 WebView 挂上去(移到屏幕外)
        var c: Context = ctx
        var act: android.app.Activity? = null
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) { act = c; break }
            c = c.baseContext
        }
        val root = act?.window?.decorView as? android.view.ViewGroup
        val widthPx = widthPxOf(ctx)
        if (root != null) {
            renderAttached(root, full, widthPx, out)
        } else {
            renderOffscreen(ctx, full, widthPx, out)
        }
        out.takeIf { it.exists() && it.length() > 0 }
    }

    /** 挂到窗口渲染(可靠方案)：内容高度轮询到连续稳定后才截图，保证长邮件完整 */
    private suspend fun renderAttached(root: android.view.ViewGroup, full: String, widthPx: Int, out: File) {
        kotlinx.coroutines.withTimeoutOrNull(25000) {
            suspendCancellableCoroutine { cont ->
                val wv = WebView(root.context)
                wv.settings.javaScriptEnabled = false
                wv.settings.allowFileAccess = true
                val lp = android.widget.FrameLayout.LayoutParams(widthPx,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
                wv.translationY = -100000f // 移到屏幕外，用户看不到
                root.addView(wv, lp)
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        var lastH = -1
                        var stable = 0
                        var tries = 0
                        fun tick() {
                            tries++
                            val h = (view.contentHeight * view.scale).toInt().coerceIn(200, 12000)
                            if (h == lastH) stable++ else { stable = 0; lastH = h }
                            if (stable >= 3 || tries > 55) {
                                view.post {
                                    val h2 = lastH.coerceAtLeast(200)
                                    try {
                                        // 关键修复“长图下半空白”：WebView 只渲染可视区域，
                                        // 直接 draw 时屏外的下半部分是空白。改用软件图层(SOFTWARE)
                                        // 强制 WebView 把整页光栅化后再 draw，保证从头到尾全部有内容
                                        view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                        // 位图内存保护：面积超过 4000 万像素时压缩宽度
                                        val w = if (widthPx.toLong() * h2 > 40_000_000L) (40_000_000L / h2).toInt() else widthPx
                                        view.measure(
                                            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                                            android.view.View.MeasureSpec.makeMeasureSpec(h2, android.view.View.MeasureSpec.EXACTLY))
                                        view.layout(0, 0, w, h2)
                                        // 先滚到底再回顶：触发懒加载内容(远程图片/字体)全部加载，并强制整页重绘
                                        view.scrollTo(0, h2); view.scrollTo(0, 0)
                                        view.postDelayed({
                                            try {
                                                val bmp = Bitmap.createBitmap(w, h2, Bitmap.Config.ARGB_8888)
                                                val cvs = android.graphics.Canvas(bmp)
                                                cvs.drawColor(android.graphics.Color.WHITE)
                                                view.draw(cvs)
                                                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                                bmp.recycle()
                                            } catch (_: Throwable) { }
                                            root.removeView(wv)
                                            wv.destroy()
                                            if (cont.isActive) cont.resume(Unit)
                                        }, 400)
                                    } catch (_: Throwable) {
                                        root.removeView(wv)
                                        wv.destroy()
                                        if (cont.isActive) cont.resume(Unit)
                                    }
                                }
                            } else view.postDelayed({ tick() }, 400)
                        }
                        view.postDelayed({ tick() }, 400)
                    }
                }
                wv.loadDataWithBaseURL("file:///android_asset/", full, "text/html", "utf-8", null)
            }
        }
    }

    /** 拿不到窗口时的兜底渲染(老方式，部分机型仍可用) */
    private suspend fun renderOffscreen(ctx: Context, full: String, widthPx: Int, out: File) {
        kotlinx.coroutines.withTimeoutOrNull(25000) {
            suspendCancellableCoroutine { cont ->
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = false
                wv.settings.allowFileAccess = true
                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        var lastH = -1
                        var stable = 0
                        var tries = 0
                        fun tick() {
                            tries++
                            val h = (view.contentHeight * view.scale).toInt().coerceIn(200, 12000)
                            if (h == lastH) stable++ else { stable = 0; lastH = h }
                            if (stable >= 3 || tries > 55) {
                                view.post {
                                    val h2 = lastH.coerceAtLeast(200)
                                    try {
                                        val bmp = Bitmap.createBitmap(widthPx, h2, Bitmap.Config.ARGB_8888)
                                        val cvs = android.graphics.Canvas(bmp)
                                        cvs.drawColor(android.graphics.Color.WHITE)
                                        view.measure(
                                            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                                            android.view.View.MeasureSpec.makeMeasureSpec(h2, android.view.View.MeasureSpec.EXACTLY))
                                        view.layout(0, 0, widthPx, h2)
                                        view.draw(cvs)
                                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                        bmp.recycle()
                                    } catch (_: Throwable) { }
                                    wv.destroy()
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            } else view.postDelayed({ tick() }, 400)
                        }
                        view.postDelayed({ tick() }, 400)
                    }
                }
                wv.layout(0, 0, widthPx, 400)
                wv.loadDataWithBaseURL("file:///android_asset/", full, "text/html", "utf-8", null)
            }
        }
    }
}
