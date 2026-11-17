package com.music.bitchord.playback.smart

/**
 * Makes a WebM stream's tail decodable without the rest of the file.
 *
 * Android's Matroska extractor walks the cluster headers of the whole file when
 * it opens it, so a window fetched from the end of a stream (with a hole of
 * unread bytes before it) cannot be seeked into: the extractor finds no sample.
 * The window's own bytes are fine, though, and cluster timestamps are absolute.
 * So the decoder is handed a small standalone file instead: the stream's EBML
 * header, its Info and Tracks, and the whole clusters of the tail, with the
 * Segment marked unknown-length so nothing refers to bytes that are not there.
 * The seek index and seek head are left out on purpose: their positions are
 * those of the original file.
 *
 * Only WebM/Matroska, which is what YouTube's Opus streams are. [stitch] returns
 * null for anything else, or when the pieces it needs are not in the bytes
 * given, and the caller then has no window to show rather than a wrong one.
 */
object MatroskaWindow {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TRACKS = 0x1654AE6BL
    private const val CLUSTER = 0x1F43B675L

    private val UNKNOWN_SEGMENT_SIZE = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

    /**
     * @param header the first bytes of the stream, enough to hold the EBML header, Info and Tracks.
     * @param tail the stream's last bytes, to its very end, starting anywhere.
     */
    fun stitch(header: ByteArray, tail: ByteArray): ByteArray? {
        val read = Cursor(header)
        val ebml = read.element() ?: return null
        if (ebml.id != EBML) return null
        val segment = read.idAndSizeOnly() ?: return null
        if (segment.id != SEGMENT) return null

        var info: Element? = null
        var tracks: Element? = null
        while (read.position < header.size) {
            val child = read.element() ?: break
            if (child.id == CLUSTER) break
            when (child.id) {
                INFO -> info = child
                TRACKS -> tracks = child
            }
        }
        if (info == null || tracks == null) return null

        val clusters = clustersOf(tail) ?: return null
        val out = java.io.ByteArrayOutputStream(header.size + tail.size)
        out.write(header, ebml.start, ebml.end - ebml.start)
        out.write(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67))
        out.write(UNKNOWN_SEGMENT_SIZE)
        out.write(header, info.start, info.end - info.start)
        out.write(header, tracks.start, tracks.end - tracks.start)
        out.write(tail, clusters.first, clusters.last + 1 - clusters.first)
        return out.toByteArray()
    }

    /**
     * The span of whole, back-to-back clusters in [tail]: from the first genuine
     * cluster (the id bytes can occur inside audio, so a candidate counts only if
     * its size leads to another cluster or to the end) to the end of the last
     * one, which drops a trailing seek index.
     */
    internal fun clustersOf(tail: ByteArray): IntRange? {
        var candidate = 0
        while (candidate <= tail.size - 5) {
            if (isClusterId(tail, candidate)) {
                val end = clusterEnd(tail, candidate)
                if (end != null && (end == tail.size || (end + 4 <= tail.size && isClusterId(tail, end)))) {
                    // Walk the run of clusters from here.
                    var stop: Int = end
                    while (stop + 4 <= tail.size && isClusterId(tail, stop)) {
                        val next = clusterEnd(tail, stop) ?: break
                        stop = next
                    }
                    return candidate until stop
                }
            }
            candidate++
        }
        return null
    }

    private fun isClusterId(bytes: ByteArray, at: Int): Boolean =
        at + 4 <= bytes.size &&
            bytes[at] == 0x1F.toByte() && bytes[at + 1] == 0x43.toByte() &&
            bytes[at + 2] == 0xB6.toByte() && bytes[at + 3] == 0x75.toByte()

    /** Where the cluster starting at [at] ends, or null if its size is unknown or runs past the bytes. */
    private fun clusterEnd(bytes: ByteArray, at: Int): Int? {
        val cursor = Cursor(bytes, at + 4)
        val size = cursor.size() ?: return null
        if (size < 0) return null
        val end = cursor.position.toLong() + size
        return if (end <= bytes.size) end.toInt() else null
    }

    private class Element(val id: Long, val start: Int, val end: Int)

    private class Cursor(val bytes: ByteArray, var position: Int = 0) {
        /** Reads an element id (its length marker kept, as ids are written), or null. */
        fun id(): Long? {
            if (position >= bytes.size) return null
            val first = bytes[position].toInt() and 0xFF
            val length = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (length !in 1..4 || position + length > bytes.size) return null
            var value = 0L
            repeat(length) { value = (value shl 8) or (bytes[position + it].toLong() and 0xFF) }
            position += length
            return value
        }

        /** Reads a data size: 1 to 8 bytes with a length marker; -1 for "unknown"; null when it is not all here. */
        fun size(): Long? {
            if (position >= bytes.size) return null
            val first = bytes[position].toInt() and 0xFF
            val length = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (length !in 1..8 || position + length > bytes.size) return null
            var value = (first and (0xFF shr length)).toLong()
            var allOnes = value == (0xFF shr length).toLong()
            for (index in 1 until length) {
                val byte = bytes[position + index].toInt() and 0xFF
                value = (value shl 8) or byte.toLong()
                if (byte != 0xFF) allOnes = false
            }
            position += length
            return if (allOnes) -1 else value
        }

        /** An element read whole, or null if it is cut off or has no known size. */
        fun element(): Element? {
            val start = position
            val id = id() ?: return null
            val size = size() ?: return null
            if (size < 0) return null
            val end = position.toLong() + size
            if (end > bytes.size) return null
            position = end.toInt()
            return Element(id, start, position)
        }

        /** An id and size read without skipping the body: for the Segment, whose children come next. */
        fun idAndSizeOnly(): Element? {
            val start = position
            val id = id() ?: return null
            size() ?: return null
            return Element(id, start, position)
        }
    }
}
