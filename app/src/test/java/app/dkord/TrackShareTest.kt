package app.dkord

import app.dkord.core.Track
import app.dkord.core.trackShareText
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackShareTest {
    @Test fun shares_title_artist_and_music_url_exactly() {
        assertEquals("Song - Artist\nhttps://music.youtube.com/watch?v=abcdefghijk",
            trackShareText(Track("abcdefghijk", "Song", "Artist")))
    }

    @Test fun preserves_unicode_and_does_not_include_private_metadata() {
        val track = Track("abcdefghijk", "गाना 🎵", "कलाकार", "Private album", 120, "https://private.test/cover")
        assertEquals("गाना 🎵 - कलाकार\nhttps://music.youtube.com/watch?v=abcdefghijk", trackShareText(track))
    }
}
