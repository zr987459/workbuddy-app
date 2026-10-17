package com.workbuddy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalGatewayServer(private val context: Context, private val port: Int = 8792) {

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
                    thread { handleConnection(client) }
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

    private fun handleConnection(client: Socket) {
        try {
            val input = client.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val output = client.getOutputStream()

            val requestLine = reader.readLine() ?: return client.close()
            val parts = requestLine.split(" ")
            if (parts.size < 2) return client.close()

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")
            val query = fullPath.substringAfter("?", "")

            // 读取 Headers
            var contentLength = 0
            var headerLine: String? = null
            while (reader.readLine().also { headerLine = it } != null) {
                val h = headerLine ?: break
                if (h.isEmpty()) break
                val lower = h.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // 处理 OPTIONS 预检请求
            if (method == "OPTIONS") {
                val preflight = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(preflight.toByteArray())
                output.flush()
                client.close()
                return
            }

            // 读取 Body
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = reader.read(buf, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                String(buf, 0, read)
            } else ""

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
                        put("version", "1.0.2-android")
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

                // 4. API: 模型列表 (OpenAI 格式)
                path == "/v1/models" || path == "/v1/v1/models" -> {
                    val models = JSONArray().apply {
                        put(JSONObject().apply { put("id", "deepseek-v4.1-flash"); put("name", "DeepSeek-V4.1-Flash"); put("object", "model") })
                        put(JSONObject().apply { put("id", "global:deepseek-v4.1-flash"); put("name", "DeepSeek-V4.1-Flash (Global)"); put("object", "model") })
                        put(JSONObject().apply { put("id", "glm-5.2"); put("name", "GLM-5.2"); put("object", "model") })
                        put(JSONObject().apply { put("id", "global:glm-5.2"); put("name", "GLM-5.2 (Global)"); put("object", "model") })
                        put(JSONObject().apply { put("id", "gpt-5.4"); put("name", "GPT-5.4"); put("object", "model") })
                        put(JSONObject().apply { put("id", "grok-4.7"); put("name", "Grok-4.7"); put("object", "model") })
                        put(JSONObject().apply { put("id", "kimi-k3"); put("name", "Kimi-K3"); put("object", "model") })
                        put(JSONObject().apply { put("id", "gemini-3.8-flash"); put("name", "Gemini-3.8-Flash"); put("object", "model") })
                    }
                    val json = JSONObject().apply { put("object", "list"); put("data", models) }
                    sendJsonResponse(output, 200, json.toString())
                }

                // 5. API: OpenAI 标准流式对话接口
                path == "/v1/chat/completions" || path == "/v1/v1/chat/completions" -> {
                    handleOpenAiChatStreaming(output, body)
                }

                // 6. API: Anthropic Messages 协议支持 (给 Claude/Antigravity 客户端用)
                path == "/v1/messages" || path == "/v1/v1/messages" -> {
                    handleAnthropicMessagesStreaming(output, body)
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
        output.write(header.toByteArray())
        output.write(bytes)
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

    private fun handleOpenAiChatStreaming(output: OutputStream, bodyStr: String) {
        // 使用标准的 HTTP/1.1 Chunked Transfer Encoding 确保浏览器 fetch 流式打字机即时推送
        val sseHeader = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache, no-transform\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: keep-alive\r\n\r\n"
        output.write(sseHeader.toByteArray())
        output.flush()

        try {
            val json = JSONObject(bodyStr)
            val model = json.optString("model", "global:deepseek-v4.1-flash")
            val messages = json.optJSONArray("messages") ?: JSONArray()

            TencentGateway.streamChat(model, messages) { line ->
                try {
                    writeChunk(output, line)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            writeChunk(output, "data: {\"error\": {\"message\": \"${e.message}\"}}\n\n")
        } finally {
            try {
                endChunked(output)
            } catch (ignored: Exception) {}
        }
    }

    private fun handleAnthropicMessagesStreaming(output: OutputStream, bodyStr: String) {
        val sseHeader = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache, no-transform\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: keep-alive\r\n\r\n"
        output.write(sseHeader.toByteArray())
        output.flush()

        try {
            val json = JSONObject(bodyStr)
            val modelRaw = json.optString("model", "deepseek-v4.1-flash")

            // 转换 Anthropic 格式为 OpenAI messages 格式
            val openaiMessages = JSONArray()
            val systemPrompt = json.optString("system", TencentGateway.DEFAULT_SYSTEM_PROMPT)
            openaiMessages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            val messagesArr = json.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messagesArr.length()) {
                val m = messagesArr.getJSONObject(i)
                val role = m.optString("role", "user")
                val contentObj = m.opt("content")
                val textContent = when (contentObj) {
                    is String -> contentObj
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until contentObj.length()) {
                            val part = contentObj.optJSONObject(j)
                            if (part != null && part.optString("type") == "text") {
                                sb.append(part.optString("text"))
                            }
                        }
                        sb.toString()
                    }
                    else -> ""
                }
                openaiMessages.put(JSONObject().apply {
                    put("role", role)
                    put("content", textContent)
                })
            }

            val msgId = "msg_${System.currentTimeMillis()}"

            // 1. 发送 Anthropic 启动事件
            writeChunk(output, "event: message_start\ndata: ${JSONObject().apply {
                put("type", "message_start")
                put("message", JSONObject().apply {
                    put("id", msgId)
                    put("type", "message")
                    put("role", "assistant")
                    put("content", JSONArray())
                    put("model", modelRaw)
                })
            }}\n\n")

            writeChunk(output, "event: content_block_start\ndata: ${JSONObject().apply {
                put("type", "content_block_start")
                put("index", 0)
                put("content_block", JSONObject().apply {
                    put("type", "text")
                    put("text", "")
                })
            }}\n\n")

            // 2. 调用底层流式引擎转发并翻译成 Anthropic SSE
            TencentGateway.streamChat(modelRaw, openaiMessages) { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && trimmed != "data: [DONE]" && trimmed.startsWith("data: ")) {
                    try {
                        val chunk = JSONObject(trimmed.substring(6))
                        val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val textDelta = delta?.optString("content", "") ?: ""
                        if (textDelta.isNotEmpty()) {
                            writeChunk(output, "event: content_block_delta\ndata: ${JSONObject().apply {
                                put("type", "content_block_delta")
                                put("index", 0)
                                put("delta", JSONObject().apply {
                                    put("type", "text_delta")
                                    put("text", textDelta)
                                })
                            }}\n\n")
                        }
                    } catch (ignored: Exception) {}
                }
                true
            }

            // 3. 发送 Anthropic 结束事件
            writeChunk(output, "event: content_block_stop\ndata: ${JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 0)
            }}\n\n")

            writeChunk(output, "event: message_delta\ndata: ${JSONObject().apply {
                put("type", "message_delta")
                put("delta", JSONObject().apply {
                    put("stop_reason", "end_turn")
                })
            }}\n\n")

            writeChunk(output, "event: message_stop\ndata: ${JSONObject().apply {
                put("type", "message_stop")
            }}\n\n")

        } catch (e: Exception) {
            writeChunk(output, "event: error\ndata: {\"type\": \"error\", \"error\": {\"message\": \"${e.message}\"}}\n\n")
        } finally {
            try {
                endChunked(output)
            } catch (ignored: Exception) {}
        }
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
        output.write(header.toByteArray())
        output.write(bytes)
    }
}
