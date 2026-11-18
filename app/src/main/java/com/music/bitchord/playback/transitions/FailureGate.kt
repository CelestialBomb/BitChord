package com.music.bitchord.playback.transitions

import com.music.bitchord.playback.transitions.TransitionFailureLog.Verdict

/**
 * The failure gate (design doc 4.6): which pair the transition in flight runs
 * between, whether a stop is the listener's own or a failure, and what the
 * failure record allows a pair next time.
 *
 * Fixes a v1.9.1 bug, so it runs with Beat-aware off too: a transition that
 * failed was armed again by the 250 ms re-planning at the same point, and a
 * failed loop mix replayed the end of the song until its time ran out.
 */
internal class FailureGate {
    private val failures = TransitionFailureLog()

    private var outId: String? = null
    private var inId: String? = null

    /** This transition's failure is on record already, so it counts once. */
    private var noted = false

    /** Set while the listener's skip, seek, pause or queue edit is ending the transition. */
    var byListener = false
        private set

    private var lastGateLine = ""

    /** The pair in flight, for logs; null when idle. */
    val pair: String?
        get() = outId?.let { out -> inId?.let { failures.key(out, it) } }

    /** The song a position belongs to: the incoming one once it has the session. */
    fun songOf(handedOff: Boolean): String? = if (handedOff) inId else outId

    /** A transition starts between [outgoingId] and [incomingId]. */
    fun begin(outgoingId: String, incomingId: String, kind: String, atMs: Long, durationMs: Long) {
        outId = outgoingId
        inId = incomingId
        noted = false
        TransitionLog.d("start $kind $pair at ${atMs}ms of ${durationMs}ms")
    }

    /** The transition is over, however it ended. */
    fun end() {
        outId = null
        inId = null
    }

    /** Runs [stop] as the listener's own action: nothing it causes is a failure. */
    fun asListener(why: String, phase: Any, stop: () -> Unit) {
        TransitionLog.d("cancel from $phase: $why (pair=$pair)")
        byListener = true
        try {
            stop()
        } finally {
            byListener = false
        }
    }

    /** Records the transition in flight as failed, once, unless the listener ended it. */
    fun noteFailure(what: String, phase: Any) {
        if (byListener || noted) return
        val out = outId ?: return
        val into = inId ?: return
        noted = true
        val next = failures.recordFailure(out, into)
        TransitionLog.w("failure ($what) for $pair at phase=$phase -> next attempt: $next")
    }

    /** What the record allows for [currentId] -> [nextId], logged once per pair and verdict. */
    fun verdict(currentId: String, nextId: String): Verdict {
        val verdict = failures.verdict(currentId, nextId)
        if (verdict != Verdict.NORMAL) {
            val line = "${failures.key(currentId, nextId)}: $verdict after " +
                "${failures.failureCount(currentId, nextId)} failure(s)"
            if (line != lastGateLine) {
                lastGateLine = line
                TransitionLog.w(
                    "gate $line -> " + if (verdict == Verdict.NONE) {
                        "nothing armed, the song plays to its end"
                    } else {
                        "plain crossfade only (no loop mix, no DJ blend)"
                    },
                )
            }
        }
        return verdict
    }

    /** Going back to [mediaId] gives every pair it is in a fresh attempt. */
    fun forget(mediaId: String) {
        failures.clearFor(mediaId)
        lastGateLine = ""
    }
}
