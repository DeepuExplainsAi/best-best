package app.dkord

import app.dkord.core.ItemKind
import app.dkord.data.auth.SapisidHash
import app.dkord.data.stream.*
import app.dkord.data.yt.MusicParser
import app.dkord.data.yt.lenientJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Part2Test {
    private fun fixture(n: String) = lenientJson.parseToJsonElement(
        javaClass.classLoader!!.getResource("fixtures/$n.json")!!.readText())

    // ---- auth ----
    @Test fun sapisidhash_matches_known_sha1() {
        // sha1("1000 abc https://music.youtube.com")
        val h = SapisidHash.authorization("abc", 1000)
        assertEquals("SAPISIDHASH 1000_" + sha1("1000 abc https://music.youtube.com"), h)
    }

    @Test fun headers_empty_without_session_and_complete_with_one() {
        assertTrue(SapisidHash.headers(null).isEmpty())
        assertTrue(SapisidHash.headers("VISITOR_INFO1_LIVE=x").isEmpty())
        val h = SapisidHash.headers("SID=1; SAPISID=secret; HSID=2", 5)
        assertEquals("0", h["X-Goog-AuthUser"]); assertEquals("https://music.youtube.com", h["Origin"])
        assertTrue(h["Authorization"]!!.startsWith("SAPISIDHASH 5_")); assertTrue(h["Cookie"]!!.contains("SAPISID=secret"))
    }

    @Test fun sapisid_falls_back_to_3papisid() {
        assertEquals("z", SapisidHash.sapisid("a=b; __Secure-3PAPISID=z"))
    }

    // ---- picker ----
    private fun c(mime: String, br: Int, orig: Boolean = true) = AudioCandidate("https://x/$br", mime, br, isOriginalTrack = orig)

    @Test fun picker_never_returns_video_and_prefers_best_audio() {
        val all = listOf(c("video/mp4", 9_000_000), c("audio/mp4; codecs=\"mp4a.40.2\"", 130_000), c("audio/webm; codecs=\"opus\"", 160_000), c("audio/mp4", 50_000))
        assertEquals(160_000, AudioPicker.pick(all, AudioQuality.HIGH, false)!!.bitrate)
        assertEquals(130_000, AudioPicker.pick(all, AudioQuality.HIGH, true)!!.bitrate)
        assertEquals(50_000, AudioPicker.pick(all, AudioQuality.LOW, false)!!.bitrate)
        assertNull(AudioPicker.pick(listOf(c("video/mp4", 1)), AudioQuality.HIGH, false))
    }

    @Test fun picker_skips_dubbed_tracks() {
        val all = listOf(c("audio/mp4", 200_000, orig = false), c("audio/mp4", 100_000))
        assertEquals(100_000, AudioPicker.pick(all, AudioQuality.HIGH, false)!!.bitrate)
    }

    // ---- player response ----
    private fun player(status: String, reason: String? = null, formats: String = "[]") = lenientJson.parseToJsonElement(
        """{"playabilityStatus":{"status":"$status"${reason?.let { ""","reason":"$it"""" } ?: ""}},"streamingData":{"expiresInSeconds":"21540","adaptiveFormats":$formats}}""")

    @Test fun player_errors_are_mapped() {
        fun err(r: String) = runCatching { PlayerResponseParser.checkPlayability(player("LOGIN_REQUIRED", r)) }.exceptionOrNull()
        assertTrue(err("Sign in to confirm you’re not a bot") is StreamError.BotCheck)
        assertTrue(err("Sign in to confirm your age") is StreamError.AgeRestricted)
        assertTrue(err("This video is private") is StreamError.Private)
        assertTrue(err("Video unavailable in your country") is StreamError.Geo)
    }

    @Test fun player_ok_picks_audio_url_and_expiry() {
        val f = """[{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130677,"url":"https://v/x?expire=4102444800&c=1","contentLength":"100"},
            {"itag":137,"mimeType":"video/mp4","bitrate":9000000,"url":"https://v/y"}]"""
        val s = PlayerResponseParser.parse("id", player("OK", null, f), AudioQuality.HIGH, true, "t", "ua")
        assertEquals("https://v/x?expire=4102444800&c=1", s.url); assertEquals("ua", s.userAgent); assertTrue(s.isM4a)
        assertTrue(s.expiresAtMs < 4102444800_000L)
    }

    // ---- chain ----
    private class Fake(override val name: String, val r: () -> ResolvedStream) : StreamResolver {
        var calls = 0
        override suspend fun resolve(videoId: String, quality: AudioQuality, preferM4a: Boolean): ResolvedStream { calls++; return r() }
    }
    private fun ok(src: String, exp: Long = System.currentTimeMillis() + 3_600_000) = ResolvedStream("v", "http://u", "audio/mp4", 1, null, exp, null, src)

    @Test fun chain_falls_through_and_reports_most_informative_error() = runBlocking {
        val a = Fake("a") { throw StreamError.BotCheck() }
        val b = Fake("b") { ok("b") }
        assertEquals("b", FallbackStreamResolver(listOf(a, b)).resolve("v").source)
        val c = Fake("c") { throw StreamError.Network(java.io.IOException("x")) }
        val d = Fake("d") { throw StreamError.AgeRestricted() }
        assertTrue(runCatching { FallbackStreamResolver(listOf(c, d)).resolve("v") }.exceptionOrNull() is StreamError.AgeRestricted)
    }

    @Test fun chain_stops_on_private() = runBlocking {
        val a = Fake("a") { throw StreamError.Private() }; val b = Fake("b") { ok("b") }
        runCatching { FallbackStreamResolver(listOf(a, b)).resolve("v") }
        assertEquals(0, b.calls)
    }

    @Test fun cache_reuses_until_expiry_and_invalidates_on_403() = runBlocking {
        val f = Fake("f") { ok("f") }
        val cache = CachedStreamResolver(f)
        cache.resolve("v"); cache.resolve("v"); assertEquals(1, f.calls)
        cache.invalidate("v"); cache.resolve("v"); assertEquals(2, f.calls)
        val stale = Fake("s") { ok("s", exp = System.currentTimeMillis() + 10_000) } // inside the 60s safety margin
        val c2 = CachedStreamResolver(stale); c2.resolve("v"); c2.resolve("v"); assertEquals(2, stale.calls)
    }

    // ---- parser against real captured responses ----
    @Test fun home_has_chips_and_song_and_playlist_shelves() {
        val p = MusicParser.parsePage(fixture("home"))
        assertEquals(3, p.chips.size); assertTrue(p.shelves.size >= 3)
        val kinds = p.shelves.flatMap { it.items }.map { it.kind }.toSet()
        assertTrue(ItemKind.SONG in kinds && ItemKind.PLAYLIST in kinds)
        p.shelves.flatMap { it.items }.filter { it.kind == ItemKind.SONG }.forEach { assertTrue(it.track!!.id.length == 11) }
    }

    @Test fun playlist_tracks_have_title_artist_duration_and_continuation() {
        val p = MusicParser.parsePage(fixture("playlist"))
        assertEquals(4, p.tracks.size); assertNotNull(p.continuation)
        val t = p.tracks.first(); assertEquals("Sapphire", t.title); assertEquals("Ed Sheeran", t.artist); assertEquals(184, t.durationSec)
    }

    @Test fun duration_parsing() {
        assertEquals(184, MusicParser.parseDuration("3:04")); assertEquals(3723, MusicParser.parseDuration("1:02:03")); assertEquals(0, MusicParser.parseDuration("abc"))
    }

    private fun sha1(s: String) = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
