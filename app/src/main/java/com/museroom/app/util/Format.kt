package com.museroom.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit

/** Track positions, as a player would show them. */
fun formatClock(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** "4s ago", for the self-check row. */
fun formatAgo(wallClockMs: Long, now: Long = System.currentTimeMillis()): String {
    if (wallClockMs <= 0L) return "never"
    val delta = (now - wallClockMs).coerceAtLeast(0)
    return when {
        delta < 1_000 -> "just now"
        delta < 60_000 -> "${delta / 1_000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        else -> "${delta / 3_600_000}h ago"
    }
}

/** Listening totals, the way a leaderboard would show them. */
fun formatMinutes(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        totalMinutes > 0 -> "${minutes}m"
        else -> "${(ms / 1000).coerceAtLeast(0)}s"
    }
}
