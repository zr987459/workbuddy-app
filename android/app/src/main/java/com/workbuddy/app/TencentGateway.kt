package com.workbuddy.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object TencentGateway {

    const val UPSTREAM_GLOBAL = "https://www.workbuddy.ai"
    const val UPSTREAM_CN = "https://copilot.tencent.com"
    const val CLIENT_UA = "WorkBuddy/5.5.4 WorkBuddy AI/5.5.4 CLI/2.137.1"
    const val DEFAULT_SYSTEM_PROMPT = "You are WorkBuddy, an advanced AI programming assistant developed by Tencent."

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun sha256Prefix(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder()
        for (b in bytes) {
            val h = Integer.toHexString(0xFF and b.toInt())
            if (h.length == 1) hex.append('0')
            hex.append(h)
        }
        return hex.toString().take(36)
    }

    fun startLogin(realm: String = "global"): JSONObject {
        val base = if (realm == "global") UPSTREAM_GLOBAL else UPSTREAM_CN
        val origin = if (realm == "global") UPSTREAM_GLOBAL else "https://www.codebuddy.cn"

        val req = Request.Builder()
            .url("$base/v2/plugin/auth/state?platform=CLI")
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("User-Agent", CLIENT_UA)
            .post("{}".toRequestBody(jsonMedia))
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            if (json.optInt("code") == 0 && json.has("data")) {
                val data = json.getJSONObject("data")
                return JSONObject().apply {
                    put("ok", true)
                    put("state", data.optString("state"))
                    put("url", data.optString("authUrl"))
                    put("realm", realm)
                }
            }
            return JSONObject().apply {
                put("ok", false)
                put("error", json.optString("msg", "未知错误"))
            }
        }
    }

    fun pollLogin(state: String): JSONObject {
        for (tuple in listOf(
            Triple(UPSTREAM_GLOBAL, UPSTREAM_GLOBAL, "global"),
            Triple(UPSTREAM_CN, "https://www.codebuddy.cn", "cn")
        )) {
            val base = tuple.first
            val origin = tuple.second
            val realm = tuple.third

            try {
                val req = Request.Builder()
                    .url("$base/v2/plugin/auth/token?state=$state")
                    .header("Origin", origin)
                    .header("Referer", "$origin/")
                    .header("User-Agent", CLIENT_UA)
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optInt("code") == 0 && json.has("data")) {
                        val tok = json.getJSONObject("data")
                        val accessToken = tok.optString("accessToken")
                        if (accessToken.isNotEmpty()) {
                            var uid = "user_${System.currentTimeMillis()}"
                            var nickname = "WorkBuddy用户"

                            try {
                                val acctReq = Request.Builder()
                                    .url("$base/v2/plugin/login/account?state=$state")
                                    .header("Origin", origin)
                                    .header("Referer", "$origin/")
                                    .header("User-Agent", CLIENT_UA)
                                    .header("Authorization", "Bearer $accessToken")
                                    .get()
                                    .build()
                                okHttpClient.newCall(acctReq).execute().use { ar ->
                                    val ab = ar.body?.string() ?: ""
                                    val aj = JSONObject(ab).optJSONObject("data")
                                    if (aj != null && aj.has("uid")) {
                                        uid = aj.optString("uid")
                                        nickname = aj.optString("nickname", nickname)
                                    }
                                }
                            } catch (ignored: Exception) {}

                            // 保存到本地账号库
                            AccountVault.saveAccount(uid, nickname, realm, accessToken, tok.optString("refreshToken", ""))

                            return JSONObject().apply {
                                put("ok", true)
                                put("done", true)
                                put("uid", uid)
                                put("nickname", nickname)
                                put("realm", realm)
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        return JSONObject().apply {
            put("ok", true)
            put("done", false)
            put("message", "waiting for login")
        }
    }

    fun chatSync(modelRaw: String, messagesArray: JSONArray): JSONObject {
        val sb = StringBuilder()
        var lastChunkPromptTokens = 20
        var lastChunkCompTokens = 20
        var upstreamError: String? = null

        streamChat(modelRaw, messagesArray) { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("data: ") && trimmed != "data: [DONE]") {
                try {
                    val chunk = JSONObject(trimmed.substring(6))
                    if (chunk.has("error")) {
                        upstreamError = chunk.optString("error")
                    }
                    val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val content = delta?.optString("content", "") ?: ""
                    if (content.isNotEmpty()) {
                        sb.append(content)
                    }
                    val usage = chunk.optJSONObject("usage")
                    if (usage != null) {
                        lastChunkPromptTokens = usage.optInt("prompt_tokens", lastChunkPromptTokens)
                        lastChunkCompTokens = usage.optInt("completion_tokens", lastChunkCompTokens)
                    }
                } catch (ignored: Exception) {}
            }
            true
        }

        if (upstreamError != null) {
            throw IOException("Upstream error: $upstreamError")
        }

        val text = sb.toString()
        if (text.isEmpty()) {
            throw IOException("Upstream returned empty completion")
        }

        return JSONObject().apply {
            put("content", text)
            put("prompt_tokens", lastChunkPromptTokens)
            put("completion_tokens", maxOf(lastChunkCompTokens, text.length / 2))
        }
    }

    fun streamChat(
        modelRaw: String,
        messagesArray: JSONArray,
        onLine: (String) -> Boolean
    ) {
        val modelLower = modelRaw.lowercase()
        val realm = if (modelLower.startsWith("cn:")) "cn" else "global"
        val modelName = modelLower.removePrefix("global:").removePrefix("cn:")

        val acc = AccountVault.getActiveAccount(realm) ?: throw IllegalStateException("未登录账号，请先在面板添加账号")
        val base = if (realm == "global") UPSTREAM_GLOBAL else UPSTREAM_CN
        val uid = acc.optString("uid", "user_default")

        // 规范化 messages: 确保第一条是 system
        val normMessages = JSONArray()
        var hasSystem = false
        if (messagesArray.length() > 0) {
            val first = messagesArray.getJSONObject(0)
            if (first.optString("role") == "system") {
                hasSystem = true
            }
        }
        if (!hasSystem) {
            normMessages.put(JSONObject().apply {
                put("role", "system")
                put("content", DEFAULT_SYSTEM_PROMPT)
            })
        }
        for (i in 0 until messagesArray.length()) {
            normMessages.put(messagesArray.getJSONObject(i))
        }

        val payload = JSONObject().apply {
            put("model", modelName)
            put("messages", normMessages)
            put("stream", true)
        }

        val machineId = sha256Prefix("wb2a:machine:$uid")
        val sessionId = sha256Prefix("wb2a:session:$uid")

        // 双路径自动回落：/console 兜底 /v2 (对齐腾讯 2026-10-17 渠道校验规则)
        val endpoints = listOf("$base/console/chat/completions", "$base/v2/chat/completions")

        for (url in endpoints) {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${acc.optString("accessToken")}")
                .header("Content-Type", "application/json")
                .header("User-Agent", CLIENT_UA)
                .header("Origin", base)
                .header("Referer", "$base/")
                .header("X-User-Id", uid)
                .header("X-Product", "WorkBuddy")
                .header("X-Agent-Purpose", "conversation")
                .header("X-IDE-Name", "WorkBuddy")
                .header("X-IDE-Type", "WorkBuddy")
                .header("X-IDE-Version", "5.5.4")
                .header("X-Machine-ID", machineId)
                .header("X-Session-ID", sessionId)
                .header("X-Conversation-ID", UUID.randomUUID().toString().replace("-", ""))
                .header("X-Conversation-Request-ID", UUID.randomUUID().toString().replace("-", ""))
                .header("X-Conversation-Message-ID", UUID.randomUUID().toString().replace("-", ""))
                .header("X-Root-Request-ID", UUID.randomUUID().toString().replace("-", ""))
                .header("X-Trace-ID", UUID.randomUUID().toString().replace("-", ""))

            if (realm == "global") {
                reqBuilder.header("X-No-Enterprise-Id", "1")
                reqBuilder.header("X-Domain", "www.workbuddy.ai")
            } else {
                reqBuilder.header("X-No-Enterprise-Id", "1")
                reqBuilder.header("X-No-Department-Info", "1")
            }

            val req = reqBuilder.post(payload.toString().toRequestBody(jsonMedia)).build()

            try {
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (url == endpoints.first()) {
                            // 尝试下一条候选路径
                            return@use
                        }
                        val err = resp.body?.string() ?: "HTTP ${resp.code}"
                        onLine("data: ${JSONObject().apply { put("error", err) }}\n\n")
                        return
                    }

                    val source = resp.body?.source() ?: return
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isNotEmpty()) {
                            val keepGoing = onLine("$line\n\n")
                            if (!keepGoing) break
                        }
                    }
                    return // 成功完成
                }
            } catch (e: Exception) {
                if (url == endpoints.last()) {
                    onLine("data: ${JSONObject().apply { put("error", e.message ?: "Network error") }}\n\n")
                }
            }
        }
    }
}
