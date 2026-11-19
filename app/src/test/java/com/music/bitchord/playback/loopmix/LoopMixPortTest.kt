package com.music.bitchord.playback.loopmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoopMixPortTest {

    private val port = LoopMixPort()

    @Test
    fun aReadingIsExtrapolatedWhilePlaying() {
        port.positionUs = 164_000_000
        port.positionNanos = 1_000_000_000
        assertEquals(165.0, port.presentedSeconds(nowNanos = 2_000_000_000, playing = true)!!, 1e-9)
    }

    @Test
    fun anOldReadingDoesNotOutliveItsArm() {
        // The last loop mix on this player left a reading behind, minutes ago.
        port.positionUs = 200_000_000
        port.positionNanos = 1_000_000_000
        port.disarm()
        assertNull(port.presentedSeconds(nowNanos = 300_000_000_000, playing = true))
    }

    @Test
    fun armingStartsWithoutAReading() {
        port.positionUs = 200_000_000
        port.positionNanos = 1_000_000_000
        port.armIncoming(LoopMixPort.InArm(handoverUs = 12_000_000))
        assertNull(port.presentedSeconds(nowNanos = 300_000_000_000, playing = true))
    }
}
