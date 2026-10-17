# WorkBuddy App 核心架构设计

## 1. 架构定位与设计哲学

本项目为 **WorkBuddy 跨平台独立应用**，提供 **Linux 独立服务端** 与 **纯安卓原生自洽版** 两大完整形态。

### 核心设计原则：
1. **彻底摆脱外部依赖**：
   - 安卓端不依赖 Termux、不依赖电脑、不依赖外部可执行二进制（通过 Kotlin 原生 OkHttp3 引擎嵌入执行，彻底消除 Android 10+ SELinux W^X 拦截）。
   - Linux 端单命令极速启动，零 MySQL/Redis 负担。
2. **剔除国内繁琐积分噪点**：
   - 彻底剥离国内复杂的 7 天碎包到期倒计时、领红包、成长任务、猫猫旅行等运营噪点。
   - 专注于：账号健康度管理、大模型 API 转发（OpenAI / Anthropic 双协议）、即时对话验证。
3. **移动端交互重构**：
   - 采用 iOS / Material 3 浮动胶囊底栏设计（概览 · 账号 · 接入 · 对话）。
   - 全链路触屏卡片流，支持实时流式 SSE 打字机与折叠式思维链抽屉。

---

## 2. 双版本实现规范

### 🟢 版本一：纯安卓独立版 (`android/`)
- **运行环境**：Android 7.0 ~ Android 14+ (ARM64-v8a)
- **底层网关**：`LocalGatewayServer.kt`（Kotlin 原生嵌入式 HTTP 服务，默认端口 `8800`）
- **上游通信**：`TencentGateway.kt`（基于 Google/Square 标准 `OkHttp3` 驱动，支持 HTTP/2 长连接与 SSE 流式输出）
- **账号持久化**：`AccountVault.kt`（Android 原生存储）
- **后台守护**：`GatewayService.kt`（前台服务 + 通知栏守护，防止系统休眠）
- **云端构建**：GitHub Actions 自动编译、签名并发布 `WorkBuddyApp.apk`

### 🔵 版本二：Linux 独立服务端 (`linux/`)
- **运行环境**：Linux x86_64 / aarch64（支持服务器、VPS、Docker、树莓派等）
- **底层网关**：FastAPI + Uvicorn + HTTPX 异步连接池
- **提供接口**：
  - OpenAI 兼容：`/v1/chat/completions`、`/v1/models`
  - Anthropic 兼容：`/v1/messages`
  - Web 控制台：`/`、`/m`
- **极速启动**：`bash linux/start.sh` 或 `python3 linux/main.py --port 8800`

---

## 3. 共享前端结构 (`web/`)
- `index.html`：现代响应式单页
- `style.css`：Material 3 Tokens 暗夜 Slate 高对比度色板
- `app.js`：无依赖纯 Vanilla JS，支持流式 SSE 打字机与思维链动态折叠
- `manifest.json`：支持 PWA 离线添加到手机桌面
