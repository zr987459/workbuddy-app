# WorkBuddy App

**WorkBuddy 跨平台独立应用 · 国际版大模型直驱中控**

支持腾讯 **WorkBuddy 国际版 (workbuddy.ai)** 与国内版大模型直驱，提供标准 **OpenAI** (`/v1/chat/completions`) 与 **Anthropic** (`/v1/messages`) 双协议中转。彻底精简国内碎包积分、做任务、领红包等噪点，专注于纯净的大模型 API 转发与高颜值移动端控制台。

---

## 📱 双版本发布

### 1. 纯安卓独立版 (Android APK)
- **100% 独立离线自洽**：内置原生 Kotlin + OkHttp3 网关引擎，**无需电脑、无需 Termux、无需后台敲命令**，手机桌面点开即用。
- **iOS / Material 3 移动端 UI**：浮动胶囊底栏、触屏卡片流、即时流式打字机与折叠式思维链抽屉。
- **下载安装**：前往 [Releases 页面](../../releases) 直接下载最新的 `WorkBuddyApp.apk` 即可安装使用。

### 2. Linux 独立服务端 (Linux Edition)
适用于 Linux 服务器、VPS、树莓派或容器化部署：
```bash
cd linux
bash start.sh
```
默认监听 `0.0.0.0:8792`，浏览器访问即可打开现代管理控制台。

---

## 🛠️ 核心功能

* **国际版一键秒级授权**：一键调用官方 OAuth 设备授权流，浏览器确认登录后后台 2.5 秒自动捕获并完成入库。
* **原生支持大模型矩阵**：
  * `DeepSeek-V4.1-Flash`
  * `GLM-5.2` (1M 上下文)
  * `GPT-5.4`
  * `Grok-4.7`
  * `Kimi-K3`
  * `Gemini-3.8-Flash`
* **双标准协议无缝兼容**：
  * OpenAI 兼容地址：`http://127.0.0.1:8792/v1`
  * Anthropic 兼容地址：`http://127.0.0.1:8792/v1/messages`
* **本地免鉴权极简模式**：针对个人单机独立环境，API Key 填任意字符即可直接调用。

---

## 📄 开源许可
MIT License.
