package com.rogan.cloudmail.data

import org.json.JSONArray
import org.json.JSONObject

/* 常用邮箱服务商预设。用户也可完全自定义服务器。 */
data class ProviderPreset(
    val name: String,
    val domains: List<String>,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSsl: Boolean,
    val hint: String
)

val PRESETS = listOf(
    ProviderPreset(
        "QQ邮箱", listOf("qq.com", "foxmail.com"),
        "imap.qq.com", 993, "smtp.qq.com", 465, true,
        "需在 QQ 邮箱网页版「设置→账户」中开启 IMAP/SMTP 服务并生成授权码，登录时密码填授权码"
    ),
    ProviderPreset(
        "163邮箱", listOf("163.com", "126.com"),
        "imap.163.com", 993, "smtp.163.com", 465, true,
        "需在网页版「设置→POP3/SMTP/IMAP」开启服务并设置授权码，登录时密码填授权码"
    ),
    ProviderPreset(
        "139邮箱", listOf("139.com"),
        "imap.139.com", 993, "smtp.139.com", 465, true,
        "中国移动139邮箱，需开启 IMAP 并使用授权码或客户端专用密码"
    ),
    ProviderPreset(
        "Outlook/Hotmail", listOf("outlook.com", "hotmail.com", "live.com"),
        "outlook.office365.com", 993, "smtp.office365.com", 587, false,
        "使用邮箱账户密码；如开启了两步验证需使用应用专用密码"
    ),
    ProviderPreset(
        "Gmail", listOf("gmail.com"),
        "imap.gmail.com", 993, "smtp.gmail.com", 465, true,
        "需开启两步验证并生成「应用专用密码」；同时确保 Google 账户允许 IMAP"
    ),
    ProviderPreset(
        "自定义", listOf(),
        "", 993, "", 465, true,
        "手动填写 IMAP/SMTP 服务器地址与端口，密码或授权码均可（取决于服务商要求）"
    )
)

fun presetForEmail(email: String): ProviderPreset {
    val d = email.substringAfter('@', "").lowercase()
    return PRESETS.firstOrNull { p -> p.domains.any { d.endsWith(it) } } ?: PRESETS.last()
}

/* 名片(资料卡) JSON 结构 */
data class VCard(
    val name: String = "",
    val phone: String = "",
    val org: String = "",
    val title: String = "",
    val address: String = "",
    val note: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name); put("phone", phone); put("org", org)
        put("title", title); put("address", address); put("note", note)
    }.toString()

    companion object {
        fun fromJson(s: String): VCard = runCatching {
            val o = JSONObject(s.ifBlank { "{}" })
            VCard(o.optString("name"), o.optString("phone"), o.optString("org"),
                o.optString("title"), o.optString("address"), o.optString("note"))
        }.getOrDefault(VCard())
    }
}

/* 附件信息 */
data class AttInfo(
    val name: String,
    val path: String,
    val mime: String,
    val isInline: Boolean,
    val cid: String,
    val dur: Int = 0
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name); put("path", path); put("mime", mime)
        put("inline", isInline); put("cid", cid); put("dur", dur)
    }.toString()

    companion object {
        fun fromJson(s: String): List<AttInfo> = runCatching {
            val arr = JSONArray(s.ifBlank { "[]" })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AttInfo(o.optString("name"), o.optString("path"), o.optString("mime"),
                    o.optBoolean("inline"), o.optString("cid"), o.optInt("dur"))
            }
        }.getOrDefault(emptyList())
    }
}
