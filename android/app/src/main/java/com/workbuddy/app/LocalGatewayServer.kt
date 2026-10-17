package com.workbuddy.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalGatewayServer(private val context: Context, private val port: Int = 8800) {

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
                        put("version", "1.0.3-android")
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

                // 5. API: OpenAI 标准流式与非流式对话
                path == "/v1/chat/completions" || path == "/v1/v1/chat/completions" -> {
                    handleOpenAiChat(output, body)
                }

                // 6. API: Anthropic Messages 协议全功能支持（流式与非流式）
                path == "/v1/messages" || path == "/v1/v1/messages" -> {
                    handleAnthropicMessages(output, body)
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

    private fun handleOpenAiChat(output: OutputStream, bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val isStream = json.optBoolean("stream", false)
            val model = json.optString("model", "global:deepseek-v4.1-flash")
            val messages = json.optJSONArray("messages") ?: JSONArray()

            if (!isStream) {
                // 非流式单次响应
                val res = TencentGateway.chatSync(model, messages)
                val reply = res.optString("content")
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
                            })
                            put("finish_reason", "stop")
                        })
                    })
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", promptTokens)
                        put("completion_tokens", compTokens)
                        put("total_tokens", promptTokens + compTokens)
                    })
                }
                sendJsonResponse(output, 200, oaiResp.toString())
            } else {
                // 流式 Chunked 传输
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
                    TencentGateway.streamChat(model, messages) { line ->
                        try {
                            writeChunk(output, line)
                            true
                        } catch (e: Exception) {
                            false
                        }
                    }
                } catch (e: Exception) {
                    writeChunk(output, "data: ${JSONObject().apply { put("error", e.message) }}\n\n")
                } finally {
                    endChunked(output)
                }
            }
        } catch (e: Exception) {
            sendErrorJson(output, 500, e.message ?: "Chat failed")
        }
    }

    private fun handleAnthropicMessages(output: OutputStream, bodyStr: String) {
        try {
            val json = JSONObject(bodyStr)
            val isStream = json.optBoolean("stream", false)
            val modelRaw = json.optString("model", "deepseek-v4.1-flash")

            // 转换 Anthropic 格式为 OpenAI messages
            val openaiMessages = JSONArray()
            val systemObj = json.opt("system")
            val systemPrompt = when (systemObj) {
                is String -> systemObj.ifEmpty { TencentGateway.DEFAULT_SYSTEM_PROMPT }
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (k in 0 until systemObj.length()) {
                        val part = systemObj.optJSONObject(k)
                        if (part != null && part.optString("type") == "text") {
                            sb.append(part.optString("text"))
                        }
                    }
                    sb.toString().ifEmpty { TencentGateway.DEFAULT_SYSTEM_PROMPT }
                }
                else -> TencentGateway.DEFAULT_SYSTEM_PROMPT
            }
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

            if (!isStream) {
                // 1. 非流式响应（专门应对客户端的测试按钮）
                val res = TencentGateway.chatSync(modelRaw, openaiMessages)
                val replyContent = res.optString("content")
                val promptTokens = res.optInt("prompt_tokens", 20)
                val compTokens = res.optInt("completion_tokens", 20)

                val anthropicResp = JSONObject().apply {
                    put("id", msgId)
                    put("type", "message")
                    put("role", "assistant")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", replyContent)
                        })
                    })
                    put("model", modelRaw)
                    put("stop_reason", "end_turn")
                    put("stop_sequence", JSONObject.NULL)
                    put("usage", JSONObject().apply {
                        put("input_tokens", promptTokens)
                        put("output_tokens", compTokens)
                    })
                }

                sendJsonResponse(output, 200, anthropicResp.toString())
            } else {
                // 2. 流式响应 (携带完整 Anthropic 官方规范字段)
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
                    // message_start: 包含完整的 usage 与 stop_reason 字段
                    writeChunk(output, "event: message_start\ndata: ${JSONObject().apply {
                        put("type", "message_start")
                        put("message", JSONObject().apply {
                            put("id", msgId)
                            put("type", "message")
                            put("role", "assistant")
                            put("content", JSONArray())
                            put("model", modelRaw)
                            put("stop_reason", JSONObject.NULL)
                            put("stop_sequence", JSONObject.NULL)
                            put("usage", JSONObject().apply {
                                put("input_tokens", 20)
                                put("output_tokens", 0)
                            })
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

                    writeChunk(output, "event: ping\ndata: ${JSONObject().apply { put("type", "ping") }}\n\n")

                    var outputTokenCount = 0

                    TencentGateway.streamChat(modelRaw, openaiMessages) { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && trimmed != "data: [DONE]" && trimmed.startsWith("data: ")) {
                            try {
                                val chunk = JSONObject(trimmed.substring(6))
                                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                val textDelta = delta?.optString("content", "") ?: ""
                                if (textDelta.isNotEmpty()) {
                                    outputTokenCount++
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

                    writeChunk(output, "event: content_block_stop\ndata: ${JSONObject().apply {
                        put("type", "content_block_stop")
                        put("index", 0)
                    }}\n\n")

                    writeChunk(output, "event: message_delta\ndata: ${JSONObject().apply {
                        put("type", "message_delta")
                        put("delta", JSONObject().apply {
                            put("stop_reason", "end_turn")
                            put("stop_sequence", JSONObject.NULL)
                        })
                        put("usage", JSONObject().apply {
                            put("output_tokens", maxOf(outputTokenCount, 1))
                        })
                    }}\n\n")

                    writeChunk(output, "event: message_stop\ndata: ${JSONObject().apply {
                        put("type", "message_stop")
                    }}\n\n")
                } catch (e: Exception) {
                    // 中途出错：发送 error 事件通知客户端
                    try {
                        writeChunk(output, "event: error\ndata: ${JSONObject().apply {
                            put("type", "error")
                            put("error", JSONObject().apply {
                                put("type", "upstream_error")
                                put("message", e.message ?: "upstream streaming failed")
                            })
                        }}\n\n")
                    } catch (ignored: Exception) {}
                } finally {
                    endChunked(output)
                }
            }
        } catch (e: Exception) {
            sendErrorJson(output, 500, e.message ?: "Anthropic request failed")
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
