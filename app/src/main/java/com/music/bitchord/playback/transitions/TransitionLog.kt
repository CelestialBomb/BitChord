package com.music.bitchord.playback.transitions

import android.util.Log

/**
 * One line per transition decision, start, cancel and failure, for
 * `adb logcat -s BitChordTransitions BitChordCrossfade`. The controller's own
 * v1.9.1 lines stay under `BitChordCrossfade`.
 */
internal object TransitionLog {
    const val TAG = "BitChordTransitions"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }
}
