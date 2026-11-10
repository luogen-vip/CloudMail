package com.rogan.cloudmail.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

/** 内置浏览器：邮件里的链接默认在这里打开(不跳出 APP)，系统返回即回到邮件正文。
 *  顶栏提供：刷新 / 复制链接 / 用系统浏览器打开；页面自适应手机屏宽并支持双指缩放 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: NavHostController, url: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 强制补协议：有些链接写成 www.xxx.com，补 https 保证能打开
    val finalUrl = remember(url) {
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
    var title by remember { mutableStateOf(finalUrl.substringAfter("//").substringBefore('/')) }
    var progress by remember { mutableStateOf(0) }
    var reloadKey by remember { mutableStateOf(0) }
    var wvRef by remember { mutableStateOf<android.webkit.WebView?>(null) }

    fun copyLink() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", finalUrl))
        scope.launch { }
    }
    fun openExternal() {
        runCatching {
            ctx.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)), "选择浏览器打开"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = {
                    // 页面能后退先后退，后退不了才退出浏览器回到邮件
                    if (wvRef?.canGoBack() == true) wvRef?.goBack() else nav.popBackStack()
                }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { reloadKey++ }) { Icon(Icons.Default.Refresh, "刷新") }
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", finalUrl))
                    }) { Icon(Icons.Default.ContentCopy, "复制链接") }
                    IconButton(onClick = { openExternal() }) { Icon(Icons.Default.OpenInBrowser, "用浏览器打开") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (progress in 1..99) LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
            AndroidView(
                factory = { vctx ->
                    android.webkit.WebView(vctx).apply {
                        settings.javaScriptEnabled = true          // 大多数网站需要 JS 才能正常显示
                        settings.domStorageEnabled = true          // localStorage，很多登录页需要
                        settings.allowFileAccess = false
                        settings.loadWithOverviewMode = true       // 页面初始铺满手机屏
                        settings.useWideViewPort = true            // 按手机屏宽自适应排版
                        settings.builtInZoomControls = true        // 支持双指缩放(看小字更方便)
                        settings.displayZoomControls = false
                        settings.setSupportZoom(true)
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = settings.userAgentString.replace("; wv", "") // 用完整浏览器 UA：避免网站弹"请用浏览器打开"
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, u: String): Boolean {
                                // http(s) 继续在内置浏览器打开；tel:/mailto:/intent: 等交给系统
                                return if (u.startsWith("http://") || u.startsWith("https://")) {
                                    false
                                } else {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
                                    true
                                }
                            }
                            override fun onPageFinished(view: android.webkit.WebView, u: String) {
                                title = view.title?.takeIf { it.isNotBlank() }
                                    ?: u.substringAfter("//").substringBefore('/')
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        loadUrl(finalUrl)
                    }.also { wvRef = it }
                },
                update = { wv ->
                    if (reloadKey > 0) wv.reload()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
