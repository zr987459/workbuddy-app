package com.workbuddy.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AccountVault {

    private const val PREF_NAME = "workbuddy_vault"
    private const val KEY_ACCOUNTS = "accounts"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAccounts(): JSONArray {
        val result = JSONArray()
        val seenUids = mutableSetOf<String>()

        // 1. 优先扫描本地 auths/ 目录下的官方与网关落盘凭证文件
        val authsDir = File(appContext.filesDir, "auths")
        if (authsDir.exists() && authsDir.isDirectory) {
            authsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".json") && !file.name.endsWith(".disabled")) {
                    try {
                        val content = file.readText()
                        val obj = JSONObject(content)
                        val acct = obj.optJSONObject("account") ?: JSONObject()
                        val auth = obj.optJSONObject("auth") ?: JSONObject()
                        val uid = acct.optString("uid", obj.optString("uid"))
                        val token = auth.optString("accessToken", obj.optString("access_token", obj.optString("accessToken")))

                        if (uid.isNotEmpty() && token.isNotEmpty() && !seenUids.contains(uid)) {
                            seenUids.add(uid)
                            val nickname = acct.optString("nickname", obj.optString("nickname", "WorkBuddy用户"))
                            val realm = auth.optString("realm", obj.optString("realm", "global"))

                            result.put(JSONObject().apply {
                                put("uid", uid)
                                put("nickname", nickname)
                                put("realm", realm)
                                put("accessToken", token)
                                put("refreshToken", auth.optString("refreshToken", obj.optString("refreshToken", "")))
                                put("in_pool", true)
                                put("disabled", false)
                                put("cooling", false)
                                put("credits", obj.optInt("credits", 350))
                                put("token_usage", JSONObject().apply {
                                    put("request_count", 0)
                                    put("total_tokens", 0)
                                })
                            })
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }

        // 2. 补充扫描 SharedPreferences 中的记录
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        try {
            val prefArr = JSONArray(raw)
            for (i in 0 until prefArr.length()) {
                val a = prefArr.getJSONObject(i)
                val uid = a.optString("uid")
                if (uid.isNotEmpty() && !seenUids.contains(uid)) {
                    seenUids.add(uid)
                    result.put(a)
                }
            }
        } catch (ignored: Exception) {}

        return result
    }

    @Synchronized
    fun saveAccount(uid: String, nickname: String, realm: String, accessToken: String, refreshToken: String) {
        val list = getAccounts()
        val next = JSONArray()

        val newAcc = JSONObject().apply {
            put("uid", uid)
            put("nickname", nickname)
            put("realm", realm)
            put("accessToken", accessToken)
            put("refreshToken", refreshToken)
            put("in_pool", true)
            put("disabled", false)
            put("cooling", false)
            put("credits", 350)
            put("token_usage", JSONObject().apply {
                put("request_count", 0)
                put("total_tokens", 0)
            })
        }

        next.put(newAcc)
        for (i in 0 until list.length()) {
            val old = list.getJSONObject(i)
            if (old.optString("uid") != uid) {
                next.put(old)
            }
        }

        prefs.edit().putString(KEY_ACCOUNTS, next.toString()).apply()

        // 同时落盘到 auths/ 目录以确保双向同步
        try {
            val authsDir = File(appContext.filesDir, "auths").apply { mkdirs() }
            val file = File(authsDir, "workbuddy-$uid.json")
            val fullObj = JSONObject().apply {
                put("account", JSONObject().apply {
                    put("uid", uid)
                    put("nickname", nickname)
                })
                put("auth", JSONObject().apply {
                    put("accessToken", accessToken)
                    put("refreshToken", refreshToken)
                    put("realm", realm)
                })
            }
            file.writeText(fullObj.toString(2))
        } catch (ignored: Exception) {}
    }

    @Synchronized
    fun getActiveAccount(realm: String): JSONObject? {
        val list = getAccounts()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            if (a.optString("realm") == realm && !a.optBoolean("disabled", false)) {
                return a
            }
        }
        if (list.length() > 0) return list.getJSONObject(0)
        return null
    }
}
