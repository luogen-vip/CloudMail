package com.rogan.cloudmail.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.*
import com.rogan.cloudmail.mail.MailService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(TextFieldValue("")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var nickname by remember { mutableStateOf(TextFieldValue("")) }
    var preset by remember { mutableStateOf<ProviderPreset?>(null) }
    var imapHost by remember { mutableStateOf("") }
    var imapPort by remember { mutableStateOf("993") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpSsl by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var avatarPath by remember { mutableStateOf<String?>(null) }
    var showOAuth by remember { mutableStateOf(false) }

    val imgPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            scope.launch(Dispatchers.IO) {
                val f = copyUriToFile(ctx, u, "avatar_${System.currentTimeMillis()}")
                withContext(Dispatchers.Main) { avatarPath = f.absolutePath }
            }
        }
    }

    // 输入邮箱时自动带出预设
    fun applyPresetFor(addr: String) {
        val p = presetForEmail(addr)
        if (p.name != "自定义") {
            preset = p
            imapHost = p.imapHost; imapPort = p.imapPort.toString()
            smtpHost = p.smtpHost; smtpPort = p.smtpPort.toString(); smtpSsl = p.smtpSsl
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("添加邮箱账号", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("支持 QQ / 163 / 139 / Outlook / Gmail 及任意自定义邮箱；QQ、163 等需填授权码",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PRESETS.forEach { p ->
                FilterChip(
                    selected = preset?.name == p.name,
                    onClick = {
                        preset = p
                        imapHost = p.imapHost; imapPort = p.imapPort.toString()
                        smtpHost = p.smtpHost; smtpPort = p.smtpPort.toString(); smtpSsl = p.smtpSsl
                    },
                    label = { Text(p.name, fontSize = 12.sp) }
                )
            }
        }
        preset?.let { Text("提示：${it.hint}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp)) }

        // Outlook 专属：网页授权登录(微软已关闭普通密码收发信，OAuth 是唯一可靠方式)
        if (preset?.name == "Outlook/Hotmail" || email.text.substringAfter('@', "").lowercase()
                .let { it == "outlook.com" || it == "hotmail.com" || it == "live.com" }) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("微软邮箱(Outook/Hotmail/Live)请使用网页授权登录", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("微软已关闭「邮箱密码直接登录」；点下方按钮会在本 APP 内打开微软官方登录页，授权成功后自动添加账号，无需授权码。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                    Button(
                        enabled = !testing && !showOAuth,
                        onClick = { testing = true; showOAuth = true }
                    ) { Icon(Icons.Default.OpenInBrowser, null, Modifier.size(16.dp)); Text(" 微软账号授权登录") }
                }
            }
        }

        TextField(value = email, onValueChange = {
            email = it
        }, label = { Text("邮箱地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { applyPresetFor(email.text) }) { Text("识别", fontSize = 12.sp) }
            })
        TextField(value = password, onValueChange = { password = it },
            label = { Text("密码 / 授权码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextField(value = nickname, onValueChange = { nickname = it },
            label = { Text("自定义昵称(可选)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        // 头像
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            AvatarOf(nickname.text.ifBlank { email.text }, avatarPath, 48.dp)
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { imgPick.launch("image/*") }) { Text("自定义头像(可选)") }
        }

        Text("服务器设置", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = imapHost, onValueChange = { imapHost = it }, label = { Text("IMAP 服务器") },
                singleLine = true, modifier = Modifier.weight(2f))
            TextField(value = imapPort, onValueChange = { imapPort = it }, label = { Text("端口") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = smtpHost, onValueChange = { smtpHost = it }, label = { Text("SMTP 服务器") },
                singleLine = true, modifier = Modifier.weight(2f))
            TextField(value = smtpPort, onValueChange = { smtpPort = it }, label = { Text("端口") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = smtpSsl, onCheckedChange = { smtpSsl = it })
            Text("SMTP 使用 SSL", fontSize = 13.sp)
        }

        msg?.let { Text(it, fontSize = 13.sp, color = Color(0xFFEF6C00), modifier = Modifier.padding(vertical = 6.dp)) }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = !testing, onClick = {
                if (testing) return@OutlinedButton
                if (!emailRegex.matches(email.text.trim())) {
                    msg = "邮箱地址格式不正确：请输入完整地址，如 someone@qq.com（不能只填数字或文字）"
                    return@OutlinedButton
                }
                if (password.text.isBlank()) {
                    msg = "请填写密码或授权码"
                    return@OutlinedButton
                }
                testing = true; msg = "正在验证登录…"
                scope.launch {
                    // 防重复：同一邮箱已添加过则直接提示，不再插入
                    val existed = withContext(Dispatchers.IO) { db.accounts().byEmail(email.text.trim()) }
                    if (existed != null) {
                        testing = false
                        msg = "该邮箱（${existed.email}）已添加过了，不能重复添加；如需修改密码请在「设置→我的邮箱」中编辑。"
                        return@launch
                    }
                    val acc = buildAccount(email.text.trim(), password.text, nickname.text,
                        imapHost, imapPort, smtpHost, smtpPort, smtpSsl, avatarPath,
                        preset?.name ?: presetForEmail(email.text).name, isDefault = false)
                    val err = withContext(Dispatchers.IO) {
                        runCatching { MailService.verify(acc) }.exceptionOrNull()?.message ?: ""
                    }
                    if (err.isBlank()) {
                        val final = if (withContext(Dispatchers.IO) { db.accounts().all().isEmpty() }) acc.copy(isDefault = true) else acc
                        withContext(Dispatchers.IO) {
                            db.accounts().insert(final)
                            // 后台立即同步：登录不等待，马上可用
                            com.rogan.cloudmail.mail.SyncWorker.syncAllNow(ctx)
                        }
                        msg = null
                        testing = false
                        nav.popBackStack()
                    } else {
                        testing = false
                        msg = "登录失败：$err\n请检查地址、密码/授权码及服务器设置"
                    }
                }
            }) { Text(if (testing) "验证中…" else "验证并登录") }
        }
        Spacer(Modifier.height(30.dp))
    }

    // Outlook OAuth 网页授权
    if (showOAuth) {
        OAuthDialog(onResult = { ok, email_, token, refresh, expire, err ->
            showOAuth = false
            scope.launch {
                if (!ok) {
                    testing = false
                    msg = "授权未完成${err?.let { "：$it" } ?: ""}"
                } else {
                    // 若授权结果里仍拿不到邮箱，回退使用页面已填写的 Outlook 地址
                    val em = email_ ?: email.text.trim().takeIf {
                        emailRegex.matches(it) && it.substringAfter('@').lowercase()
                            .let { d -> d == "outlook.com" || d == "hotmail.com" || d == "live.com" }
                    } ?: ""
                    val existed = withContext(Dispatchers.IO) { db.accounts().byEmail(em) }
                    if (existed != null || em.isBlank()) {
                        testing = false
                        msg = if (em.isBlank()) "授权成功但未读取到邮箱地址，请重试" else "该邮箱（$em）已添加过了，不能重复添加。"
                    } else {
                        val acc = buildAccount(em, "", nickname.text,
                            "outlook.office365.com", "993", "smtp-mail.outlook.com", "587", false,
                            avatarPath, "Outlook/Hotmail", isDefault = false)
                            .copy(oauthToken = token ?: "", oauthRefresh = refresh ?: "", oauthExpire = expire ?: 0L)
                        val final = if (withContext(Dispatchers.IO) { db.accounts().all().isEmpty() }) acc.copy(isDefault = true) else acc
                        withContext(Dispatchers.IO) {
                            db.accounts().insert(final)
                            com.rogan.cloudmail.mail.SyncWorker.syncAllNow(ctx)
                        }
                        testing = false
                        msg = null
                        nav.popBackStack()
                    }
                }
            }
        })
    }
}

/** Microsoft OAuth2 网页授权：APP 内 WebView 完成登录，拦截跳转拿到授权码(带 PKCE) */
@Composable
fun OAuthDialog(onResult: (ok: Boolean, email: String?, token: String?, refresh: String?, expire: Long?, err: String?) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    // PKCE：verifier 随机串，challenge = BASE64URL(SHA256(verifier))
    val verifier = remember {
        val bytes = ByteArray(48)
        java.security.SecureRandom().nextBytes(bytes)
        android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            .replace("=", "")
    }
    val challenge = remember(verifier) {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        android.util.Base64.encodeToString(md.digest(verifier.toByteArray()),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            .replace("=", "")
    }

    AlertDialog(onDismissRequest = { if (!done) onResult(false, null, null, null, null, "用户取消") },
        title = { Text("微软账号授权登录") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { vctx ->
                        android.webkit.WebView(vctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, url: String): Boolean {
                                    // 授权完成会重定向到 https://localhost/?code=...
                                    if (url.startsWith("https://localhost")) {
                                        if (done) return true
                                        done = true
                                        val code = java.net.URI(url).let { u ->
                                            val q = u.rawQuery ?: ""
                                            q.split("&").firstOrNull { it.startsWith("code=") }
                                                ?.substring(5)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                                        }
                                        val e2 = url.contains("error=")
                                        Thread {
                                            runCatching {
                                                if (code.isNullOrBlank()) throw Exception(if (e2) "微软返回了错误，请重新授权" else "未获取到授权码，请重试")
                                                val t = MailService.msExchangeToken(code, verifier)
                                                // 优先从 access_token(JWT) 解析邮箱；失败时回退到 id_token(微软在 id_token 里更可靠地返回 email/preferred_username)
                                                val em = jwtEmail(t.access) ?: jwtEmail(t.idToken) ?: throw Exception("无法从授权结果读取邮箱地址；请在下方邮箱地址栏手动填写后重试")
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    onResult(true, em, t.access, t.refresh, t.expire, null)
                                                }
                                            }.onFailure {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    onResult(false, null, null, null, null, it.message)
                                                }
                                            }
                                        }.start()
                                        return true
                                    }
                                    return false
                                }
                                override fun onPageFinished(view: android.webkit.WebView, u: String) { loading = false }
                            }
                            loadUrl(MailService.msAuthorizeUrl(challenge))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(420.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                err?.let { Text(it, color = Color(0xFFEF6C00), fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!done) onResult(false, null, null, null, null, "用户取消") }) { Text("取消") }
        })
}

/** 从 access_token(JWT) 中解析 email / preferred_username */
fun jwtEmail(token: String): String? = runCatching {
    val payload = token.split(".")[1]
        .replace('-', '+').replace('_', '/')
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    val json = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
    val o = org.json.JSONObject(json)
    o.optString("preferred_username", o.optString("email", o.optString("unique_name", ""))).ifBlank { null }
}.getOrNull()

private fun buildAccount(
    email: String, password: String, nickname: String,
    ih: String, ip: String, sh: String, sp: String, ssl: Boolean,
    avatar: String?, provider: String, isDefault: Boolean
): Account = Account(
    email = email, password = password,
    imapHost = ih.ifBlank { presetForEmail(email).imapHost },
    imapPort = ip.toIntOrNull() ?: 993,
    smtpHost = sh.ifBlank { presetForEmail(email).smtpHost },
    smtpPort = sp.toIntOrNull() ?: 465,
    smtpSsl = ssl,
    provider = provider,
    nickname = nickname,
    avatarPath = avatar,
    isDefault = isDefault,
    vcard = VCard(name = nickname.ifBlank { email.substringBefore("@") }).toJson(),
    cardDefault = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAccountScreen(nav: NavHostController, id: Long) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf<Account?>(null) }
    var nickname by remember { mutableStateOf(TextFieldValue("")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var avatarPath by remember { mutableStateOf<String?>(null) }
    var cardDefault by remember { mutableStateOf(false) }
    var isDefault by remember { mutableStateOf(false) }
    var vcard by remember { mutableStateOf(VCard()) }
    var toast by remember { mutableStateOf<String?>(null) }

    val imgPick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u -> scope.launch(Dispatchers.IO) {
            val f = copyUriToFile(ctx, u, "avatar_${System.currentTimeMillis()}")
            withContext(Dispatchers.Main) { avatarPath = f.absolutePath }
        } }
    }

    LaunchedEffect(id) {
        acc = db.accounts().byId(id)
        acc?.let { a ->
            nickname = TextFieldValue(a.nickname); password = TextFieldValue(a.password)
            avatarPath = a.avatarPath; cardDefault = a.cardDefault; isDefault = a.isDefault
            vcard = VCard.fromJson(a.vcard)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑邮箱", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = {
                        acc?.let { a ->
                            scope.launch(Dispatchers.IO) {
                                val nv = vcard.copy(name = vcard.name.ifBlank { nickname.text })
                                db.accounts().update(a.copy(
                                    nickname = nickname.text, password = password.text,
                                    avatarPath = avatarPath, cardDefault = cardDefault,
                                    isDefault = isDefault, vcard = nv.toJson()))
                                withContext(Dispatchers.Main) { nav.popBackStack() }
                            }
                        }
                    }) { Text("保存") }
                }
            )
        }
    ) { pad ->
        val a0 = acc ?: return@Scaffold
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(a0, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(a0.email, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("服务商：${a0.provider}${if (a0.oauthRefresh.isNotBlank()) " · 网页授权登录" else ""}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { imgPick.launch("image/*") }) { Text("更换头像") }
            Spacer(Modifier.height(8.dp))
            TextField(value = nickname, onValueChange = { nickname = it }, label = { Text("显示昵称") }, modifier = Modifier.fillMaxWidth())
            if (a0.oauthRefresh.isBlank()) {
                TextField(value = password, onValueChange = { password = it }, label = { Text("密码/授权码(可更新)") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            } else {
                Text("此账号使用微软网页授权登录，无需密码；如需重新授权请删除后重新添加。", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isDefault, onCheckedChange = {
                    isDefault = it
                    if (it) scope.launch(Dispatchers.IO) {
                        db.accounts().all().forEach { o -> if (o.id != id) db.accounts().setDefault(o.id, false) }
                    }
                })
                Text("设为主邮箱(默认发信账号)", fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = cardDefault, onCheckedChange = { cardDefault = it })
                Text("该账号发信默认附带名片", fontSize = 14.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text("名片(资料卡)信息", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("开启名片后发送邮件会显示这些信息；头像使用上方「更换头像」的图片", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Labeled("名片姓名", vcard.name) { vcard = vcard.copy(name = it) }
            Labeled("电话", vcard.phone) { vcard = vcard.copy(phone = it) }
            Labeled("单位", vcard.org) { vcard = vcard.copy(org = it) }
            Labeled("职务", vcard.title) { vcard = vcard.copy(title = it) }
            Labeled("地址", vcard.address) { vcard = vcard.copy(address = it) }
            Labeled("备注", vcard.note) { vcard = vcard.copy(note = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    db.messages().deleteByAccount(id)
                    db.accounts().delete(id)
                    withContext(Dispatchers.Main) { nav.popBackStack() }
                }
            }) { Text("删除此邮箱账号(同时清理本地邮件)", color = Color(0xFFEF6C00)) }
            toast?.let { Text(it, color = Color(0xFFEF6C00), fontSize = 13.sp) }
            Spacer(Modifier.height(30.dp))
        }
    }
}
