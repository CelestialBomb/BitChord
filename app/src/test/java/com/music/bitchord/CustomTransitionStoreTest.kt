package com.music.bitchord

import com.music.bitchord.data.CustomTransitionStore
import com.music.bitchord.playback.transitions.CustomTransition
import com.music.bitchord.playback.transitions.CustomTransitionSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Which custom transition the controller plays for a pair: saved only with Mix on, or an editor preview. */
class CustomTransitionStoreTest {
    private val saved = CustomTransition(style = "rise", outDurationMs = 200_000, inDurationMs = 180_000)
    private val draft = CustomTransition(style = "quick", bassSwap = true, outDurationMs = 200_000, inDurationMs = 180_000)

    @Before
    fun clean() {
        CustomTransitionStore.replaceAll(CustomTransitionSnapshot())
        CustomTransitionStore.clearPreview()
    }

    @After
    fun cleanAfter() = clean()

    @Test
    fun aSavedPairOnlyCountsWhileMixIsOn() {
        CustomTransitionStore.save("PL", "a", "b", saved)
        assertNull("saved, but Mix is off", CustomTransitionStore.effective("PL", "a", "b"))
        CustomTransitionStore.setMix("PL", true)
        assertEquals(saved, CustomTransitionStore.effective("PL", "a", "b"))
        CustomTransitionStore.setMix("PL", false)
        assertNull(CustomTransitionStore.effective("PL", "a", "b"))
    }

    @Test
    fun aPairBelongsToItsPlaylistAndDirection() {
        CustomTransitionStore.setMix("PL", true)
        CustomTransitionStore.setMix("OTHER", true)
        CustomTransitionStore.save("PL", "a", "b", saved)
        assertNull(CustomTransitionStore.effective("OTHER", "a", "b"))
        assertNull(CustomTransitionStore.effective("PL", "b", "a"))
        assertNull(CustomTransitionStore.effective("PL", "a", "c"))
    }

    @Test
    fun aPreviewStandsInForItsPairEvenWithMixOff() {
        CustomTransitionStore.save("PL", "a", "b", saved)
        CustomTransitionStore.setPreview("PL", "a", "b", draft)
        assertEquals(draft, CustomTransitionStore.effective("PL", "a", "b"))
        assertNull("another pair is untouched", CustomTransitionStore.effective("PL", "b", "c"))
    }

    @Test
    fun aPreviewIsUsedUpOnceItsBlendBegins() {
        CustomTransitionStore.setMix("PL", true)
        CustomTransitionStore.save("PL", "a", "b", saved)
        CustomTransitionStore.setPreview("PL", "a", "b", draft)

        CustomTransitionStore.consumePreview("PL", "x", "y")
        assertEquals("a blend for another pair leaves it", draft, CustomTransitionStore.effective("PL", "a", "b"))

        CustomTransitionStore.consumePreview("PL", "a", "b")
        assertEquals("then the saved one is back", saved, CustomTransitionStore.effective("PL", "a", "b"))
    }

    @Test
    fun aPreviewIsNeverSavedOrBackedUp() {
        CustomTransitionStore.setPreview("PL", "a", "b", draft)
        assertEquals(CustomTransitionSnapshot(), CustomTransitionStore.snapshot.value)
    }

    @Test
    fun savingResettingAndPreviewingTellTheControllerWhichPair() {
        val told = mutableListOf<Pair<String, String>>()
        CustomTransitionStore.onPairEdited = { out, into -> told += out to into }
        try {
            CustomTransitionStore.save("PL", "a", "b", saved)
            CustomTransitionStore.setPreview("PL", "b", "c", draft)
            CustomTransitionStore.reset("PL", "a", "b")
            CustomTransitionStore.setMix("PL", true)
            CustomTransitionStore.consumePreview("PL", "b", "c")
            CustomTransitionStore.replaceAll(CustomTransitionSnapshot())
            assertEquals(listOf("a" to "b", "b" to "c", "a" to "b"), told)
        } finally {
            CustomTransitionStore.onPairEdited = null
        }
    }
}
