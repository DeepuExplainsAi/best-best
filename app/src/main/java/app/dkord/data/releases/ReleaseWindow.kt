package app.dkord.data.releases

import app.dkord.core.Track

/** Windows are elapsed time, not calendar-day or device-time-zone comparisons. */
enum class ReleaseWindow(val durationMs: Long) {
    LAST_24_HOURS(24L * 60 * 60 * 1000),
    LAST_7_DAYS(7L * 24 * 60 * 60 * 1000),
}

data class DatedTrack(val track: Track, val uploadedAtMs: Long)

/** Ignore unknown/future dates, keep exact boundaries, newest first, one result per video. */
fun releasesInWindow(candidates: List<DatedTrack>, window: ReleaseWindow, nowMs: Long): List<Track> =
    candidates.filter { it.uploadedAtMs > 0 && it.uploadedAtMs <= nowMs && nowMs - it.uploadedAtMs <= window.durationMs }
        .sortedByDescending { it.uploadedAtMs }
        .distinctBy { it.track.id }
        .map { it.track }
