package com.ruslancams.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackWatchdogTest {

    private class TestMonotonicClock(var currentTimeMs: Long = 1000L) : MonotonicClock {
        override fun elapsedRealtime(): Long = currentTimeMs

        fun advance(ms: Long) {
            currentTimeMs += ms
        }
    }

    private lateinit var clock: TestMonotonicClock
    private lateinit var watchdog: PlaybackWatchdog

    @Before
    fun setUp() {
        clock = TestMonotonicClock(1000L)
        watchdog = PlaybackWatchdog(
            clock = clock,
            stallThresholdMs = 4000L,
            initialConnectTimeoutMs = 8000L,
            bufferingTimeoutMs = 6000L,
            minRetryIntervalMs = 2000L,
            maxRetryIntervalMs = 8000L,
            backoffMultiplier = 2.0,
            stablePlaybackResetMs = 10000L
        )
    }

    @Test
    fun testNormalPlaybackDoesNotStall() {
        watchdog.onPrepareStarted()
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        clock.advance(500)
        watchdog.onFirstFrameRendered()
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // Simulate 12 fps frames arriving every ~83ms
        for (i in 1..50) {
            clock.advance(83)
            watchdog.onFrameRendered()
            assertEquals(WatchdogAction.NONE, watchdog.checkHealth())
        }
        assertEquals(51, watchdog.totalFramesRendered)
    }

    @Test
    fun testDetectsStallWhenFramesStop() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Frames arriving normally
        clock.advance(100)
        watchdog.onFrameRendered()
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // 3.9 seconds pass (under threshold of 4.0s) -> no stall yet
        clock.advance(3900)
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // Pass 4.1 seconds total since last frame -> STALL!
        clock.advance(200)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())
    }

    @Test
    fun testInitialConnectTimeout() {
        watchdog.onPrepareStarted()

        // 7 seconds without first frame (under threshold of 8s) -> still waiting
        clock.advance(7000)
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // 8.1 seconds without first frame -> STALL!
        clock.advance(1100)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())
    }

    @Test
    fun testBufferingTimeout() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Stream enters buffering
        watchdog.onBufferingStarted()
        clock.advance(4000) // 4 seconds in buffering (under 6s timeout)
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // 6.5 seconds in buffering without recovering -> STALL!
        clock.advance(2500)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())

        // Buffering ends
        watchdog.onBufferingEnded()
        watchdog.onFrameRendered()
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())
    }

    @Test
    fun testRateLimitingAndBackoff() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Freeze occurs
        clock.advance(4100)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())

        // Recovery is executed
        watchdog.recordRecoveryAttempt()
        assertEquals(1, watchdog.consecutiveFailures)
        assertEquals(2000L, watchdog.getNextCooldownMs()) // min 2s

        // Immediately checking again within cooldown -> WAIT_BACKOFF
        clock.advance(500)
        assertEquals(WatchdogAction.WAIT_BACKOFF, watchdog.checkHealth())

        // 2 seconds pass -> cooldown elapsed, can retry
        clock.advance(1600)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())

        // Second failure -> backoff doubles to 4000ms
        watchdog.recordRecoveryAttempt()
        assertEquals(2, watchdog.consecutiveFailures)
        assertEquals(4000L, watchdog.getNextCooldownMs())

        clock.advance(2500)
        assertEquals(WatchdogAction.WAIT_BACKOFF, watchdog.checkHealth())

        clock.advance(1600)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())

        // Third failure -> backoff doubles to 8000ms (max)
        watchdog.recordRecoveryAttempt()
        assertEquals(3, watchdog.consecutiveFailures)
        assertEquals(8000L, watchdog.getNextCooldownMs())

        // Multiple repeated checks during backoff cooldown MUST return WAIT_BACKOFF and not change failures
        for (step in 1..7) {
            clock.advance(1000)
            if (step < 8) {
                assertEquals(WatchdogAction.WAIT_BACKOFF, watchdog.checkHealth())
            }
        }
        assertEquals(3, watchdog.consecutiveFailures)
        assertEquals(8000L, watchdog.getNextCooldownMs())

        // After full 8s elapsed, next check triggers RECOVER
        clock.advance(1100)
        assertEquals(WatchdogAction.RECOVER, watchdog.checkHealth())

        // Fourth failure: capped at maxRetryIntervalMs (8000ms)
        watchdog.recordRecoveryAttempt()
        assertEquals(4, watchdog.consecutiveFailures)
        assertEquals(8000L, watchdog.getNextCooldownMs())
    }

    @Test
    fun testStablePlaybackResetsFailures() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Trigger a failure
        clock.advance(4100)
        watchdog.recordRecoveryAttempt()
        assertEquals(1, watchdog.consecutiveFailures)

        // Stream recovers and plays stably for 12 seconds (> stablePlaybackResetMs = 10s)
        for (i in 1..120) {
            clock.advance(100)
            watchdog.onFrameRendered()
        }

        assertEquals(0, watchdog.consecutiveFailures)
        assertEquals(2000L, watchdog.getNextCooldownMs())
    }

    @Test
    fun testSingleFrameAfterLongDowntimeDoesNotResetBackoff() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Trigger two failures -> backoff = 4000ms
        clock.advance(4100)
        watchdog.recordRecoveryAttempt()
        clock.advance(2100)
        watchdog.recordRecoveryAttempt()
        assertEquals(2, watchdog.consecutiveFailures)

        // 20 seconds of silence pass (> stablePlaybackResetMs = 10s)
        clock.advance(20000)

        // A single frame arrives
        watchdog.onFrameRendered()

        // A single frame MUST NOT reset consecutiveFailures!
        // On old buggy code, this will fail because consecutiveFailures becomes 0 immediately.
        assertEquals("Single frame after long downtime must not reset failures", 2, watchdog.consecutiveFailures)
    }

    @Test
    fun testBufferingInterruptsStablePlaybackCountdown() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Trigger failure -> consecutiveFailures = 2
        clock.advance(4100)
        watchdog.recordRecoveryAttempt()
        clock.advance(2100)
        watchdog.recordRecoveryAttempt()
        assertEquals(2, watchdog.consecutiveFailures)

        // 7 seconds of stable frames (needed: 10s)
        for (i in 1..70) {
            clock.advance(100)
            watchdog.onFrameRendered()
        }
        assertEquals(2, watchdog.consecutiveFailures)

        // Buffering occurs -> interrupts stability streak
        watchdog.onBufferingStarted()
        clock.advance(1000)
        watchdog.onBufferingEnded()

        // 5 more seconds of frames (total 7 + 1 + 5 = 13s since start, but only 5s continuous since buffering ended)
        for (i in 1..50) {
            clock.advance(100)
            watchdog.onFrameRendered()
        }

        // Must still NOT be reset, because continuous streak is only 5s < 10s
        assertEquals("Buffering must interrupt stability countdown", 2, watchdog.consecutiveFailures)
    }

    @Test
    fun testPauseInterruptsStablePlaybackCountdown() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // Trigger failure
        clock.advance(4100)
        watchdog.recordRecoveryAttempt()
        assertEquals(1, watchdog.consecutiveFailures)

        // 8 seconds of frames
        for (i in 1..80) {
            clock.advance(100)
            watchdog.onFrameRendered()
        }
        assertEquals(1, watchdog.consecutiveFailures)

        // Pause
        watchdog.pause()
        clock.advance(3000)
        watchdog.resume()

        // 4 seconds of frames
        for (i in 1..40) {
            clock.advance(100)
            watchdog.onFrameRendered()
        }

        // Must still NOT be reset, because continuous streak is only 4s < 10s
        assertEquals("Pause must interrupt stability countdown", 1, watchdog.consecutiveFailures)
    }

    @Test
    fun testPauseAndResume() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        // App goes to background
        watchdog.pause()
        clock.advance(10000) // 10 seconds in background
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // App returns to foreground
        watchdog.resume()
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())

        // Ensure resume updated timestamps so no false stall
        clock.advance(2000)
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())
    }

    @Test
    fun testReleaseCancelsWatchdog() {
        watchdog.onPrepareStarted()
        clock.advance(500)
        watchdog.onFirstFrameRendered()

        watchdog.release()
        assertTrue(watchdog.isReleased)

        clock.advance(10000)
        assertEquals(WatchdogAction.NONE, watchdog.checkHealth())
    }

    @Test
    fun testUrlSanitizationDoesNotLogTokensOrPasswords() {
        val testUrl1 = "rtsp://admin:SecretPassword123@192.168.1.50:554/live"
        val sanitized1 = StreamPlayerController.sanitizeUrl(testUrl1)
        assertFalse("Password must not be in sanitized URL", sanitized1.contains("SecretPassword123"))
        assertTrue("Password must be masked", sanitized1.contains(":***@"))

        val testUrl2 = "http://66.151.34.27:8080/grid.json?token=ruslan2026&other=123"
        val sanitized2 = StreamPlayerController.sanitizeUrl(testUrl2)
        assertFalse("Token must not be in sanitized URL", sanitized2.contains("ruslan2026"))
        assertTrue("Token must be masked", sanitized2.contains("token=***"))
    }
}
