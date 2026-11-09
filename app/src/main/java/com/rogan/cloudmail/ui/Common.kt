package com.rogan.cloudmail.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rogan.cloudmail.data.Account
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun fmtTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000L * 60 * 24 && isSameDay(now, millis) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
        isSameDay(now - 86_400_000L, millis) -> "昨天"
        else -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(millis))
    }
}

fun fmtFull(millis: Long): String =
    SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()).format(Date(millis))

private fun isSameDay(a: Long, b: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = a }
    val c2 = Calendar.getInstance().apply { timeInMillis = b }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

/** 头像：优先自定义图片，否则显示首字 */
@Composable
fun Avatar(acc: Account, size: Dp = 40.dp) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (acc.avatarPath != null && File(acc.avatarPath).exists()) {
            AsyncImage(
                model = File(acc.avatarPath), contentDescription = null,
                modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                    Text(
                        (acc.nickname.ifBlank { acc.email }).take(1),
                        color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = (size.value / 2).sp
                    )
                }
            }
        }
    }
}

@Composable
fun AvatarOf(name: String, avatarPath: String?, size: Dp = 40.dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        if (avatarPath != null && File(avatarPath).exists()) {
            AsyncImage(
                model = File(avatarPath), contentDescription = null,
                modifier = Modifier.size(size).background(MaterialTheme.colorScheme.secondary, CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
                Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                    Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold,
                        fontSize = (size.value / 2).sp)
                }
            }
        }
    }
}

fun displayName(remark: String?, email: String, fallbackName: String = "") =
    remark?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { email }

val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

fun saveToDownloads(src: File, name: String): File {
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    dir.mkdirs()
    var out = File(dir, name)
    var i = 1
    while (out.exists()) {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        out = File(dir, "${base}_$i.${ext.ifBlank { "bin" }}")
        i++
    }
    src.copyTo(out)
    return out
}
