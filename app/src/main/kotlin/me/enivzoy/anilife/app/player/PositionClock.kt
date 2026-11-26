package me.enivzoy.anilife.app.player

// throttles time-pos ticks to ~5hz so the seek bar advances smoothly without chasing every event
internal class PositionClock(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val jumpSeconds: Double = DEFAULT_JUMP_SECONDS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var sentAt = 0L
    private var lastSent = 0.0
    private var sent = false

    fun offer(positionSeconds: Double): Boolean {
        val current = now()
        val due = !sent ||
            current - sentAt >= intervalMillis ||
            kotlin.math.abs(positionSeconds - lastSent) >= jumpSeconds
        if (due) {
            sent = true
            sentAt = current
            lastSent = positionSeconds
        }
        return due
    }

    // makes the next offer due regardless of the interval, for a transport change
    fun flush() {
        sent = false
    }

    fun reset() {
        sent = false
        sentAt = 0L
        lastSent = 0.0
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 200L
        const val DEFAULT_JUMP_SECONDS = 1.0
    }
}