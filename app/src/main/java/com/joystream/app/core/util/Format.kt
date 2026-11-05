package com.joystream.app.core.util

import java.util.Locale

/** 1:42:07 style duration from milliseconds. */
fun formatDuration(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** Human friendly "3 days ago" for library and update cards. */
fun relativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timestampMs
    if (diff < 0) return "just now"
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 30 -> "$days day${if (days == 1L) "" else "s"} ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} yr ago"
    }
}

/** Extracts the first plausible 4-digit year from free text. */
fun extractYear(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = Regex("(19|20)\\d{2}").find(text) ?: return null
    return match.value
}

fun slugifyFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9 _.-]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "video" }

/** Compares dotted version strings ("1.2.10" > "1.2.9"). */
fun compareVersions(left: String, right: String): Int {
    val a = left.trim().removePrefix("v").split('.', '-', '+')
    val b = right.trim().removePrefix("v").split('.', '-', '+')
    val size = maxOf(a.size, b.size)
    for (index in 0 until size) {
        val leftPart = a.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val rightPart = b.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }
    return 0
}
