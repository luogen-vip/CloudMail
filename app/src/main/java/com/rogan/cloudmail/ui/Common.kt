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

/** 判断是否为系统退信(DSN)：发件人是 mailer-daemon/postmaster，或主题命中常见退信关键词。
 *  退信说明对方地址/域名可能不存在或已满/拒收——列表、详情、通知都要明确提示，
 *  不能像普通新邮件一样只显示发件人(否则用户以为发送成功，其实是被退回了) */
fun isBounceMail(fromAddr: String, subject: String): Boolean =
    fromAddr.contains("mailer-daemon", true) ||
            fromAddr.contains("postmaster", true) ||
            subject.contains("Undeliverable", true) ||
            subject.contains("退信", false) ||
            subject.contains("退回", false) ||
            subject.contains("Undelivered", true) ||
            subject.contains("Mail Delivery", true) ||
            subject.contains("Returned mail", true) ||
            subject.contains("Delivery Status", true)

/** 从退信正文解析出具体失败原因(中文说明)：域名不存在/收件地址不存在/邮箱已满/被拒收/暂时无法连接。
 *  邮件协议下 SMTP 服务器先受理、之后才异步回发退信(DSN)，所以"已发送"并不代表真的送达；
 *  APP 收到退信后用这个函数把服务器给的英文错误翻译成用户看得懂的原因 */
fun bounceReason(body: String): String {
    // 去 HTML 标签/压缩空白，只留纯文本再匹配关键词
    val b = body.replace(Regex("<[^>]{1,300}>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
    fun has(p: String): Boolean = runCatching { Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(b) }.getOrDefault(false)
    return when {
        // 1. 域名问题最优先：@ 后面的域名根本不存在/DNS 解析不到
        has("Host or domain name not found|Name or service not known|domain not found|" +
                "domain [^. ]{1,60} (?:does not |)exist|unknown domain|NXDOMAIN|" +
                "no.{0,15}(?:MX|mail exchanger)|DNS Lookup Failed|unrouteable address") ->
            "收件域名不存在或无法解析（请核对邮箱 @ 后面的部分是否写错）"
        // 2. 收件账号不存在（550 user unknown 是最常见的退信）
        has("user unknown|unknown user|unknown recipient|no such (?:user|mailbox|address|recipient)|" +
                "user (?:not found|doesn't exist|does not exist)|recipient (?:not found|unknown|rejected)|" +
                "address (?:does not exist|not exist|rejected|invalid)|invalid (?:recipient|mailbox|address)|" +
                "bad destination|non-?existent|mailbox unavailable|recipient deferred|" +
                "address not found|mailbox not found|not our customer") ->
            "收件邮箱地址不存在（对方服务器确认没有这个账号）"
        // 3. 邮箱已满
        has("quota|mailbox (?:is )?full|over ?quota|insufficient (?:storage|system storage)|storage exceeded") ->
            "对方邮箱已满，暂时收不下新邮件"
        // 4. 被拒收/被判为垃圾邮件
        has("spam|blacklist|black ?list|block ?list|blocked|banned|policy|denied|refused|" +
                "not allowed|security|authentication|filter") ->
            "被对方服务器拒收（可能被判为垃圾邮件或被对方策略拦截）"
        // 5. 临时故障（4xx 类：稍后服务器会自动重试投递）
        has("timed? ?out|time ?out|connection (?:refused|failed|reset|dropped)|unreachable|" +
                "temporar|try (?:again )?later|greylist|deferr|\\b4\\d\\d\\b") ->
            "对方服务器暂时无法连接，系统稍后会自动重试投递"
        // 6. 其他永久失败(5xx)
        has("\\b5\\d\\d\\b") ->
            "对方服务器拒绝接收（错误码 5xx，邮件未送达）"
        else -> "对方服务器退回了邮件（未能成功送达）"
    }
}

/** 从退信正文里提取失败的收件地址(第一条 @ 地址)，找不到返回 null */
fun bounceFailedAddr(body: String): String? {
    val b = body.replace(Regex("<[^>]{1,300}>"), " ")
    return Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(b)?.value
}

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
