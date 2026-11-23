package me.enivzoy.anilife.app.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/**
 * Prevents the display from sleeping while a video is playing.
 * Call [acquire] when playback starts, [release] when paused or closed.
 */
object WakeLock {
    private interface Kernel32Ext : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val kernel32 by lazy {
        try {
            Native.load("kernel32", Kernel32Ext::class.java) as Kernel32Ext
        } catch (_: Throwable) {
            null
        }
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private var acquired = false

    fun acquire() {
        if (acquired) return
        try {
            kernel32?.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
            acquired = true
        } catch (_: Throwable) {
            // Best effort; ignore on non-Windows or if JNA fails.
        }
    }

    fun release() {
        if (!acquired) return
        try {
            kernel32?.SetThreadExecutionState(ES_CONTINUOUS)
            acquired = false
        } catch (_: Throwable) {
            acquired = false
        }
    }
}