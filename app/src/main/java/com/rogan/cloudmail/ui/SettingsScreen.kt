package com.rogan.cloudmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.*
import kotlinx.coroutines.launch

/* ============ 设置页 ============ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }

    LaunchedEffect(Unit) { accounts = db.accounts().all() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("我的邮箱", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            accounts.forEach { a ->
                ListItem(
                    headlineContent = { Text(a.nickname.ifBlank { a.email }) },
                    supportingContent = { Text(a.email) },
                    leadingContent = { Avatar(a, 36.dp) },
                    trailingContent = {
                        if (a.isDefault) Text("主", fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 1.dp))
                    },
                    modifier = Modifier.clickable { nav.navigate("editAccount/${a.id}") }
                )
            }
            if (accounts.isEmpty()) Text("尚未添加邮箱账号", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { nav.navigate("addAccount") }) {
                Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp)); Text(" 添加邮箱账号")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("收信", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            var realtime by remember { mutableStateOf(com.rogan.cloudmail.mail.MailPushService.isEnabled(ctx)) }
            ListItem(
                headlineContent = { Text("后台实时收信") },
                supportingContent = {
                    Text("保持与服务器的长连接，新邮件秒级到达；开启后状态栏会有一条常驻通知(静音)")
                },
                leadingContent = { Icon(Icons.Default.Bolt, null) },
                trailingContent = {
                    Switch(checked = realtime, onCheckedChange = {
                        realtime = it
                        com.rogan.cloudmail.mail.MailPushService.setEnabled(ctx, it)
                    })
                }
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("管理", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ListItem(headlineContent = { Text("联系人") },
                leadingContent = { Icon(Icons.Default.People, null) },
                modifier = Modifier.clickable { nav.navigate("contacts") })
            ListItem(headlineContent = { Text("搜索邮件/联系人") },
                leadingContent = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.clickable { nav.navigate("search") })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("功能说明", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val tips = listOf(
                "已删除(回收站)：在侧边栏打开，可还原或彻底删除；列表页内有「清空回收站」按钮。",
                "未读邮件：在侧边栏打开，里面可「全部标为已读」；已读状态保存在本机，不会因重启恢复为未读。",
                "撤回：邮件协议限制，撤回会删除服务器上的原邮件并向收件人发送撤回通知；同一服务商效果最佳。",
                "已读回执：发送时开启「已读回执」，同域邮箱(QQ→QQ等)支持更好，跨域邮箱无法保证。",
                "定时发送：写邮件页点击时钟图标设置，到点自动真实发送。",
                "Outlook/Hotmail：微软已关闭密码直接登录，请在添加账号时使用「微软账号授权登录」。",
                "重要联系人：来信通知会明确标注重要联系人身份。"
            )
            tips.forEach { Text("· $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp)) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // 作者信息
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于云邮", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("版本 1.3.5", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("作者：罗根", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("一款支持多账号、多服务商的真实邮箱客户端", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/* ============ 全局搜索(输入即实时显示结果) ============ */

// 搜索状态全局持有：点进邮件再返回不清空，无需重新搜索
object SearchState {
    var q by mutableStateOf("")
    var msgs by mutableStateOf<List<MailMessage>>(emptyList())
    var contacts by mutableStateOf<List<Contact>>(emptyList())
    var searched by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()

    // 实时搜索：输入变化后 300ms 自动搜索，无需手动点击；状态存在 SearchState（全局），点进邮件返回不清空
    LaunchedEffect(SearchState.q) {
        val kw = SearchState.q.trim()
        if (kw.isBlank()) { SearchState.msgs = emptyList(); SearchState.contacts = emptyList(); SearchState.searched = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        SearchState.msgs = db.messages().search(kw)
        SearchState.contacts = db.contacts().search(kw)
        SearchState.searched = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = { SearchState.q = ""; SearchState.searched = false; nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 搜索框：圆润胶囊造型，高度固定不遮挡文字
            TextField(value = SearchState.q, onValueChange = { SearchState.q = it },
                placeholder = { Text("输入即实时搜索：邮件主题/内容/联系人…", fontSize = 14.sp) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(56.dp),
                trailingIcon = {
                    if (SearchState.q.isNotBlank()) IconButton(onClick = { SearchState.q = "" }) { Icon(Icons.Default.Close, "清空") }
                })
            LazyColumn(Modifier.fillMaxSize()) {
                if (SearchState.contacts.isNotEmpty()) {
                    item { Text("联系人 (${SearchState.contacts.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)) }
                    items(SearchState.contacts, key = { "c${it.id}" }) { c ->
                        ListItem(
                            headlineContent = { Text(c.remark.ifBlank { c.email }) },
                            supportingContent = { Text(c.email) },
                            leadingContent = { AvatarOf(c.remark.ifBlank { c.email }, null, 36.dp) },
                            trailingContent = { TextButton(onClick = { nav.navigate("composeTo/${c.email}") }) { Text("发信") } },
                            modifier = Modifier.clickable { nav.navigate("contactEdit/${c.id}") }
                        )
                    }
                    item { HorizontalDivider() }
                }
                if (SearchState.msgs.isNotEmpty()) {
                    item { Text("邮件 (${SearchState.msgs.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)) }
                    items(SearchState.msgs, key = { "m${it.id}" }) { m ->
                        ListItem(
                            headlineContent = { Text(m.subject.ifBlank { "(无主题)" }) },
                            supportingContent = { Text("${if (m.folder == "SENT") "发送至 " else m.fromAddr} · ${fmtTime(m.date)}") },
                            leadingContent = {
                                Icon(if (m.folder == "SENT") Icons.Default.Send else Icons.Default.Email, null,
                                    tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { nav.navigate("detail/${m.id}") }
                        )
                    }
                }
                if (SearchState.searched && SearchState.msgs.isEmpty() && SearchState.contacts.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有匹配的结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (!searched) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("输入内容即可实时显示相关邮件与联系人", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
