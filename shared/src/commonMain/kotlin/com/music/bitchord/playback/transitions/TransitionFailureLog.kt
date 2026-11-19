package com.music.bitchord.playback.transitions

/**
 * Remembers which outgoing -> incoming pairs had a transition fail, so the
 * 250 ms re-planning can never restart the same failing transition at the
 * same point.
 *
 * A failure is any stop that was not the listener's own skip, seek, pause or
 * queue edit. What a pair gets next depends on what failed, and only ever gets
 * stricter (design doc 4.6):
 *  - a [Kind.LOOP_MIX] failed: [Verdict.NO_LOOP], the planner's own blend, DJ included;
 *  - a [Kind.BLEND] failed: [Verdict.PLAIN_CROSSFADE], no loop mix and no DJ blend;
 *  - a [Kind.PLAIN] crossfade failed: [Verdict.NONE], nothing is armed and the song plays out.
 *
 * Kept for the session only. Going back to a song clears what is known about it
 * ([clearFor]), so a listener who returns to a pair gets a fresh attempt.
 *
 * Pure Kotlin so the policy is unit-testable without a device.
 */
class TransitionFailureLog {
    /** In order of strictness. */
    enum class Verdict { NORMAL, NO_LOOP, PLAIN_CROSSFADE, NONE }

    /** What kind of transition failed. */
    enum class Kind(val next: Verdict) {
        LOOP_MIX(Verdict.NO_LOOP),
        BLEND(Verdict.PLAIN_CROSSFADE),
        PLAIN(Verdict.NONE),
    }

    private class Entry(val from: String, val to: String, var verdict: Verdict, var count: Int)

    private val failures = HashMap<String, Entry>()

    /** A readable key for logs. Media IDs can contain anything, so [clearFor] never parses it back. */
    fun key(outgoingId: String, incomingId: String) = "$outgoingId->$incomingId"

    /** Records one failed [kind] for [outgoingId] -> [incomingId] and returns the verdict for the next attempt. */
    fun recordFailure(outgoingId: String, incomingId: String, kind: Kind): Verdict {
        val entry = failures.getOrPut(key(outgoingId, incomingId)) {
            Entry(outgoingId, incomingId, Verdict.NORMAL, 0)
        }
        entry.count++
        if (kind.next > entry.verdict) entry.verdict = kind.next
        return entry.verdict
    }

    fun verdict(outgoingId: String, incomingId: String): Verdict =
        failures[key(outgoingId, incomingId)]?.verdict ?: Verdict.NORMAL

    fun failureCount(outgoingId: String, incomingId: String): Int =
        failures[key(outgoingId, incomingId)]?.count ?: 0

    /** Forgets every failure that involves [mediaId] on either side. */
    fun clearFor(mediaId: String) {
        failures.values.removeAll { it.from == mediaId || it.to == mediaId }
    }
}
