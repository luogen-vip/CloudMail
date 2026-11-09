# 云邮 (CloudMail) 📧

> 作者：罗根

一款从零编写的 Android 多账号邮箱客户端（Kotlin + Jetpack Compose），使用真实 IMAP/SMTP 协议收发邮件，无任何模拟功能。

## 功能总览

| 分类 | 功能 |
|---|---|
| 账号 | 多账号添加（QQ / 163 / 139 / Outlook / Gmail / 自定义任意邮箱），密码或授权码登录，验证后自动预填服务器信息，自定义昵称与头像，设主邮箱（默认发信账号），删除账号，编辑账号 |
| 收件 | IMAP 真实收信，后台每 15 分钟轮询 + 新邮件系统通知（显示发件人/内容，可点开、可删除），各账号邮件分开 + 全部邮件聚合、未读邮件、星标邮件、已删除（回收站）、广告邮件自动识别标注 |
| 发件 | SMTP 真实发信（支持任意登录账号发送），多收件人（默认上限 5 个）、抄送、密送、主题、无上限正文、图片/任意文件/录音附件（支持现场录音），名片(资料卡)与纯文本两种发信模式（可按账号设默认），已读回执开关，定时发送（24 小时制） |
| 待发送 | 草稿与定时邮件统一列表，标注原因（草稿/定时），可继续编辑、立即发送、取消定时；已发送后自动从列表移除 |
| 已发送 | 所有发过的邮件（含所用账号、收件人、附件），可撤回、删除、再次回复 |
| 撤回 | 删除服务器上原邮件 + 向收件人发送撤回通知（协议内最大能力，详见下方说明） |
| 附件 | 附件预览/保存到下载文件夹，图片大图预览，视频/音频/其他文件调用系统播放器打开 |
| 联系人 | 自动识别来信人并一键添加，自定义添加，备注名/电话/单位/备注，重要联系人（来信通知明确标注），三种排序（最近来信优先/字母/添加时间），备注优先显示，点联系人直接发信（收件人自动填入），删除联系人 |
| 正文 | HTML 邮件完整渲染（含内联图片位置还原）、cid 内联图片显示、纯文本邮件自动排版、链接自动高亮（含真实域名校验，防止伪链接）、可发送「文字带链接」邮件（插入链接功能） |
| 其他 | 长按/进入详情删除邮件、恢复邮件、彻底删除、清空回收站、全局搜索（邮件+联系人）、备注名优先显示、回复（收件人自动填入）、星标 |

## 撤回与已读回执的真实限制（重要说明）

邮件协议（SMTP/IMAP）本身不支持“从对方收件箱移除邮件”，QQ 邮箱网页版的撤回是腾讯私有接口，第三方客户端无法调用。因此本 App 的撤回是协议内能做到的真实操作：

1. **从你自己的服务器「已发送」文件夹删除原邮件**（服务器级真实删除）；
2. **向收件人真实发送一封「邮件撤回」通知邮件**（与 QQ 内部撤回提示体验一致）；
3. 同一服务商之间（如 QQ→QQ、163→163），服务商网页版可能支持彻底撤回，App 会如实提示。

已读回执通过标准 `Disposition-Notification-To` 头实现，同域邮箱支持较好，跨域邮箱无法保证——这是协议现实，不是 Bug。

## 如何添加邮箱账号（QQ 邮箱示例）

1. 电脑登录 QQ 邮箱网页版 → 设置 → 账户 → 开启「IMAP/SMTP 服务」；
2. 按提示发送短信生成**授权码**；
3. 在云邮「添加邮箱账号」中选择 QQ邮箱 → 填邮箱地址 → **密码填授权码** → 验证并登录。

163 / 139 / Outlook / Gmail 同理：先在服务商设置里开启 IMAP 并生成授权码/应用专用密码。

## 用 GitHub 云端编译 APK（图文步骤）

本仓库已内置 GitHub Actions 编译流水线（`.github/workflows/build.yml`），推上去即自动编译。

1. **生成新令牌**：GitHub → 右上角头像 → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token，勾选仓库的 **Contents: Read and write**、**Actions: Read** 权限（⚠️ 不要把令牌发给任何人/聊天）。
2. **创建仓库**：GitHub → New repository，名称如 `CloudMail`，Public 或 Private 均可。
3. **上传源码**（本 zip 解压后）：
   ```bash
   cd CloudMail
   git init
   git add .
   git commit -m "云邮 v1.0 作者：罗根"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/CloudMail.git
   git push -u origin main
   ```
4. **自动编译**：仓库页 → Actions → 等待 "Build CloudMail APK" 变绿（约 5-8 分钟）。
5. **下载 APK**：
   - 本次编译：Actions → 进入该次运行 → Artifacts → `CloudMail-APK`；
   - 正式版（可选）：Releases → Draft a new release → Publish，APK 会自动挂到 Release。
6. 传到手机安装即可（允许安装未知来源应用）。

## 技术栈

- Kotlin 1.9 + Jetpack Compose (Material 3)
- Room 数据库（账号/邮件/联系人/待发送）
- JavaMail（android-mail 1.6.7）：IMAPS 收信 + SMTP 发信 + IMAP APPEND 保存已发送
- WorkManager：后台收信轮询、定时发送、即时发送
- Coil：头像/图片加载；WebView：HTML 正文渲染

## 项目结构

```
app/src/main/java/com/rogan/cloudmail/
├── CloudMailApp.kt        # 应用入口，注册后台同步
├── MainActivity.kt
├── data/                  # Room 实体/DAO/服务商预设/名片
├── mail/                  # MailService(IMAP/SMTP)、SendWorker、SyncWorker
└── ui/                    # 全部 Compose 界面
```
