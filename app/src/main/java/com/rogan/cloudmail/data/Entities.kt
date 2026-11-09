package com.rogan.cloudmail.data

import androidx.room.*

/* ============ 实体 ============ */

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val password: String,           // 授权码或密码
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSsl: Boolean,
    val provider: String,           // QQ / 163 / 139 / Outlook / Gmail / 自定义
    val nickname: String,           // 自定义显示名
    val avatarPath: String?,        // 自定义头像路径
    val isDefault: Boolean,         // 主邮箱(默认发信)
    val vcard: String,              // 名片 JSON
    val cardDefault: Boolean,       // 该账号发信默认带名片
    val oauthToken: String = "",    // OAuth2 访问令牌(Outlook等)，空=密码登录
    val oauthRefresh: String = "",  // OAuth2 刷新令牌
    val oauthExpire: Long = 0       // 访问令牌过期时间(毫秒)
)

@Entity(tableName = "messages")
data class MailMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val folder: String,             // INBOX / SENT / TRASH
    val uid: String,
    val messageId: String,
    val subject: String,
    val fromAddr: String,
    val fromName: String,
    val toAddr: String,
    val ccAddr: String,
    val date: Long,
    var bodyHtml: String,           // 渲染用正文
    var snippet: String,
    var isRead: Boolean,
    var isStarred: Boolean,
    var isAd: Boolean,              // 广告邮件标记
    var hasAttachments: Boolean,
    var attachmentsJson: String,    // [{name,path,mime,isInline,cid}]
    var loaded: Boolean,            // 正文是否已拉取
    var size: Long = 0              // 邮件大小(字节)
)

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val remark: String,             // 备注名(显示优先)
    val phone: String,
    val org: String,
    val note: String,
    val isImportant: Boolean,       // 重要联系人
    val lastReceivedAt: Long,       // 最后来信时间(排序用)
    val createdAt: Long
)

@Entity(tableName = "outbox")
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val toAddr: String,
    val ccAddr: String,
    val bccAddr: String,
    val subject: String,
    val bodyHtml: String,
    val attachmentsJson: String,    // [{name,path,mime,isInline}]
    val readReceipt: Boolean,
    val cardMode: Boolean,
    val scheduledAt: Long?,         // null=普通草稿; 有值=定时发送
    val status: String,             // DRAFT / SCHEDULED / SENDING / FAILED
    val updatedAt: Long,
    val error: String = ""          // 发送失败原因
)

