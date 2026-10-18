package com.workbuddy.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

object GatewayMetrics {

    private const val PREF_NAME = "workbuddy_metrics"
    private const val KEY_LAST_DATE = "last_date"
    private const val KEY_TODAY_REQ = "today_req"
    private const val KEY_TODAY_TOKENS = "today_tokens"
    private const val MAX_LOGS = 150

    private lateinit var prefs: SharedPreferences
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val todayRequests = AtomicInteger(0)
    private val todayTokens = AtomicInteger(0)
    private val logs = ConcurrentLinkedDeque<JSONObject>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val today = dateFormat.format(Date())
        val savedDate = prefs.getString(KEY_LAST_DATE, "")

        if (savedDate == today) {
            todayRequests.set(prefs.getInt(KEY_TODAY_REQ, 0))
            todayTokens.set(prefs.getInt(KEY_TODAY_TOKENS, 0))
        } else {
            todayRequests.set(0)
            todayTokens.set(0)
            prefs.edit().putString(KEY_LAST_DATE, today)
                .putInt(KEY_TODAY_REQ, 0)
                .putInt(KEY_TODAY_TOKENS, 0)
                .apply()
        }
    }

    private fun checkDateRollover() {
        if (!::prefs.isInitialized) return
        val today = dateFormat.format(Date())
        val savedDate = prefs.getString(KEY_LAST_DATE, "")
        if (savedDate != today) {
            todayRequests.set(0)
            todayTokens.set(0)
            prefs.edit().putString(KEY_LAST_DATE, today)
                .putInt(KEY_TODAY_REQ, 0)
                .putInt(KEY_TODAY_TOKENS, 0)
                .apply()
        }
    }

    fun getTodayRequests(): Int {
        checkDateRollover()
        return todayRequests.get()
    }

    fun getTodayTokens(): Int {
        checkDateRollover()
        return todayTokens.get()
    }

    @Synchronized
    fun record(
        method: String,
        path: String,
        model: String,
        endpoint: String,
        status: Int,
        tokens: Int,
        latencyMs: Long,
        note: String? = null
    ) {
        checkDateRollover()
        val reqCount = todayRequests.incrementAndGet()
        val totalTok = todayTokens.addAndGet(maxOf(0, tokens))

        if (::prefs.isInitialized) {
            prefs.edit()
                .putInt(KEY_TODAY_REQ, reqCount)
                .putInt(KEY_TODAY_TOKENS, totalTok)
                .apply()
        }

        val now = Date()
        val logItem = JSONObject().apply {
            put("id", "log_${now.time}_${UUID.randomUUID().toString().take(6)}")
            put("time", timeFormat.format(now))
            put("method", method)
            put("path", path)
            put("model", model)
            put("endpoint", endpoint)
            put("status", status)
            put("tokens", tokens)
            put("latency_ms", latencyMs)
            if (!note.isNullOrBlank()) {
                put("note", note)
            }
        }

        logs.addFirst(logItem)
        while (logs.size > MAX_LOGS) {
            logs.pollLast()
        }
    }

    fun getLogsJson(): JSONArray {
        val arr = JSONArray()
        for (item in logs) {
            arr.put(item)
        }
        return arr
    }

    fun clearLogs() {
        logs.clear()
    }
}
