package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionKindTest {

    private fun plan(
        style: TransitionStyle,
        variant: FilterTransitionVariant = FilterTransitionVariant.SWEEP,
        echo: Double = 0.0,
        cue: Double = 0.0,
    ) = TransitionPlan(transitionStyle = style, filterVariant = variant, echoSeconds = echo, incomingCueTime = cue)

    @Test
    fun eachPlanStyleIsAKind() {
        assertEquals(TransitionKind.CROSSFADE, TransitionKind.of(plan(TransitionStyle.EQUAL_POWER)))
        assertEquals(TransitionKind.GAPLESS, TransitionKind.of(plan(TransitionStyle.GAPLESS)))
        assertEquals(TransitionKind.BEAT_BLEND, TransitionKind.of(plan(TransitionStyle.DJ_BLEND)))
        assertEquals(TransitionKind.FILTER_SWEEP, TransitionKind.of(plan(TransitionStyle.DJ_FILTER)))
        assertEquals(
            TransitionKind.FILTER_SWEEP,
            TransitionKind.of(plan(TransitionStyle.DJ_FILTER, FilterTransitionVariant.BASS_HANDOFF)),
        )
    }

    @Test
    fun anEchoIsAnEchoOutWhicheverStyleCarriesIt() {
        assertEquals(TransitionKind.ECHO_OUT, TransitionKind.of(plan(TransitionStyle.DJ_FILTER, FilterTransitionVariant.ECHO_RIDE)))
        assertEquals(TransitionKind.ECHO_OUT, TransitionKind.of(plan(TransitionStyle.DJ_BLEND, echo = 0.5)))
    }

    @Test
    fun anEqualPowerFadeIsACrossfadeEvenWithACue() {
        assertEquals(TransitionKind.CROSSFADE, TransitionKind.of(plan(TransitionStyle.EQUAL_POWER, cue = 4.9)))
    }

    @Test
    fun whatAFailureCostsFollowsTheUsersRule() {
        assertEquals(TransitionFailureLog.Kind.LOOP_MIX, TransitionKind.LOOP_MIX.failure)
        for (kind in listOf(TransitionKind.BEAT_BLEND, TransitionKind.FILTER_SWEEP, TransitionKind.ECHO_OUT)) {
            assertEquals(TransitionFailureLog.Kind.BLEND, kind.failure)
        }
        for (kind in listOf(TransitionKind.CROSSFADE, TransitionKind.GAPLESS)) {
            assertEquals(TransitionFailureLog.Kind.PLAIN, kind.failure)
        }
    }
}
