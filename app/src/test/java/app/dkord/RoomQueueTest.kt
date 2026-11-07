package app.dkord

import app.dkord.core.Track
import app.dkord.data.room.RoomCode
import app.dkord.data.room.RoomPlaybackState
import app.dkord.data.room.roomQueueMatches
import org.junit.Assert.*
import org.junit.Test

class RoomQueueTest {
    private val song = Track("abcdefghijk", "Song", "Artist")
    private val next = Track("lmnopqrstuv", "Next", "Artist")
    private val related = Track("123456789ab", "Related", "Artist")

    @Test fun a_local_related_suffix_does_not_restart_the_host_song() {
        val local = RoomPlaybackState(song, listOf(song, next, related))
        assertTrue(roomQueueMatches(local, listOf(song, next)))
        assertTrue(roomQueueMatches(local, listOf(song)))
    }

    @Test fun history_before_the_current_queue_index_is_ignored() {
        assertTrue(roomQueueMatches(RoomPlaybackState(song, listOf(related, song, next), queueIndex = 1), listOf(song, next)))
    }

    @Test fun different_current_song_missing_or_reordered_host_tracks_require_replacement() {
        assertFalse(roomQueueMatches(RoomPlaybackState(related, listOf(related, next)), listOf(song, next)))
        assertFalse(roomQueueMatches(RoomPlaybackState(song, listOf(song)), listOf(song, next)))
        assertFalse(roomQueueMatches(RoomPlaybackState(song, listOf(song, related, next)), listOf(song, next)))
        assertFalse(roomQueueMatches(RoomPlaybackState(song, listOf(song)), emptyList()))
    }

    @Test fun pasted_grouped_codes_normalize_without_accepting_urls_or_invalid_characters() {
        assertEquals("ABCDEFGHJK", RoomCode.normalize(" abcd-ef ghjk\n"))
        assertEquals("dkord-ABCDEFGHJK", RoomCode.topic("abcd efghjk"))
        assertNull(RoomCode.normalize("https://example.test/ABCDEFGHJK"))
        assertNull(RoomCode.normalize("ABCD0FGHJK"))
        assertNull(RoomCode.normalize("ABCD/FGHJK"))
    }
}