/* ============ DAO ============ */

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun all(): List<Account>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun byId(id: Long): Account?
    @Query("SELECT * FROM accounts WHERE email = :email LIMIT 1") suspend fun byEmail(email: String): Account?
    @Query("SELECT * FROM accounts WHERE isDefault = 1 LIMIT 1") suspend fun getDefault(): Account?
    @Insert suspend fun insert(a: Account): Long
    @Update suspend fun update(a: Account)
    @Query("DELETE FROM accounts WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE accounts SET isDefault = :def WHERE id = :id") suspend fun setDefault(id: Long, def: Boolean)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE folder IN ('INBOX','SENT') ORDER BY date DESC LIMIT 300")
    suspend fun allMail(): List<MailMessage>
    @Query("SELECT * FROM messages WHERE folder = 'INBOX' ORDER BY date DESC LIMIT 300")
    suspend fun inbox(): List<MailMessage>
    @Query("SELECT * FROM messages WHERE folder = 'INBOX' AND isRead = 0 ORDER BY date DESC")
    suspend fun unread(): List<MailMessage>
    @Query("SELECT * FROM messages WHERE isStarred = 1 AND folder IN ('INBOX','SENT') ORDER BY date DESC")
    suspend fun starred(): List<MailMessage>
    @Query("SELECT * FROM messages WHERE folder = 'SENT' ORDER BY date DESC")
    suspend fun sent(): List<MailMessage>
    @Query("SELECT * FROM messages WHERE folder = 'TRASH' ORDER BY date DESC")
    suspend fun trash(): List<MailMessage>
    @Query("SELECT * FROM messages WHERE accountId = :acc AND folder IN ('INBOX','SENT') ORDER BY date DESC LIMIT 300")
    suspend fun byAccount(acc: Long): List<MailMessage>
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun byId(id: Long): MailMessage?
    @Query("SELECT * FROM messages WHERE accountId = :acc AND folder = :folder AND uid = :uid LIMIT 1")
    suspend fun byUid(acc: Long, folder: String, uid: String): MailMessage?
    @Query("SELECT COUNT(*) FROM messages WHERE accountId = :acc AND folder = 'INBOX' AND isRead = 0")
    suspend fun unreadCount(acc: Long): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(m: MailMessage): Long
    @Update suspend fun update(m: MailMessage)
    @Query("UPDATE messages SET isRead = :read WHERE id = :id") suspend fun setRead(id: Long, read: Boolean)
    @Query("UPDATE messages SET isStarred = :star WHERE id = :id") suspend fun setStar(id: Long, star: Boolean)
    @Query("UPDATE messages SET folder = :folder WHERE id = :id") suspend fun setFolder(id: Long, folder: String)
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteForever(id: Long)
    @Query("DELETE FROM messages WHERE folder = 'TRASH'") suspend fun emptyTrash()
    @Query("UPDATE messages SET folder = 'INBOX' WHERE folder = 'TRASH'") suspend fun restoreAllTrash()
    @Query("DELETE FROM messages WHERE accountId = :acc") suspend fun deleteByAccount(acc: Long)
    @Query("UPDATE messages SET isRead = 1 WHERE folder IN ('INBOX','SENT') AND isRead = 0") suspend fun markAllRead()
    @Query("SELECT COUNT(*) FROM messages WHERE folder IN ('INBOX','SENT')") suspend fun countAll(): Int
    @Query("SELECT COUNT(*) FROM messages WHERE folder = 'INBOX'") suspend fun countInbox(): Int
    @Query("SELECT COUNT(*) FROM messages WHERE folder = 'SENT'") suspend fun countSent(): Int
    @Query("SELECT * FROM messages WHERE (subject LIKE '%'||:q||'%' OR bodyHtml LIKE '%'||:q||'%' OR fromAddr LIKE '%'||:q||'%' OR toAddr LIKE '%'||:q||'%') AND folder IN ('INBOX','SENT','TRASH') ORDER BY date DESC LIMIT 100")
    suspend fun search(q: String): List<MailMessage>
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY lastReceivedAt DESC, createdAt DESC") suspend fun all(): List<Contact>
    @Query("SELECT * FROM contacts WHERE id = :id") suspend fun byId(id: Long): Contact?
    @Query("SELECT * FROM contacts WHERE email = :email LIMIT 1") suspend fun byEmail(email: String): Contact?
    @Query("SELECT * FROM contacts WHERE isImportant = 1 ORDER BY lastReceivedAt DESC") suspend fun important(): List<Contact>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(c: Contact): Long
    @Update suspend fun update(c: Contact)
    @Query("DELETE FROM contacts WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE contacts SET lastReceivedAt = :t WHERE email = :email") suspend fun touch(email: String, t: Long)
    @Query("SELECT * FROM contacts WHERE email LIKE '%'||:q||'%' OR remark LIKE '%'||:q||'%' OR phone LIKE '%'||:q||'%' OR org LIKE '%'||:q||'%' OR note LIKE '%'||:q||'%' LIMIT 50")
    suspend fun search(q: String): List<Contact>
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY updatedAt DESC") suspend fun all(): List<OutboxItem>
    @Query("SELECT * FROM outbox WHERE id = :id") suspend fun byId(id: Long): OutboxItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(o: OutboxItem): Long
    @Update suspend fun update(o: OutboxItem)
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun delete(id: Long)
}
