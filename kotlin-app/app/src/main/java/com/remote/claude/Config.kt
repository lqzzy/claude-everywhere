package com.remote.claude

import android.content.Context

// 连接配置(持久化到 SharedPreferences)。
// 首启为空 → App 显示「连接」页;扫描电脑端 install.sh 打印的二维码
// (claudeeverywhere://connect?url=...&token=...)或手动填写后写入,之后开机自动连。
object Config {
    private const val PREFS = "claude_everywhere"
    private const val KEY_URL = "server_ws"
    private const val KEY_TOKEN = "auth_token"

    data class Conn(val url: String, val token: String)

    // 读取已保存的连接;未配置(地址为空)返回 null。
    fun load(ctx: Context): Conn? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = sp.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return Conn(url, sp.getString(KEY_TOKEN, "") ?: "")
    }

    fun save(ctx: Context, url: String, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }
}
