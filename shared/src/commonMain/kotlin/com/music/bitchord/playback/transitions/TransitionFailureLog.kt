package com.music.bitchord.playback.transitions

/**
 * Remembers which outgoing -> incoming pairs had a blend fail, so the 250 ms
 * re-planning can never restart the same failing blend at the same point.
 *
 * A failure is any bail that was not the listener's own skip, seek, pause or
 * queue edit. The policy for a pair is escalating and finite:
 *  - no failures: [Verdict.NORMAL] — whatever the planner chose;
 *  - one failure: [Verdict.PLAIN_CROSSFADE] — no loop mix, no DJ blend;
 *  - two or more: [Verdict.NONE] — nothing is armed, the song plays to its end.
 *
 * Kept for the session only. Going back to a song clears what is known about it
 * ([clearFor]), so a listener who returns to a pair gets a fresh attempt.
 *
 * Pure Kotlin so the policy is unit-testable without a device.
 */
class TransitionFailureLog {
    enum class Verdict { NORMAL, PLAIN_CROSSFADE, NONE }

    private class Entry(val from: String, val to: String, var count: Int)

    private val failures = HashMap<String, Entry>()

    /** A readable key for logs. Media IDs can contain anything, so [clearFor] never parses it back. */
    fun key(outgoingId: String, incomingId: String) = "$outgoingId->$incomingId"

    /** Records one failed attempt for [outgoingId] -> [incomingId] and returns the verdict for the next attempt. */
    fun recordFailure(outgoingId: String, incomingId: String): Verdict {
        val entry = failures.getOrPut(key(outgoingId, incomingId)) { Entry(outgoingId, incomingId, 0) }
        entry.count++
        return verdictFor(entry.count)
    }

    fun verdict(outgoingId: String, incomingId: String): Verdict =
        verdictFor(failures[key(outgoingId, incomingId)]?.count ?: 0)

    fun failureCount(outgoingId: String, incomingId: String): Int =
        failures[key(outgoingId, incomingId)]?.count ?: 0

    /** Forgets every failure that involves [mediaId] on either side. */
    fun clearFor(mediaId: String) {
        failures.values.removeAll { it.from == mediaId || it.to == mediaId }
    }

    fun clear() = failures.clear()

    val isEmpty: Boolean get() = failures.isEmpty()

    private fun verdictFor(count: Int): Verdict = when {
        count <= 0 -> Verdict.NORMAL
        count == 1 -> Verdict.PLAIN_CROSSFADE
        else -> Verdict.NONE
    }
}
