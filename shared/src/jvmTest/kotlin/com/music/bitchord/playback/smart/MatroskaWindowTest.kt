package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tail window made decodable by stitching it onto the header; see [MatroskaWindow]. */
class MatroskaWindowTest {
    private fun idBytes(id: Long): ByteArray {
        val length = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(length) { (id shr (8 * (length - 1 - it)) and 0xFF).toByte() }
    }

    /** A size as a 4-byte vint (0x10 marker), enough for the small bodies here. */
    private fun sizeBytes(size: Int): ByteArray = byteArrayOf(
        (0x10 or (size shr 24 and 0x0F)).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
    )

    private fun element(id: Long, body: ByteArray) = idBytes(id) + sizeBytes(body.size) + body

    private val ebml = element(0x1A45DFA3L, "webm-doctype".toByteArray())
    private val info = element(0x1549A966L, ByteArray(20) { 1 })
    private val tracks = element(0x1654AE6BL, ByteArray(30) { 2 })
    private val seekHead = element(0x114D9B74L, ByteArray(12) { 3 })
    private val cues = element(0x1C53BB6BL, ByteArray(40) { 4 })

    /** A cluster whose audio bytes may contain the cluster id itself, as real data can. */
    private fun cluster(fill: Int, size: Int = 60, lookalike: Boolean = false): ByteArray {
        val body = ByteArray(size) { fill.toByte() }
        if (lookalike) idBytes(0x1F43B675L).copyInto(body, 10)
        return element(0x1F43B675L, body)
    }

    private fun header(vararg children: ByteArray): ByteArray =
        ebml + idBytes(0x18538067L) + byteArrayOf(0x01, 0, 0, 0, 0, 0x10, 0, 0) + children.reduce { a, b -> a + b }

    private val fullHeader = header(seekHead, info, tracks, cluster(9))

    @Test
    fun theStitchedFileIsHeaderInfoTracksAndTheTailsClusters() {
        val a = cluster(0x11)
        val b = cluster(0x22)
        val tail = ByteArray(25) { 7 } + a + b // starts mid-cluster, with junk
        val stitched = assertNotNull(MatroskaWindow.stitch(fullHeader, tail))

        assertContentEquals(ebml, stitched.copyOfRange(0, ebml.size))
        val afterEbml = stitched.copyOfRange(ebml.size, stitched.size)
        assertContentEquals(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), afterEbml.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1), afterEbml.copyOfRange(4, 12), "unknown-length segment")
        assertContentEquals(info + tracks + a + b, afterEbml.copyOfRange(12, afterEbml.size))
    }

    @Test
    fun theSeekHeadAndTheSeekIndexAreLeftOut() {
        val tail = cluster(1) + cluster(2) + cues // the index at the end of the file
        val stitched = assertNotNull(MatroskaWindow.stitch(fullHeader, tail))
        assertEquals(-1, indexOf(stitched, seekHead), "positions in the seek head are those of the original file")
        assertEquals(-1, indexOf(stitched, cues), "so are the positions in the seek index")
        assertTrue(indexOf(stitched, cluster(2)) > 0)
    }

    @Test
    fun aClusterIdInsideAudioIsNotTakenForAClusterStart() {
        // The first cluster id in the tail is inside a cluster that began before the tail; only the real one counts.
        val real = cluster(0x33)
        val partial = ByteArray(5) + idBytes(0x1F43B675L) + sizeBytes(900) + ByteArray(20) // claims 900 bytes: runs past the end
        val tail = partial + real + cluster(0x44)
        val stitched = assertNotNull(MatroskaWindow.stitch(fullHeader, tail))
        assertContentEquals(real + cluster(0x44), stitched.copyOfRange(stitched.size - (real.size + cluster(0x44).size), stitched.size))
    }

    @Test
    fun aLookalikeInsideAClustersAudioDoesNotSplitIt() {
        val withLookalike = cluster(0x55, lookalike = true)
        val tail = withLookalike + cluster(0x66)
        val stitched = assertNotNull(MatroskaWindow.stitch(fullHeader, tail))
        assertContentEquals(withLookalike + cluster(0x66), stitched.copyOfRange(stitched.size - tail.size, stitched.size))
    }

    @Test
    fun aTailThatEndsOnOneCompleteClusterWorks() {
        val only = cluster(0x77)
        assertNotNull(MatroskaWindow.stitch(fullHeader, ByteArray(8) + only))
    }

    @Test
    fun withoutTracksOrInfoThereIsNothingToStitch() {
        assertNull(MatroskaWindow.stitch(header(seekHead, info, cluster(9)), cluster(1) + cluster(2)))
        assertNull(MatroskaWindow.stitch(header(seekHead, tracks, cluster(9)), cluster(1) + cluster(2)))
    }

    @Test
    fun aHeaderCutOffBeforeTheTracksHasNothingToStitch() {
        val cut = fullHeader.copyOfRange(0, ebml.size + 12 + info.size + 10)
        assertNull(MatroskaWindow.stitch(cut, cluster(1) + cluster(2)))
    }

    @Test
    fun anythingThatIsNotMatroskaIsRefused() {
        val mp4 = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(60)
        assertNull(MatroskaWindow.stitch(mp4, cluster(1) + cluster(2)))
        assertNull(MatroskaWindow.stitch(ByteArray(0), cluster(1)))
    }

    @Test
    fun aTailWithNoWholeClusterHasNothingToStitch() {
        assertNull(MatroskaWindow.stitch(fullHeader, ByteArray(200) { 5 }))
        val cutOff = cluster(1, size = 500).copyOfRange(0, 100)
        assertNull(MatroskaWindow.stitch(fullHeader, cutOff))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }
}
