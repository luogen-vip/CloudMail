package com.rogan.cloudmail.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.*

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
            Text("管理", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ListItem(headlineContent = { Text("联系人") },
                leadingContent = { Icon(Icons.Default.People, null) },
                modifier = Modifier.clickable { nav.navigate("contacts") })
            ListItem(headlineContent = { Text("搜索邮件/联系人") },
                leadingContent = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.clickable { nav.navigate("search") })
            ListItem(headlineContent = { Text("清空已删除(回收站)") },
                leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                modifier = Modifier.clickable {
                    kotlinx.coroutines.MainScope().launch { db.messages().emptyTrash() }
                })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("功能说明", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val tips = listOf(
                "撤回：邮件协议限制，撤回会删除服务器上的原邮件并向收件人发送撤回通知；同一服务商效果最佳。",
                "已读回执：发送时开启「已读回执」，同域邮箱(QQ→QQ等)支持更好，跨域邮箱无法保证。",
                "定时发送：写邮件页点击时钟图标设置，到点自动真实发送。",
                "广告邮件：含退订头或营销关键词的邮件会自动标注「广告」。",
                "重要联系人：来信通知会明确标注重要联系人身份。"
            )
            tips.forEach { Text("· $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 3.dp)) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // 作者信息
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("关于云邮", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("版本 1.0.0", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/* ============ 全局搜索 ============ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var msgs by remember { mutableStateOf<List<MailMessage>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(value = q, onValueChange = { q = it },
                        placeholder = { Text("搜索邮件、主题、内容、联系人…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = {
                        if (q.isBlank()) return@TextButton
                        scope.launch {
                            msgs = db.messages().search(q)
                            contacts = db.contacts().search(q)
                            searched = true
                        }
                    }) { Text("搜索") }
                }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (contacts.isNotEmpty()) {
                item { Text("联系人", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)) }
                items(contacts, key = { "c${it.id}" }) { c ->
                    ListItem(
                        headlineContent = { Text(c.remark.ifBlank { c.email }) },
                        supportingContent = { Text(c.email) },
                        leadingContent = { AvatarOf(c.remark.ifBlank { c.email }, null, 36.dp) },
                        trailingContent = { TextButton(onClick = { nav.navigate("composeTo/${c.email}") }) { Text("发信") } },
                        modifier = Modifier.clickable { nav.navigate("contactEdit/${c.id}") }
                    )
                }
                HorizontalDivider()
            }
            if (msgs.isNotEmpty()) {
                item { Text("邮件", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)) }
                items(msgs, key = { "m${it.id}" }) { m ->
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
            if (searched && msgs.isEmpty() && contacts.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有匹配的结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!searched) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("输入关键词搜索邮件和联系人", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
