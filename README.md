# NUDT-WLAN Auto Login

国防科技大学校园网（深澜 SRun 门户）自动认证工具。开机、休眠唤醒、断线重连后**自动完成网页认证**，从此告别"点 WiFi → 打开浏览器 → 点登录"的三连操作。

同时提供 **Windows**（Python）与 **Android**（Kotlin + Compose）双端实现。

## ✨ 功能特性

- **全自动**：开机 / 休眠唤醒 / WiFi 断线重连后自动认证，全程零操作
- **双端覆盖**：电脑（Windows 计划任务）+ 手机（WorkManager 网络触发）
- **不占资源**：无常驻进程，登录成功即退出，已联网时亚秒级结束
- **双网场景正确**：手机移动数据与 WiFi 同开时，探测与登录请求强制绑定 WiFi 接口，不会被 5G 误判
- **协议通用**：标准深澜（SRun）门户协议，改 `portal_base` / `ac_id` 即可适配其他学校

## 🔐 工作原理

与浏览器点击"登录"按钮发出的请求完全等价，纯 HTTP 协议实现：

```
rad_user_info   → 查询当前认证状态（是否已在线）
get_challenge   → 获取一次性令牌 token
srun_portal     → 提交登录（账号密码经 HMAC-MD5 / SHA-1 / XXTEA / 自定义 Base64 编码校验）
```

加密算法从门户自带前端 JS 1:1 移植；Windows（Python）与 Android（Kotlin）两套实现通过**同一组参考向量逐字节交叉验证**，保证行为一致。

## 🚀 快速开始

### Windows

1. 安装 Python 3.7+（仅标准库，无需 pip 安装任何依赖）
2. 复制 `config.example.json` 为 `config.json`，填入校园网账号密码（详见下方[配置说明](#%EF%B8%8F-配置说明账号密码)）
3. 运行 `python check.py` 确认能正确检测状态（已联网 / 未认证 / 不在校园网）
4. 双击 `install.bat` 注册开机自启计划任务，完成

### Android

1. 用 Android Studio 打开 `android-app/` 构建 APK（或到 [Releases](../../releases) 下载）
2. 安装后打开 App，在界面里填入账号密码 → 点「保存配置」→ 开启「自动登录」开关
3. **关键**：按手机品牌设置后台白名单（国产 ROM 必须，否则后台任务被推迟）：
   - vivo / iQOO：设置 → 电池 → 后台耗电管理 → 允许后台高耗电；设置 → 应用 → 应用启动管理 → 自启动
   - OPPO / 一加：设置 → 应用 → 本应用 → 电池 → 允许完全后台行为
   - 荣耀：设置 → 应用 → 应用启动管理 → 手动管理全部允许

详细说明见 [README-Android.md](README-Android.md)。

## ⚙️ 配置说明（账号密码）

**两端的账号密码就是你在浏览器登录校园网弹出页面时输入的那个账号和密码**（工号/学号 + 对应密码），没有额外的注册步骤。

### Windows：`config.json`

仓库里的 `config.example.json` 是模板，**复制一份改名为 `config.json`**（注意别去掉 `.json` 后缀），填好即可。各字段含义：

| 字段 | 说明 |
|---|---|
| `username` | 校园网账号（学号/工号） |
| `password` | 校园网密码 |
| `portal_base` | 认证服务器地址，NUDT 为 `http://10.20.69.103` |
| `ac_id` | 认证域编号，NUDT 为 `3` |
| `ssid` | 校园 WiFi 名称，用于判断是否在校园网 |
| `check_urls` | 联网探测地址（国内 204 探测点，一般不用改） |
| `total_timeout_sec` / `retry_interval_sec` / `log_max_lines` | 重试与日志参数，保持默认即可 |

> `config.json` 含明文密码，已在 `.gitignore` 中排除，请勿提交或外传。

### Android：App 内直接填

打开 App → 「账号」「密码」输入框填入 → 点「**保存配置**」。改密码时同样操作再保存一次即可。数据存在应用私有目录，卸载即清除。

### 其他学校适配（非 NUDT）

只要是深澜（SRun）门户就能用：连上校园 WiFi 后在弹出的浏览器登录页看地址栏，URL 形如
`http://x.x.x.x/srun_portal_pc?ac_id=N&theme=...`——`x.x.x.x` 填到 `portal_base`，`N` 填到 `ac_id`，再把 `ssid`（Windows）改成你们学校的 WiFi 名。

## ❓ 常见问题

- **登录失败 / 无反应？** Windows 看 `login.log`、Android 看 App 内「日志」，最后几行会写明原因（密码错误、门户不可达、已在线等）
- **检测不到校园网？** 确认 WiFi 已连上、`portal_base` 地址能在浏览器打开
- **电脑换用户/重装？** 重新双击 `install.bat` 即可
- **手机后台不触发？** 99% 是没设后台白名单，见上方 Android 步骤第 3 步

## ⚙️ 触发机制

| 场景 | Windows | Android |
|---|---|---|
| 开机 | 计划任务·用户登录后 20s | BootReceiver → WorkManager |
| 休眠唤醒 | 电源事件（Power-Troubleshooter ID 1） | 系统网络约束自动唤醒 |
| 断线重连 | 网络事件（NetworkProfile ID 10000） | WiFi 网络约束任务链（秒级） |
| 服务器踢线等 | 每 30 分钟兜底 | 每 15 分钟兜底 |
| 不在校园网 | 静默退出，不影响其他网络使用 | 同左 |

## 📁 目录结构

```
├── config.example.json   # 配置模板（复制为 config.json 并填账号密码）
├── srun.py               # Windows 协议层（含全部加密算法）
├── campus_login.py       # Windows 主程序（计划任务入口）
├── check.py              # 状态检测脚本
├── test_cycle.py         # 注销→自动登录→验证 自测脚本（带失败回滚）
├── install.bat / uninstall.bat
├── setup_tasks.ps1 / remove_tasks.ps1
├── android-app/          # Android 工程（Kotlin + Compose）
│   └── app/src/main/java/com/nudt/campuslogin/
│       ├── SrunCrypto.kt     # 加密算法（同 srun.py）
│       ├── SrunApi.kt        # HTTP 协议层 + WiFi 网络绑定
│       ├── LoginEngine.kt    # 检测→登录→验证编排
│       ├── LoginWorker.kt    # WorkManager 后台任务链
│       └── MainActivity.kt   # 界面
└── README-Android.md     # Android 详细文档
```

## 🔒 隐私与安全

- 账号密码仅保存在**本机** `config.json`（Windows）/ 应用私有目录（Android），与浏览器"记住密码"等价
- `config.json` 等敏感文件已列入 `.gitignore`，**不会进入仓库**
- 请勿将个人 `config.json` 提交或分享

## ✅ 已测试环境

- Windows 11 + Python 3.8（实机：登录 0.2s，端到端 2.9s，重启场景通过）
- vivo V2244A / Android 14 / OriginOS（实机：含移动数据同开、无人值守自动重连、WiFi 开关真实场景）

## License

MIT（可自行添加 LICENSE 文件）
