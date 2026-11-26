package me.enivzoy.anilife.app.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PositionClockTest {

    private class FakeClock(var millis: Long = 1_000L) {
        fun advance(delta: Long) {
            millis += delta
        }
    }

    @Test
    fun firstOfferIsAlwaysDue() {
        val clock = FakeClock()
        assertTrue(PositionClock(now = clock::millis).offer(0.0))
    }

    @Test
    fun offersInsideTheIntervalAreDropped() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(10.0)

        clock.advance(199)
        assertFalse(positionClock.offer(10.2))
    }

    @Test
    fun anOfferAfterTheIntervalIsDue() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(10.0)

        clock.advance(200)
        assertTrue(positionClock.offer(10.2))
    }

    @Test
    fun aJumpOfOneSecondBypassesTheInterval() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(10.0)

        clock.advance(10)
        assertTrue(positionClock.offer(11.0))
    }

    @Test
    fun aJumpSmallerThanOneSecondWaits() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(10.0)

        clock.advance(10)
        assertFalse(positionClock.offer(10.99))
    }

    @Test
    fun aBackwardSeekIsAlsoAJump() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(30.0)

        clock.advance(5)
        assertTrue(positionClock.offer(12.0))
    }

    @Test
    fun flushMakesTheNextOfferDue() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(10.0)
        clock.advance(20)

        positionClock.flush()
        assertTrue(positionClock.offer(10.1))
    }

    @Test
    fun resetForgetsEverything() {
        val clock = FakeClock()
        val positionClock = PositionClock(now = clock::millis)
        positionClock.offer(10.0)
        clock.advance(20)
        positionClock.reset()

        assertTrue(positionClock.offer(10.0))
    }
}