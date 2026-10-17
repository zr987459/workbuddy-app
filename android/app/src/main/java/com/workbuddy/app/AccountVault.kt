package com.workbuddy.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object AccountVault {

    private const val PREF_NAME = "workbuddy_vault"
    private const val KEY_ACCOUNTS = "accounts"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getAccounts(): JSONArray {
        val raw = prefs.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
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
