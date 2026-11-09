package com.rogan.cloudmail.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.AppDatabase
import com.rogan.cloudmail.data.Contact
import kotlinx.coroutines.launch

/* ============ 联系人列表(按最后来信时间优先排序) ============ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var sortMode by remember { mutableStateOf(0) } // 0=最近来信优先 1=按字母 2=添加时间
    var importantFirst by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sortMode, importantFirst) {
        contacts = db.contacts().all()
    }
    val sorted = remember(contacts, sortMode, importantFirst) {
        val list = contacts.toMutableList()
        when (sortMode) {
            0 -> list.sortWith(compareByDescending<Contact> { it.lastReceivedAt }.thenByDescending { it.createdAt })
            1 -> list.sortBy { (it.remark.ifBlank { it.email }).uppercase() }
            2 -> list.sortByDescending { it.createdAt }
        }
        if (importantFirst) {
            val imp = list.filter { it.isImportant }
            val rest = list.filterNot { it.isImportant }
            imp + rest
        } else list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("联系人(${sorted.size})", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    // 排序方式切换
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.Sort, "排序") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("最近来信优先") }, onClick = { sortMode = 0; expanded = false })
                        DropdownMenuItem(text = { Text("按字母排序") }, onClick = { sortMode = 1; expanded = false })
                        DropdownMenuItem(text = { Text("按添加时间") }, onClick = { sortMode = 2; expanded = false })
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("contactEdit/-1") }) {
                Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("添加联系人")
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(sorted, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("contactEdit/${c.id}") }
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarOf(c.remark.ifBlank { c.email }, null, 42.dp)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.remark.ifBlank { c.email }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (c.isImportant) {
                                Spacer(Modifier.width(6.dp))
                                Text("重要", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.background(androidx.compose.ui.graphics.Color(0xFFEF5350), MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (c.lastReceivedAt > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text("最后来信 ${fmtTime(c.lastReceivedAt)}", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(c.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.phone.isNotBlank() || c.org.isNotBlank()) {
                            Text(listOf(c.phone, c.org).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { nav.navigate("composeTo/${c.email}") }) {
                        Icon(Icons.Default.Edit, "写邮件", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        scope.launch { db.contacts().delete(c.id); contacts = db.contacts().all() }
                    }) { Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider()
            }
        }
    }
}

/* ============ 联系人编辑/新建 ============ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditScreen(nav: NavHostController, id: Long) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var important by remember { mutableStateOf(false) }
    var isNew by remember { mutableStateOf(id <= 0) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        if (id > 0) db.contacts().byId(id)?.let { c ->
            email = c.email; remark = c.remark; phone = c.phone; org = c.org
            note = c.note; important = c.isImportant
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "添加联系人" else "编辑联系人", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = {
                        if (!emailRegex.matches(email.trim())) { toast = "请输入正确的邮箱地址"; return@TextButton }
                        scope.launch {
                            val c = Contact(
                                id = if (id > 0) id else 0,
                                email = email.trim(), remark = remark.trim(), phone = phone.trim(),
                                org = org.trim(), note = note.trim(), isImportant = important,
                                lastReceivedAt = db.contacts().byId(id)?.lastReceivedAt ?: 0,
                                createdAt = db.contacts().byId(id)?.createdAt ?: System.currentTimeMillis())
                            db.contacts().insert(c)
                            nav.popBackStack()
                        }
                    }) { Text("保存") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Labeled("邮箱地址(必填)", email) { email = it }
            Labeled("备注名(显示优先)", remark) { remark = it }
            Labeled("电话", phone) { phone = it }
            Labeled("单位", org) { org = it }
            Labeled("备注信息", note) { note = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = important, onCheckedChange = { important = it })
                Text("设为重要联系人(来信时明确提示)", fontSize = 14.sp)
            }
            if (!isNew) {
                TextButton(onClick = {
                    scope.launch {
                        db.contacts().delete(id)
                        nav.popBackStack()
                    }
                }) { Text("删除此联系人", color = androidx.compose.ui.graphics.Color(0xFFEF6C00)) }
            }
            toast?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFEF6C00), fontSize = 13.sp) }
            Spacer(Modifier.height(20.dp))
            Text("重要联系人来信时，通知会明确标注为重要联系人。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Labeled(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true)
    }
}
