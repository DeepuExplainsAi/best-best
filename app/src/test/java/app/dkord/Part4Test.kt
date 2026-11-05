package app.dkord

import app.dkord.core.Track
import app.dkord.data.download.DownloadStorage
import app.dkord.data.download.Mp4Tagger
import app.dkord.data.library.LibraryDoc
import app.dkord.data.library.PlaylistDoc
import app.dkord.data.library.PlaylistJson
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Part4Test {
    private fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()

    private fun stco(vararg offsets: Int): ByteArray {
        val b = ByteBuffer.allocate(8 + offsets.size * 4).putInt(0).putInt(offsets.size)
        offsets.forEach { b.putInt(it) }
        return box("stco", b.array())
    }

    private fun mp4(moovFirst: Boolean, mdat: ByteArray): Pair<ByteArray, Int> {
        val ftyp = box("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray())
        fun moov(off: Int) = box("moov", box("trak", box("mdia", box("minf", box("stbl", stco(off))))))
        val moovSize = moov(0).size
        val out = ByteArrayOutputStream()
        return if (moovFirst) {
            val dataStart = ftyp.size + moovSize + 8
            out.write(ftyp); out.write(moov(dataStart)); out.write(box("mdat", mdat))
            out.toByteArray() to dataStart
        } else {
            out.write(ftyp); out.write(box("mdat", mdat)); out.write(moov(ftyp.size + 8))
            out.toByteArray() to (ftyp.size + 8)
        }
    }

    private fun find(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).indexOf(s)

    private fun tagged(data: ByteArray, tags: Mp4Tagger.Tags): ByteArray {
        val src = File.createTempFile("src", ".m4a").apply { writeBytes(data) }
        val dst = File.createTempFile("dst", ".m4a")
        Mp4Tagger.tag(src, dst, tags)
        return dst.readBytes().also { src.delete(); dst.delete() }
    }

    @Test fun taggerKeepsMediaAndWritesTags() {
        val media = ByteArray(100) { it.toByte() }
        val (file, _) = mp4(moovFirst = false, mdat = media)
        val out = tagged(file, Mp4Tagger.Tags("Song é", "Artist", "Album", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)))
        assertTrue(find(out, "\u00A9nam") > 0)
        assertTrue(String(out, Charsets.UTF_8).contains("Song é"))
        assertTrue(find(out, "covr") > 0)
        assertTrue(find(out, "\u00A9alb") > 0)
        val at = find(out, "mdat") - 4
        assertArrayEquals(media, out.copyOfRange(at + 8, at + 8 + media.size))
    }

    @Test fun taggerShiftsChunkOffsetsWhenMoovPrecedesMdat() {
        val media = ByteArray(64) { 7 }
        val (file, oldStart) = mp4(moovFirst = true, mdat = media)
        val out = tagged(file, Mp4Tagger.Tags("T", "A"))
        val stcoAt = find(out, "stco") + 4
        val newOffset = ByteBuffer.wrap(out).getInt(stcoAt + 8)
        val mdatData = find(out, "mdat") + 4
        assertEquals("offset must point at the first media byte", mdatData, newOffset)
        assertTrue(newOffset > oldStart)
    }

    @Test fun taggerLeavesOffsetsWhenMdatPrecedesMoov() {
        val (file, start) = mp4(moovFirst = false, mdat = ByteArray(32))
        val out = tagged(file, Mp4Tagger.Tags("T", "A"))
        assertEquals(start, ByteBuffer.wrap(out).getInt(find(out, "stco") + 12))
    }

    @Test fun taggerReplacesExistingUdta() {
        val (file, _) = mp4(moovFirst = false, mdat = ByteArray(8))
        val once = tagged(file, Mp4Tagger.Tags("First", "A"))
        val twice = tagged(once, Mp4Tagger.Tags("Second", "A"))
        val s = String(twice, Charsets.UTF_8)
        assertTrue(s.contains("Second")); assertFalse(s.contains("First"))
    }

    @Test fun taggerRejectsNonMp4() {
        val src = File.createTempFile("bad", ".bin").apply { writeBytes(ByteArray(40) { 1 }) }
        try { Mp4Tagger.tag(src, File.createTempFile("o", ".m4a"), Mp4Tagger.Tags("T", "A")); fail() }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun safeNameStripsIllegalCharacters() {
        assertEquals("ACDC - Back In Black.m4a", DownloadStorage.safeName("AC/DC", "Back: In *Black*?", "m4a"))
        assertEquals("track.m4a", DownloadStorage.safeName("", "///", "m4a"))
        assertTrue(DownloadStorage.safeName("a".repeat(300), "b", "m4a").length <= 124)
    }

    @Test fun uniqueFileAddsCounter() {
        val dir = File.createTempFile("dir", "x").let { it.delete(); it.mkdirs(); it }
        File(dir, "x.m4a").writeText("1")
        assertEquals("x (1).m4a", DownloadStorage.uniqueFile(dir, "x.m4a").name)
    }

    private val t1 = Track("aaaaaaaaaaa", "One", "A", "Alb", 100, "http://x/1.jpg")
    private val t2 = Track("bbbbbbbbbbb", "Two", "B")

    @Test fun jsonRoundTrip() {
        val text = PlaylistJson.encode(LibraryDoc(playlists = listOf(PlaylistDoc("Mix", listOf(t1, t2))), liked = listOf(t2)))
        val back = PlaylistJson.decode(text)
        assertEquals(listOf(t1, t2), back.playlists.single().tracks)
        assertEquals(listOf(t2), back.liked)
    }

    @Test fun jsonDropsInvalidTracksAndBlankNames() {
        val bad = Track("short", "x", "y")
        val doc = PlaylistJson.decode(PlaylistJson.encode(LibraryDoc(playlists = listOf(PlaylistDoc("  ", listOf(t1, bad))))))
        assertEquals("Imported", doc.playlists.single().name)
        assertEquals(listOf(t1), doc.playlists.single().tracks)
    }

    @Test fun jsonRejectsForeignFiles() {
        for (text in listOf("not json", "{\"hello\":1}", "{\"app\":\"other\",\"playlists\":[]}", "{\"app\":\"dkord\",\"version\":9}")) {
            try { PlaylistJson.decode(text); fail("accepted: $text") } catch (_: IllegalArgumentException) {}
        }
    }
}
