package app.dkord.ui.common

import java.util.Locale

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.ROOT, "%d KB", bytes / 1024)
}

/** YouTube thumbnails come tiny (w60). googleusercontent urls accept a size suffix. */
fun hiRes(url: String?, px: Int = 544): String? {
    if (url == null) return null
    val sized = Regex("=w\\d+-h\\d+")
    return if (sized.containsMatchIn(url)) sized.replaceFirst(url, "=w$px-h$px") else url
}
