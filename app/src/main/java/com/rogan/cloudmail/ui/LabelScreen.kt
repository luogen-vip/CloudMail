package com.rogan.cloudmail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.rogan.cloudmail.data.AppDatabase
import com.rogan.cloudmail.data.Label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 标签管理页：新建标签(数量无上限)、查看每个标签下有多少封邮件、
 *  长按进入多选勾选删除(支持全选)；删除标签后其下邮件自动解除关联(邮件本身不删) */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LabelsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var labels by remember { mutableStateOf<List<Label>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var newName by remember { mutableStateOf("") }
    // 多选删除模式：长按某一行进入并默认勾选该行；全部取消勾选自动退出
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); toast = null } }

    fun load() {
        scope.launch {
            val (ls, cs) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val l = db.labels().all()
                val c = mutableMapOf<Long, Int>()
                for (x in l) c[x.id] = db.mailLabels().countForLabel(x.id)
                l to c.toMap()
            }
            labels = ls; counts = cs
        }
    }
    LaunchedEffect(Unit) { load() }

    fun exitSelection() { selectionMode = false; selected = emptySet() }
    fun toggleSelect(id: Long, add: Boolean) {
        val s = if (add) selected + id else selected - id
        selected = s
        if (!add && s.isEmpty()) selectionMode = false
    }
    // 系统返回：多选模式下先退出多选，再退出页面
    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("标签管理", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = { selected = labels.map { it.id }.toSet() }) { Text("全选", color = MaterialTheme.colorScheme.primary) }
                        TextButton(onClick = { if (selected.isNotEmpty()) confirmDelete = true }) { Text("删除(${selected.size})", color = MaterialTheme.colorScheme.primary) }
                        TextButton(onClick = { exitSelection() }) { Text("取消", color = MaterialTheme.colorScheme.primary) }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 新建标签
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(value = newName, onValueChange = { newName = it },
                    placeholder = { Text("输入标签名，如：工作") },
                    modifier = Modifier.weight(1f), singleLine = true)
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val n = newName.trim()
                    if (n.isEmpty()) { toast = "请先输入标签名"; return@Button }
                    scope.launch(Dispatchers.IO) {
                        // 同名标签不重复创建
                        if (db.labels().all().any { it.name == n }) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) { toast = "已有同名标签「$n」"; newName = "" }
                            return@launch
                        }
                        db.labels().insert(Label(name = n, createdAt = System.currentTimeMillis()))
                        kotlinx.coroutines.withContext(Dispatchers.Main) { newName = ""; toast = "已创建标签「$n」"; load() }
                    }
                }) { Text("新建") }
            }
            if (!selectionMode) Text("长按标签可进入勾选删除；删除标签不会删除里面的邮件，只会解除关联。",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp))
            if (selectionMode) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 个标签", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (selected.isNotEmpty()) confirmDelete = true }) { Text("删除所选", color = Color.White, fontSize = 12.sp) }
                }
            }
            if (labels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有标签，在上方输入名字点「新建」即可创建", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                return@Column
            }
            LazyColumn {
                items(labels, key = { it.id }) { lb ->
                    val isSel = lb.id in selected
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .combinedClickable(
                                onClick = { if (selectionMode) toggleSelect(lb.id, !isSel) },
                                onLongClick = { selectionMode = true; selected = selected + lb.id }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionMode) Checkbox(checked = isSel, onCheckedChange = { toggleSelect(lb.id, it) })
                        Icon(Icons.Default.Label, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(lb.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("${counts[lb.id] ?: 0} 封邮件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!selectionMode) {
                            // 删除单个标签：确认后删除(邮件保留，仅解除关联)
                            TextButton(onClick = { selected = setOf(lb.id); confirmDelete = true }) { Text("删除", fontSize = 12.sp, color = Color(0xFFD32F2F)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(onDismissRequest = { confirmDelete = false },
                title = { Text("删除标签") },
                text = { Text("确定删除选中的 ${selected.size} 个标签吗？\n标签里的邮件不会被删除，只是移出该标签。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch(Dispatchers.IO) {
                            selected.forEach { id ->
                                db.mailLabels().deleteByLabel(id)
                                db.labels().delete(id)
                            }
                            val n = selected.size
                            kotlinx.coroutines.withContext(Dispatchers.Main) { exitSelection(); toast = "已删除 $n 个标签"; load() }
                        }
                    }) { Text("删除", color = Color(0xFFD32F2F)) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
        }
    }
}
