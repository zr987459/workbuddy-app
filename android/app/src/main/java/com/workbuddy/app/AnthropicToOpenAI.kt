package com.workbuddy.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// org.json 辅助：optString 读到 JSON null 会返回字符串 "null"
private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)
private fun newId(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(24)

internal fun systemText(sys: Any?): String? = when (sys) {
    is String -> sys.ifBlank { null }
    is JSONArray -> (0 until sys.length()).mapNotNull { sys.optJSONObject(it) }
        .filter { it.optString("type") == "text" }.joinToString("\n") { it.str("text") }.ifBlank { null }
    else -> null
}

internal fun parseArgs(s: String): JSONObject =
    try { if (s.isBlank()) JSONObject() else JSONObject(s) } catch (e: Exception) { JSONObject().put("_raw", s) }

internal fun mapFinish(r: String, hasTools: Boolean): String = when (r) {
    "tool_calls", "function_call" -> "tool_use"
    "length" -> "max_tokens"
    else -> if (hasTools) "tool_use" else "end_turn"
}

object AnthropicToOpenAI {

    // ================= 请求：Anthropic -> OpenAI =================
    fun convertRequest(req: JSONObject): JSONObject {
        val out = JSONObject().put("model", req.str("model"))
        if (req.has("max_tokens")) out.put("max_tokens", req.getInt("max_tokens"))
        for (k in listOf("temperature", "top_p")) if (!req.isNull(k)) out.put(k, req.get(k))
        req.optJSONArray("stop_sequences")?.let { out.put("stop", it) }
        if (req.optBoolean("stream")) {
            out.put("stream", true)
            out.put("stream_options", JSONObject().put("include_usage", true))
        }

        val msgs = JSONArray()
        systemText(req.opt("system"))?.let { msgs.put(JSONObject().put("role", "system").put("content", it)) }
        val src = req.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until src.length()) {
            val m = src.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "assistant" -> msgs.put(convertAssistant(m.opt("content")))
                "user" -> convertUser(m.opt("content")).forEach { msgs.put(it) }
            }
        }
        out.put("messages", repairToolPairing(msgs))

        val tools = req.optJSONArray("tools")?.let { convertTools(it) }
        if (tools != null && tools.length() > 0) {
            out.put("tools", tools)
            req.optJSONObject("tool_choice")?.let { tc ->
                convertToolChoice(tc)?.let { out.put("tool_choice", it) }
                if (tc.optBoolean("disable_parallel_tool_use")) out.put("parallel_tool_calls", false)
            }
        }
        return out
    }

    // assistant: text 合并为 content，tool_use -> tool_calls，thinking -> reasoning_content
    private fun convertAssistant(content: Any?): JSONObject {
        val msg = JSONObject().put("role", "assistant")
        if (content is String) return msg.put("content", content)
        val blocks = content as? JSONArray ?: return msg.put("content", "")
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> text.append(b.str("text"))
                "thinking" -> reasoning.append(b.str("thinking"))
                "tool_use" -> calls.put(JSONObject()
                    .put("id", b.getString("id"))
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", b.getString("name"))
                        // OpenAI 要求 arguments 是 JSON 字符串，不是对象
                        .put("arguments", (b.optJSONObject("input") ?: JSONObject()).toString())))
            }
        }
        if (reasoning.isNotEmpty()) msg.put("reasoning_content", reasoning.toString())
        if (calls.length() > 0) {
            msg.put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
            msg.put("tool_calls", calls)
        } else {
            msg.put("content", text.toString())
        }
        return msg
    }

    // user: tool_result -> 独立的 role:tool 消息（放最前），其余 text/image 合成一条 user 消息
    private fun convertUser(content: Any?): List<JSONObject> {
        if (content is String) return listOf(JSONObject().put("role", "user").put("content", content))
        val blocks = content as? JSONArray ?: return listOf(JSONObject().put("role", "user").put("content", ""))
        val toolMsgs = mutableListOf<JSONObject>()
        val parts = JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "tool_result" -> {
                    val (text, images) = flattenToolResult(b.opt("content"))
                    val body = if (b.optBoolean("is_error")) "[tool error] $text" else text
                    toolMsgs += JSONObject().put("role", "tool")
                        .put("tool_call_id", b.getString("tool_use_id"))
                        .put("content", body.ifEmpty { "(empty)" })
                    images.forEach { parts.put(it) } // tool 消息只能放文本，图片挪到随后的 user 消息
                }
                "text" -> parts.put(JSONObject().put("type", "text").put("text", b.str("text")))
                "image" -> imagePart(b)?.let { parts.put(it) }
            }
        }
        val result = toolMsgs.toMutableList() // tool 消息必须紧跟 assistant(tool_calls)
        if (parts.length() > 0) result += JSONObject().put("role", "user").put("content", simplify(parts))
        return result
    }

    private fun flattenToolResult(c: Any?): Pair<String, List<JSONObject>> = when (c) {
        is String -> c to emptyList()
        is JSONArray -> {
            val texts = mutableListOf<String>()
            val imgs = mutableListOf<JSONObject>()
            for (i in 0 until c.length()) {
                val b = c.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> texts += b.str("text")
                    "image" -> imagePart(b)?.let { imgs += it }
                }
            }
            texts.joinToString("\n") to imgs
        }
        else -> "" to emptyList()
    }

    private fun imagePart(b: JSONObject): JSONObject? {
        val s = b.optJSONObject("source") ?: return null
        val url = when (s.optString("type")) {
            "base64" -> "data:${s.str("media_type")};base64,${s.str("data")}"
            "url" -> s.str("url")
            else -> return null
        }
        return JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))
    }

    // 全是文本时压成字符串，兼容性最好
    private fun simplify(parts: JSONArray): Any {
        val all = (0 until parts.length()).map { parts.getJSONObject(it) }
        return if (all.all { it.optString("type") == "text" }) all.joinToString("\n") { it.str("text") } else parts
    }

    // 兜底：每个 tool_call_id 都必须有且只有一条 tool 回复，且紧跟其后；孤立的 tool 消息丢弃
    private fun repairToolPairing(msgs: JSONArray): JSONArray {
        val out = JSONArray()
        var i = 0
        while (i < msgs.length()) {
            val m = msgs.getJSONObject(i++)
            if (m.optString("role") == "tool") continue
            out.put(m)
            val calls = m.optJSONArray("tool_calls") ?: continue
            val pending = LinkedHashSet<String>()
            for (k in 0 until calls.length()) pending += calls.getJSONObject(k).getString("id")
            while (i < msgs.length() && msgs.getJSONObject(i).optString("role") == "tool") {
                val t = msgs.getJSONObject(i++)
                if (pending.remove(t.getString("tool_call_id"))) out.put(t) // 去重 + 去孤儿
            }
            for (id in pending) out.put(JSONObject().put("role", "tool").put("tool_call_id", id)
                .put("content", "[no result: tool call was interrupted]"))
        }
        return out
    }

    private fun convertTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val schema = t.optJSONObject("input_schema") ?: continue // 服务端内置工具无 schema，跳过
            out.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.getString("name"))
                .put("description", t.str("description"))
                .put("parameters", schema)))
        }
        return out
    }

    private fun convertToolChoice(tc: JSONObject): Any? = when (tc.optString("type")) {
        "auto" -> "auto"
        "any" -> "required"
        "none" -> "none"
        "tool" -> JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", tc.getString("name")))
        else -> null
    }

    // ================= 非流式响应：OpenAI -> Anthropic =================
    fun convertResponse(resp: JSONObject, model: String): JSONObject {
        val choice = resp.getJSONArray("choices").getJSONObject(0)
        val msg = choice.getJSONObject("message")
        val content = JSONArray()
        msg.str("content").takeIf { it.isNotEmpty() }?.let {
            content.put(JSONObject().put("type", "text").put("text", it))
        }
        val calls = msg.optJSONArray("tool_calls")
        if (calls != null) for (k in 0 until calls.length()) {
            val c = calls.getJSONObject(k)
            val f = c.getJSONObject("function")
            content.put(JSONObject().put("type", "tool_use")
                .put("id", c.str("id").ifEmpty { newId("toolu_") })
                .put("name", f.str("name"))
                .put("input", parseArgs(f.str("arguments"))))
        }
        val u = resp.optJSONObject("usage")
        return JSONObject()
            .put("id", newId("msg_")).put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content)
            .put("stop_reason", mapFinish(choice.str("finish_reason"), (calls?.length() ?: 0) > 0))
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject()
                .put("input_tokens", u?.optInt("prompt_tokens") ?: 0)
                .put("output_tokens", u?.optInt("completion_tokens") ?: 0))
    }
}

