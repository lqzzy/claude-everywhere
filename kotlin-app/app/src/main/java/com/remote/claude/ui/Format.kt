package com.remote.claude.ui

import androidx.compose.ui.graphics.Color
import com.remote.claude.ui.theme.C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Token count abbreviation: 1234 → 1.2k, 12345 → 12k
fun kfmt(n: Int): String = when {
    n >= 10000 -> "${n / 1000}k"
    n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}

// Relative time: just now / N min ago / N h ago / yesterday / N d ago / MM-dd
fun relativeTime(ts: Long): String {
    if (ts <= 0) return ""
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        d < 172_800_000 -> "yesterday"
        d < 7 * 86_400_000L -> "${d / 86_400_000} d ago"
        else -> SimpleDateFormat("MM-dd", Locale.US).format(Date(ts))
    }
}

// Until next usage reset: in 3d11h / in 2h1m / in 5m / resetting
fun untilReset(ms: Long): String {
    if (ms <= 0) return ""
    val d = ms - System.currentTimeMillis()
    if (d <= 0) return "resetting"
    val h = d / 3_600_000
    val m = (d % 3_600_000) / 60_000
    return when {
        h >= 24 -> "in ${h / 24}d${h % 24}h"
        h > 0 -> "in ${h}h${m}m"
        else -> "in ${m}m"
    }
}

// Status dot color: idle gray / thinking coral / tool gold / waiting_permission red
fun statusColor(status: String): Color = when (status) {
    "thinking" -> C.accent
    "tool" -> C.warn
    "waiting_permission" -> C.err
    else -> C.dim
}
