package app.dkord

import app.dkord.core.Track
import app.dkord.data.room.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RoomMessageTest {
    private fun message() = RoomMessage("abcdefghijk", "Song", "Artist", "https://i.ytimg.com/cover.jpg",
        12_000, true, 1_800_000_000_000, listOf(RoomTrack("bcdefghijkl", "Next", "Artist")))

    @Test fun codes_are_ten_unambiguous_uppercase_characters() {
        val codes = (1..1000).map { RoomCode.generate() }
        assertEquals(1000, codes.toSet().size)
        assertTrue(codes.all { it.length == 10 && it.all { char -> char in RoomCode.ALPHABET && char !in "O0I1" } })
    }

    @Test fun seeded_generator_normalization_and_topics_are_testable() {
        assertEquals(RoomCode.generate(Random(3)), RoomCode.generate(Random(3)))
        assertEquals("ABCDEFGHJK", RoomCode.normalize("  abcdefghjk  "))
        assertEquals("dkord-ABCDEFGHJK", RoomCode.topic("abcdefghjk"))
        for (bad in listOf("ABCDE0GHJK", "ABCDEOGHJK", "ABCDEIGHJK", "ABCDE1GHJK", "short", "../../bad/")) {
            assertNull(RoomCode.normalize(bad))
        }
    }

    @Test fun message_round_trips_required_fields_and_queue() {
        val original = message()
        assertEquals(original, RoomCodec.decode(RoomCodec.encode(original)))
    }

    @Test fun unicode_payload_remains_below_4000_utf8_bytes_and_queue_never_exceeds_ten() {
        val heavy = message().copy(title = "गाना🎵".repeat(100), artist = "कलाकार🎧".repeat(100),
            thumbnail = "https://i.ytimg.com/" + "a".repeat(480),
            queue = (1..30).map { RoomTrack("q" + it.toString().padStart(10, '0'), "गाना🎵".repeat(100), "कलाकार".repeat(100)) })
        val encoded = RoomCodec.encode(heavy)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < 4000)
        val decoded = checkNotNull(RoomCodec.decode(encoded))
        assertTrue(decoded.queue.size <= 10)
        assertTrue(decoded.queue.isNotEmpty())
        assertFalse(decoded.title.last().isHighSurrogate())
    }

    @Test fun duplicate_current_and_invalid_queue_entries_are_not_shared() {
        val encoded = RoomCodec.encode(message().copy(queue = listOf(
            RoomTrack("abcdefghijk", "Current", "Artist"),
            RoomTrack("bcdefghijkl", "Next", "Artist"),
            RoomTrack("bcdefghijkl", "Duplicate", "Artist"),
            RoomTrack("invalid", "Bad", "Artist"),
        )))
        assertEquals(listOf("bcdefghijkl"), RoomCodec.decode(encoded)!!.queue.map { it.videoId })
    }

    @Test fun invalid_json_versions_ids_positions_and_oversized_payloads_are_rejected() {
        assertNull(RoomCodec.decode("not json"))
        assertNull(RoomCodec.decode("x".repeat(4000)))
        val good = RoomCodec.encode(message())
        assertNull(RoomCodec.decode(good.replace("\"version\":1", "\"version\":2")))
        assertNull(RoomCodec.decode(good.replace("abcdefghijk", "bad")))
        assertNull(RoomCodec.decode(good.replace("\"positionMs\":12000", "\"positionMs\":-1")))
    }

    @Test fun relay_envelope_ignores_keepalives_and_bad_messages() {
        val line = buildJsonObject {
            put("event", "message"); put("id", "cursor123"); put("message", RoomCodec.encode(message()))
        }.toString()
        assertEquals("cursor123", RoomCodec.decodeRelayLine(line)?.cursor)
        assertEquals(message(), RoomCodec.decodeRelayLine(line)?.message)
        assertNull(RoomCodec.decodeRelayLine("{\"event\":\"keepalive\"}"))
        assertNull(RoomCodec.decodeRelayLine("{\"event\":\"message\",\"message\":\"bad\"}"))
    }

    @Test fun sync_position_extrapolates_only_while_playing_and_clamps_future_clocks() {
        val msg = message()
        assertEquals(15_000L, synchronizedPosition(msg, msg.timestamp + 3_000))
        assertEquals(12_000L, synchronizedPosition(msg.copy(playing = false), msg.timestamp + 3_000))
        assertEquals(12_000L, synchronizedPosition(msg, msg.timestamp - 3_000))
    }

    @Test fun track_conversion_excludes_album_download_and_private_metadata() {
        val source = Track("abcdefghijk", "Song", "Artist", "private_album_secret", 123, "https://i.ytimg.com/cover.jpg")
        val snapshot = RoomPlaybackState(source, listOf(source), 0, 1_000, false)
        val text = RoomCodec.encode(checkNotNull(roomMessage(snapshot, 1_800_000_000_000)))
        assertFalse(text.contains("private_album_secret"))
        assertFalse(text.contains("durationSec"))
        assertFalse(text.contains("cookie", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    @Test fun only_next_ten_queue_tracks_are_included() {
        val tracks = (0..20).map { Track("q" + it.toString().padStart(10, '0'), "Song $it", "Artist") }
        val msg = checkNotNull(roomMessage(RoomPlaybackState(tracks[4], tracks, 4), 1_800_000_000_000))
        assertEquals(tracks.drop(5).take(10).map { it.id }, msg.queue.map { it.videoId })
    }
}
