package app.dkord.data.releases

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Labels are localized by the UI; Bhojpuri is an internal query, never the Choliwood label. */
enum class ReleaseCategory(val queryLanguage: String) {
    ALL(""), HOLLYWOOD("english"), BOLLYWOOD("hindi"), CHOLIWOOD("bhojpuri"),
    PUNJABI("punjabi"), TAMIL("tamil"), TELUGU("telugu"),
}

/** Date-aware, songs-only searches instead of a timeless relevance search for "new songs". */
internal fun releaseQueries(category: ReleaseCategory, nowMs: Long): List<String> {
    val date = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
    val month = date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))
    val after = date.minusDays(10)
    if (category == ReleaseCategory.ALL) return listOf(
        "new english songs $month after:$after",
        "new hindi songs $month after:$after",
        "new bhojpuri songs $month after:$after",
        "new punjabi songs $month after:$after",
    )
    return listOf(
        "new ${category.queryLanguage} songs $month",
        "latest ${category.queryLanguage} songs after:$after",
        "new ${category.queryLanguage} songs this week $month",
    )
}
