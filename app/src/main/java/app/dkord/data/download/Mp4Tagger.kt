package app.dkord.data.download

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Writes iTunes-style metadata (title, artist, album, cover) into an MP4/M4A file without any
 * native library. Replaces `moov/udta`, copies everything else byte for byte, and shifts
 * `stco`/`co64` chunk offsets if `moov` sits in front of the media data. YouTube's fragmented
 * M4A has no chunk offsets, so there nothing needs shifting.
 */
object Mp4Tagger {
    class Tags(
        val title: String,
        val artist: String,
        val album: String? = null,
        val cover: ByteArray? = null,
        val coverIsPng: Boolean = false,
    )

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    private const val MAX_MOOV = 64L * 1024 * 1024

    fun tag(src: File, dst: File, tags: Tags) {
        RandomAccessFile(src, "r").use { raf ->
            val len = raf.length()
            val moov = readBoxes(raf, 0, len).firstOrNull { it.type == "moov" } ?: error("No moov box: not an MP4 file")
            require(moov.size <= MAX_MOOV) { "moov too large" }

            val old = ByteArray((moov.size - moov.header).toInt())
            raf.seek(moov.start + moov.header)
            raf.readFully(old)

            val kept = children(old).filter { it.type != "udta" }
            val udta = udta(tags)
            val body = ByteBuffer.allocate(kept.sumOf { it.size.toInt() } + udta.size)
            kept.forEach { body.put(old, it.start.toInt(), it.size.toInt()) }
            body.put(udta)

            val newMoov = box("moov", body.array())
            val delta = newMoov.size - moov.size
            if (delta != 0L) shiftChunkOffsets(newMoov, 8, newMoov.size, moovEnd = moov.start + moov.size, delta = delta)

            dst.outputStream().buffered().use { out ->
                copy(raf, 0, moov.start, out)
                out.write(newMoov)
                copy(raf, moov.start + moov.size, len, out)
            }
        }
    }

    // ---- building ----

    private fun udta(t: Tags): ByteArray {
        val items = ArrayList<ByteArray>()
        items += textItem("\u00A9nam", t.title)
        items += textItem("\u00A9ART", t.artist)
        items += textItem("aART", t.artist)
        t.album?.takeIf { it.isNotBlank() }?.let { items += textItem("\u00A9alb", it) }
        t.cover?.let { items += item("covr", data(if (t.coverIsPng) 14 else 13, it)) }
        val ilst = box("ilst", items.fold(ByteArray(0)) { a, b -> a + b })
        val hdlr = box("hdlr", ByteArray(4 + 4) + "mdir".toByteArray(ISO_8859_1) + "appl".toByteArray(ISO_8859_1) + ByteArray(4 + 4 + 1))
        val meta = box("meta", ByteArray(4) + hdlr + ilst)
        return box("udta", meta)
    }

    private fun textItem(type: String, value: String) = item(type, data(1, value.toByteArray(UTF_8)))
    private fun item(type: String, data: ByteArray) = box(type, data)
    private fun data(typeCode: Int, payload: ByteArray) =
        box("data", ByteBuffer.allocate(8 + payload.size).putInt(typeCode).putInt(0).put(payload).array())

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(ISO_8859_1)).put(payload).array()

    // ---- parsing ----

    private fun readBoxes(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val tb = ByteArray(4).also { raf.readFully(it) }
            var header = 8
            if (size == 1L) { size = raf.readLong(); header = 16 } else if (size == 0L) size = to - pos
            require(size >= header && pos + size <= to) { "Corrupt MP4 box at $pos" }
            out += Box(String(tb, ISO_8859_1), pos, size, header)
            pos += size
        }
        return out
    }

    /** Child boxes of [buf]; positions are offsets into [buf]. */
    private fun children(buf: ByteArray, from: Int = 0, to: Int = buf.size): List<Box> {
        val bb = ByteBuffer.wrap(buf)
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val size = bb.getInt(pos).toLong() and 0xFFFFFFFFL
            val type = String(buf, pos + 4, 4, ISO_8859_1)
            require(size >= 8 && pos + size <= to) { "Corrupt box '$type' inside moov" }
            out += Box(type, pos.toLong(), size, 8)
            pos += size.toInt()
        }
        return out
    }

    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")

    /** Adds [delta] to every chunk offset that points behind the (old) end of moov. */
    private fun shiftChunkOffsets(buf: ByteArray, from: Int, to: Long, moovEnd: Long, delta: Long) {
        val bb = ByteBuffer.wrap(buf)
        for (b in children(buf, from, to.toInt())) {
            val inner = b.start.toInt() + b.header
            when (b.type) {
                in CONTAINERS -> shiftChunkOffsets(buf, inner, b.start + b.size, moovEnd, delta)
                "stco" -> {
                    val n = bb.getInt(inner + 4)
                    for (i in 0 until n) {
                        val p = inner + 8 + i * 4
                        val v = bb.getInt(p).toLong() and 0xFFFFFFFFL
                        if (v >= moovEnd) bb.putInt(p, (v + delta).toInt())
                    }
                }
                "co64" -> {
                    val n = bb.getInt(inner + 4)
                    for (i in 0 until n) {
                        val p = inner + 8 + i * 8
                        val v = bb.getLong(p)
                        if (v >= moovEnd) bb.putLong(p, v + delta)
                    }
                }
            }
        }
    }

    private fun copy(raf: RandomAccessFile, from: Long, to: Long, out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        raf.seek(from)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("Unexpected end of file")
            out.write(buf, 0, n)
            left -= n
        }
    }
}
