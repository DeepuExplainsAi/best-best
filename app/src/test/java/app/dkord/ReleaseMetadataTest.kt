package app.dkord

import app.dkord.core.Track
import app.dkord.data.releases.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReleaseMetadataTest {
    private val date = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()

    @Test fun meta_dates_support_different_attribute_order_quotes_and_case() {
        for (html in listOf(
            "<meta itemprop=\"datePublished\" content=\"2026-11-06\">",
            "<META content='2026-11-06T00:00:00Z' itemprop='uploadDate'>",
            "<meta property='video:release_date' content='2026-11-06T05:30:00+05:30'>",
        )) assertEquals(date, publishedAt(html))
    }

    @Test fun player_microformat_works_without_extracting_audio_streams() {
        val html = """<script>var response={"microformat":{"playerMicroformatRenderer":{"publishDate":"2026-11-06T00:00:00Z","uploadDate":"2026-11-01","description":{"simpleText":"a } brace and a { brace"}}}};</script>"""
        assertEquals(date, publishedAt(html))
    }

    @Test fun publication_date_takes_priority_over_an_earlier_upload_date() {
        assertEquals(date, publishedAt("<meta itemprop='uploadDate' content='2026-10-01'><meta itemprop='datePublished' content='2026-11-06'>"))
    }

    @Test fun arbitrary_recommendation_dates_titles_and_relative_words_are_never_guessed() {
        for (html in listOf("<div>released today</div>", "{\"publishDate\":\"2026-11-06\"}",
            "<meta itemprop='datePublished' content='yesterday'>", "<meta itemprop='datePublished' content='2026-99-99'>", "")) {
            assertNull(publishedAt(html))
        }
    }

    @Test fun broken_microformat_can_fall_back_to_main_video_meta_date() {
        val html = "<meta itemprop='datePublished' content='2026-11-06'>\"playerMicroformatRenderer\":{broken"
        assertEquals(date, publishedAt(html))
    }

    @Test fun metadata_http_is_anonymous_and_does_not_need_a_playable_stream() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body("<meta itemprop='datePublished' content='2026-11-06'>".toResponseBody()).build()
        }.build()
        assertEquals(date, MusicUploadDates(client).fetch(Track("abcdefghijk", "Song", "Artist")))
        assertEquals(1, calls)
    }

    @Test fun date_aware_queries_cover_the_requested_categories_without_renaming_choliwood() {
        val now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        val queries = releaseQueries(ReleaseCategory.CHOLIWOOD, now)
        assertTrue(queries.all { "bhojpuri" in it })
        assertTrue(queries.any { "November 2026" in it })
        assertTrue(queries.any { "after:2026-10-28" in it })
        assertTrue(releaseQueries(ReleaseCategory.HOLLYWOOD, now).all { "english" in it })
        assertTrue(releaseQueries(ReleaseCategory.BOLLYWOOD, now).all { "hindi" in it })
    }
}
