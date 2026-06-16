package com.remote.claude.ui

import androidx.compose.ui.graphics.Color
import com.remote.claude.ui.theme.C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// token 数缩写:1234 → 1.2k,12345 → 12k
fun kfmt(n: Int): String = when {
    n >= 10000 -> "${n / 1000}k"
    n >= 1000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}

// 相对时间:刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / MM-dd
fun relativeTime(ts: Long): String {
    if (ts <= 0) return ""
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "刚刚"
        d < 3_600_000 -> "${d / 60_000} 分钟前"
        d < 86_400_000 -> "${d / 3_600_000} 小时前"
        d < 172_800_000 -> "昨天"
        d < 7 * 86_400_000L -> "${d / 86_400_000} 天前"
        else -> SimpleDateFormat("MM-dd", Locale.US).format(Date(ts))
    }
}

// 距下次额度重置:3d11h后 / 2h1m后 / 5m后 / 重置中
fun untilReset(ms: Long): String {
    if (ms <= 0) return ""
    val d = ms - System.currentTimeMillis()
    if (d <= 0) return "重置中"
    val h = d / 3_600_000
    val m = (d % 3_600_000) / 60_000
    return when {
        h >= 24 -> "${h / 24}d${h % 24}h后"
        h > 0 -> "${h}h${m}m后"
        else -> "${m}m后"
    }
}

// 状态色点:idle 灰 / thinking 珊瑚 / tool 金 / waiting_permission 红
fun statusColor(status: String): Color = when (status) {
    "thinking" -> C.accent
    "tool" -> C.warn
    "waiting_permission" -> C.err
    else -> C.dim
}