// ================= 流式响应：OpenAI SSE chunk -> Anthropic SSE 事件 =================
class AnthropicStreamTranslator(
    private val model: String,
    private val emit: (event: String, data: JSONObject) -> Unit
) {
    private class ToolBuf(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private var started = false
    private var textIndex = -1
    private var thinkIndex = -1
    private var thinkOpen = false
    private var nextIndex = 0
    private val tools = sortedMapOf<Int, ToolBuf>()
    private var finishReason = ""
    private var inTok = 0
    private var outTok = 0

    private fun closeThinking() {
        if (!thinkOpen) return
        thinkOpen = false
        emit("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", thinkIndex)
            .put("delta", JSONObject().put("type", "signature_delta").put("signature", "")))
        emit("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", thinkIndex))
    }

    fun onChunk(chunk: JSONObject) {
        if (!started) {
            started = true
            emit("message_start", JSONObject().put("type", "message_start").put("message", JSONObject()
                .put("id", newId("msg_")).put("type", "message").put("role", "assistant").put("model", model)
                .put("content", JSONArray()).put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 0))))
        }
        chunk.optJSONObject("usage")?.let {
            inTok = it.optInt("prompt_tokens", inTok); outTok = it.optInt("completion_tokens", outTok)
        }
        val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return
        choice.optJSONObject("delta")?.let { d ->
            // 思考增量 (独立块)
            val reasoning = d.str("reasoning_content")
            if (reasoning.isNotEmpty()) {
                if (thinkIndex < 0) {
                    thinkIndex = nextIndex++
                    thinkOpen = true
                    emit("content_block_start", JSONObject().put("type", "content_block_start").put("index", thinkIndex)
                        .put("content_block", JSONObject().put("type", "thinking").put("thinking", "")))
                }
                emit("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", thinkIndex)
                    .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", reasoning)))
            }
            // 正文增量
            val text = d.str("content")
            if (text.isNotEmpty()) {
                closeThinking()
                if (textIndex < 0) {
                    textIndex = nextIndex++
                    emit("content_block_start", JSONObject().put("type", "content_block_start").put("index", textIndex)
                        .put("content_block", JSONObject().put("type", "text").put("text", "")))
                }
                emit("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", textIndex)
                    .put("delta", JSONObject().put("type", "text_delta").put("text", text)))
            }
            val calls = d.optJSONArray("tool_calls")
            if (calls != null) for (k in 0 until calls.length()) {
                val c = calls.getJSONObject(k)
                val buf = tools.getOrPut(c.optInt("index", k)) { ToolBuf() }
                c.str("id").takeIf { it.isNotEmpty() }?.let { buf.id = it }
                c.optJSONObject("function")?.let { f ->
                    f.str("name").takeIf { it.isNotEmpty() }?.let { buf.name = it }
                    buf.args.append(f.str("arguments"))
                }
            }
        }
        choice.str("finish_reason").takeIf { it.isNotEmpty() }?.let { finishReason = it }
    }

    fun abort(err: JSONObject) {
        closeThinking()
        if (textIndex >= 0) {
            emit("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", textIndex))
            textIndex = -1
        }
        val innerErr = err.optJSONObject("error") ?: err
        emit("error", JSONObject().put("type", "error").put("error", innerErr))
    }

    fun finish() {
        if (!started) onChunk(JSONObject())
        closeThinking()
        if (textIndex >= 0) emit("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", textIndex))
        for (t in tools.values) {
            val idx = nextIndex++
            emit("content_block_start", JSONObject().put("type", "content_block_start").put("index", idx)
                .put("content_block", JSONObject().put("type", "tool_use")
                    .put("id", t.id.ifEmpty { newId("toolu_") }).put("name", t.name).put("input", JSONObject())))
            emit("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", idx)
                .put("delta", JSONObject().put("type", "input_json_delta")
                    .put("partial_json", parseArgs(t.args.toString()).toString()))) // 保证是合法 JSON
            emit("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", idx))
        }
        emit("message_delta", JSONObject().put("type", "message_delta")
            .put("delta", JSONObject().put("stop_reason", mapFinish(finishReason, tools.isNotEmpty()))
                .put("stop_sequence", JSONObject.NULL))
            .put("usage", JSONObject().put("input_tokens", inTok).put("output_tokens", maxOf(outTok, 1))))
        emit("message_stop", JSONObject().put("type", "message_stop"))
    }
}

// ================= 稳定的 X-Conversation-ID =================
object ConversationIds {
    fun resolve(headers: Map<String, String>, req: JSONObject): String {
        // 1) 客户端自带会话头时直接沿用
        headers.entries.firstOrNull { it.key.equals("x-conversation-id", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }?.let { return it }
        // 2) metadata.user_id（部分客户端会在里面带 session）
        req.optJSONObject("metadata")?.str("user_id")?.takeIf { it.isNotBlank() }
            ?.let { return uuidOf("meta:$it") }
        // 3) 兜底：system + 第一条 user 消息，多轮之间不变！
        val sysPrompt = systemText(req.opt("system")) ?: run {
            val firstMsg = req.optJSONArray("messages")?.optJSONObject(0)
            if (firstMsg?.optString("role") == "system") firstMsg.optString("content") else null
        }
        val first = req.optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("role") == "user" }
        }
        val firstText = when (val c = first?.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("\n") { it.str("text") }
            else -> ""
        }
        return uuidOf("conv:" + sysPrompt.orEmpty() + "\u0000" + firstText)
    }

    private fun uuidOf(s: String): String =
        UUID.nameUUIDFromBytes(s.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
}
