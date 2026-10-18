package com.workbuddy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

class LocalGatewayServer(
    private val context: Context,
    private val port: Int = 8800,
    private val onBusy: () -> Unit = {},
    private val onIdle: () -> Unit = {}
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "LocalGatewayServer") {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true // 减少小包网络延迟
                    client.soTimeout = 120000 // 120 秒空闲读超时，适应超长思考模型首字返回
                    thread {
                        onBusy()
                        try {
                            client.use { handleConnection(it) }
                        } finally {
                            onIdle()
                        }
                    }
                }
            } catch (e: Exception) {
                // server closed
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
    }

    private fun readHeaderBlock(input: InputStream, max: Int = 64 * 1024): ByteArray? {
        val buf = ByteArrayOutputStream(512)
        var lineLen = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else throw EOFException("headers truncated")
            if (buf.size() >= max) throw IOException("request header too large")
            buf.write(b)
            when (b) {
                '\n'.code -> {
                    if (lineLen == 0) return buf.toByteArray() // 遇到空行即头部结束
                    lineLen = 0
                }
                '\r'.code -> {}
                else -> lineLen++
            }
        }
    }

    private fun readRequestBody(input: InputStream, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val buf = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            val n = input.read(buf, off, contentLength - off)
            if (n < 0) throw EOFException("client closed: got $off / $contentLength bytes")
            off += n
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun handleConnection(client: Socket) {
        try {
            // 全连接统一使用一个 BufferedInputStream，严防 Reader 预读吃掉 Body 字节
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            val rawHead = readHeaderBlock(input) ?: return client.close()
            val headLines = String(rawHead, Charsets.ISO_8859_1).split('\n')
                .map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
            if (headLines.isEmpty()) return client.close()

            val parts = headLines[0].split(' ')
            if (parts.size < 2) return client.close()

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")

            // 解析 Headers (小写标准化)
            val headers = HashMap<String, String>()
            for (l in headLines.drop(1)) {
                val i = l.indexOf(':')
                if (i <= 0) continue
                val k = l.substring(0, i).trim().lowercase()
                val v = l.substring(i + 1).trim()
                headers[k] = headers[k]?.let { "$it, $v" } ?: v
            }

            // 处理 OPTIONS 预检请求
            if (method == "OPTIONS") {
                val preflight = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(preflight.toByteArray(Charsets.US_ASCII))
                output.flush()
                client.close()
                return
            }

            // 读取 Body：使用精确字节数读取，彻底根除多字节中文卡死挂起
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = readRequestBody(input, contentLength)

            // 路由派发
            when {
                // 1. API: 状态概览
                path == "/panel/api/overview" || path == "/m/api/overview" -> {
                    val accounts = AccountVault.getAccounts()
                    val healthy = (0 until accounts.length()).count {
                        !accounts.getJSONObject(it).optBoolean("disabled", false)
                    }
                    val json = JSONObject().apply {
                        put("healthy", healthy)
                        put("total", accounts.length())
                        put("accounts", accounts)
                        put("auth_required", false)
                        put("version", "1.1.3-android")
                    }
                    sendJsonResponse(output, 200, json.toString())
                }

                // 2. API: 发起授权
                path == "/panel/api/login/start" || path == "/m/api/auth/start" -> {
                    val realm = try { JSONObject(body).optString("realm", "global") } catch (e: Exception) { "global" }
                    val res = TencentGateway.startLogin(realm)
                    sendJsonResponse(output, 200, res.toString())
                }

                // 3. API: 轮询授权
                path == "/panel/api/login/poll" || path == "/m/api/auth/poll" -> {
                    val state = query.split("&").find { it.startsWith("state=") }?.substringAfter("state=") ?: ""
                    val res = TencentGateway.pollLogin(java.net.URLDecoder.decode(state, "UTF-8"))
                    sendJsonResponse(output, 200, res.toString())
                }

                // 4. API: 腾讯 WorkBuddy 国际版官方真实模型清单（标注真实官方倍率：DeepSeek/Hy4 国际版限免 0.00x！）
                path == "/v1/models" || path == "/v1/v1/models" -> {
                    val models = JSONArray().apply {
                        put(JSONObject().apply { put("id", "deepseek-v4.1-flash-1017"); put("name", "DeepSeek-V4.1-Flash-1017 [0.00x 限免免费 · 多模态+思考]"); put("credits", "0.00x (Free)"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "global:deepseek-v4.1-flash-1017"); put("name", "DeepSeek-V4.1-Flash-1017 (Global) [0.00x 限免免费]"); put("credits", "0.00x (Free)"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "deepseek-v4.1-flash"); put("name", "DeepSeek-V4.1-Flash [0.00x 限免免费]"); put("credits", "0.00x (Free)"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "global:deepseek-v4.1-flash"); put("name", "DeepSeek-V4.1-Flash (Global) [0.00x 限免免费]"); put("credits", "0.00x (Free)"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "hy4-preview"); put("name", "Hy4-Preview [0.00x 限免免费 · 混元深度思考]"); put("credits", "0.00x (Free)"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "hy3"); put("name", "Hy3 [0.00x 限免免费]"); put("credits", "0.00x (Free)"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "gpt-6-astra"); put("name", "GPT-6-Astra [6.67x · 国际版专属顶级旗舰]"); put("credits", "6.67x"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "gpt-5.6-sol"); put("name", "GPT-5.6-Sol [国际版专属]"); put("supports_images", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "gpt-5.4"); put("name", "GPT-5.4 [国际版原生多模态]"); put("supports_images", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "grok-4.7"); put("name", "Grok-4.7 [国际版专属]"); put("object", "model") })
                        put(JSONObject().apply { put("id", "gemini-3.5-flash"); put("name", "Gemini-3.5-Flash [国际版原生多模态]"); put("supports_images", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "glm-5.3-flash"); put("name", "GLM-5.3-Flash [极低消耗]"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "glm-5.3"); put("name", "GLM-5.3 [最新旗舰]"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "glm-5.2"); put("name", "GLM-5.2 [1M 长上下文]"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "kimi-k3"); put("name", "Kimi-K3 [长文本深度推理]"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "kimi-k2.8-preview"); put("name", "Kimi-K2.8-Preview [深度推理]"); put("supports_images", true); put("supports_reasoning", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "claude-3.7-sonnet"); put("name", "Claude-3.7-Sonnet (官方映射 default-1.1)"); put("supports_images", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "claude-4.0-sonnet"); put("name", "Claude-4.0-Sonnet (官方映射 default-1.2)"); put("supports_images", true); put("object", "model") })
                        put(JSONObject().apply { put("id", "auto"); put("name", "Auto [自动智能调度]"); put("object", "model") })
                    }
                    val json = JSONObject().apply { put("object", "list"); put("data", models) }
                    sendJsonResponse(output, 200, json.toString())
                }

                // 5. API: OpenAI 标准流式与非流式对话 (完整透传 tools、reasoning_content、多模态)
                path == "/v1/chat/completions" || path == "/v1/v1/chat/completions" -> {
                    handleOpenAiChat(output, body, headers)
                }

                // 6. API: Anthropic Messages 协议全功能支持（双向映射 tools 与 thinking）
                path == "/v1/messages" || path == "/v1/v1/messages" -> {
                    handleAnthropicMessages(output, body, headers)
                }

                // 7. 静态前端资源
                else -> {
                    serveStaticAsset(output, path)
                }
            }

            output.flush()
            client.close()
        } catch (e: Exception) {
            try { client.close() } catch (ignored: Exception) {}
        }
    }

    private fun sendJsonResponse(output: OutputStream, code: Int, jsonStr: String) {
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeChunk(output: OutputStream, chunkStr: String) {
        val bytes = chunkStr.toByteArray(Charsets.UTF_8)
        val hex = Integer.toHexString(bytes.size)
        output.write("$hex\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun endChunked(output: OutputStream) {
        output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun errorPayload(e: Exception): Pair<Int, JSONObject> {
        val (status, type) = when {
            e is UpstreamException && e.code == 11128 -> 403 to "upstream_channel_rejected"
            e is UpstreamException && e.status == 403 -> 403 to "upstream_forbidden"
            e is UpstreamException -> 502 to "upstream_error"
            else -> 500 to "gateway_error"
        }
        val err = JSONObject().put("message", e.message ?: type).put("type", type)
        if (e is UpstreamException) err.put("code", e.code ?: e.status)
        return status to JSONObject().put("type", "error").put("error", err)
    }

    private fun handleOpenAiChat(output: OutputStream, bodyStr: String, reqHeaders: Map<String, String>) {
        try {
            val json = JSONObject(bodyStr)
            val isStream = json.optBoolean("stream", false)
            val model = json.optString("model", "deepseek-v4.1-flash-1017")
            val convId = ConversationIds.resolve(reqHeaders, json)

            if (!isStream) {
                // 非流式响应：上游透传，完整包含 reasoning_content 与 tool_calls
                try {
                    val res = TencentGateway.chatSync(json, convId)
                    val reply = res.optString("content")
                    val reasoning = res.optString("reasoning_content")
                    val toolCalls = res.optJSONArray("tool_calls")
                    val promptTokens = res.optInt("prompt_tokens", 20)
                    val compTokens = res.optInt("completion_tokens", 20)

                    val oaiResp = JSONObject().apply {
                        put("id", "chatcmpl_${System.currentTimeMillis()}")
                        put("object", "chat.completion")
                        put("created", System.currentTimeMillis() / 1000)
                        put("model", model)
                        put("choices", JSONArray().apply {
                            put(JSONObject().apply {
                                put("index", 0)
                                put("message", JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", reply)
                                    if (reasoning.isNotEmpty()) {
                                        put("reasoning_content", reasoning)
                                    }
                                    if (toolCalls != null) {
                                        put("tool_calls", toolCalls)
                                    }
                                })
                                put("finish_reason", if (toolCalls != null) "tool_calls" else "stop")
                            })
                        })
                        put("usage", JSONObject().apply {
                            put("prompt_tokens", promptTokens)
                            put("completion_tokens", compTokens)
                            put("total_tokens", promptTokens + compTokens)
                        })
                    }
                    sendJsonResponse(output, 200, oaiResp.toString())
                } catch (e: Exception) {
                    val (st, body) = errorPayload(e)
                    sendJsonResponse(output, st, body.toString())
                }
            } else {
                // 流式 Chunked 传输：按字节原样转发 SSE 帧，上游 reasoning_content 与 tool_calls 天然完整送达
                val sseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache, no-transform\r\n" +
                        "X-Accel-Buffering: no\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                output.write(sseHeader.toByteArray(Charsets.US_ASCII))
                output.flush()

                try {
                    TencentGateway.streamChat(json, convId) { line ->
                        try {
                            writeChunk(output, line)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                } catch (e: Exception) {
                    val (_, payload) = errorPayload(e)
                    writeChunk(output, "data: $payload\n\n")
                    writeChunk(output, "data: [DONE]\n\n")
                } finally {
                    endChunked(output)
                }
            }
        } catch (e: Exception) {
            val (st, body) = errorPayload(e)
            sendJsonResponse(output, st, body.toString())
        }
    }

    private fun handleAnthropicMessages(output: OutputStream, bodyStr: String, reqHeaders: Map<String, String>) {
        try {
            val json = JSONObject(bodyStr)
            val isStream = json.optBoolean("stream", false)
            val modelRaw = json.optString("model", "deepseek-v4.1-flash-1017")

            // 采用 Hark 工业级转换器：完美解决 tool_use / tool_result 配对、图片、多轮会话锚点
            val convId = ConversationIds.resolve(reqHeaders, json)
            val oaiReq = AnthropicToOpenAI.convertRequest(json)

            if (!isStream) {
                // 1. 非流式响应
                try {
                    val oaiResp = TencentGateway.chatSync(oaiReq, convId)
                    val anthropicResp = AnthropicToOpenAI.convertResponse(oaiResp, modelRaw)
                    sendJsonResponse(output, 200, anthropicResp.toString())
                } catch (e: Exception) {
                    val (st, body) = errorPayload(e)
                    sendJsonResponse(output, st, body.toString())
                }
            } else {
                // 2. 流式响应 (支持 thinking 与 tool_use 事件)
                val sseHeader = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache, no-transform\r\n" +
                        "X-Accel-Buffering: no\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                output.write(sseHeader.toByteArray(Charsets.US_ASCII))
                output.flush()

                val translator = AnthropicStreamTranslator(modelRaw) { event, data ->
                    writeChunk(output, "event: $event\ndata: $data\n\n")
                }

                try {
                    TencentGateway.streamChat(oaiReq, convId) { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data: ") && trimmed != "data: [DONE]") {
                            try {
                                val chunk = JSONObject(trimmed.substring(6))
                                translator.onChunk(chunk)
                            } catch (ignored: Exception) {}
                        }
                        true
                    }
                    translator.finish()
                } catch (e: Exception) {
                    try {
                        val (_, body) = errorPayload(e)
                        translator.abort(body)
                    } catch (ignored: Exception) {}
                } finally {
                    endChunked(output)
                }
            }
        } catch (e: Exception) {
            val (st, body) = errorPayload(e)
            sendJsonResponse(output, st, body.toString())
        }
    }

    private fun sendErrorJson(output: OutputStream, code: Int, msg: String) {
        val json = JSONObject().apply { put("error", JSONObject().apply { put("message", msg) }) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code Error\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun serveStaticAsset(output: OutputStream, path: String) {
        val cleanPath = when {
            path == "/" || path.isEmpty() || path == "/index.html" -> "web/index.html"
            path.startsWith("/m/") -> "web/" + path.removePrefix("/m/")
            path == "/m" -> "web/index.html"
            else -> "web/" + path.removePrefix("/")
        }

        val mime = when {
            cleanPath.endsWith(".html") -> "text/html; charset=utf-8"
            cleanPath.endsWith(".css") -> "text/css; charset=utf-8"
            cleanPath.endsWith(".js") -> "application/javascript; charset=utf-8"
            cleanPath.endsWith(".json") -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }

        val bytes = try {
            context.assets.open(cleanPath).use { it.readBytes() }
        } catch (e: Exception) {
            context.assets.open("web/index.html").use { it.readBytes() }
        }

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $mime\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
