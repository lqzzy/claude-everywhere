package com.remote.claude

import android.content.Context

// Connection config (persisted to SharedPreferences).
// Empty on first launch → the app shows the "Connect" screen; scanning the QR code printed by the desktop install.sh
// (claudeeverywhere://connect?url=...&token=...) or filling it in manually writes it, and afterward it auto-connects on startup.
object Config {
    private const val PREFS = "claude_everywhere"
    private const val KEY_URL = "server_ws"
    private const val KEY_TOKEN = "auth_token"

    data class Conn(val url: String, val token: String)

    // Read the saved connection; returns null if not configured (address is empty).
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
