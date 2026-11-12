package com.ruslancams.tv.player

/**
 * Monotonic time provider to allow deterministic unit testing of watchdogs and rate limiters.
 */
interface MonotonicClock {
    fun elapsedRealtime(): Long
}

class SystemMonotonicClock : MonotonicClock {
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * Diagnostic status of the video player.
 */
enum class PlaybackStatus {
    IDLE,
    CONNECTING,
    LIVE,
    BUFFERING,
    STALLED,
    ERROR
}

/**
 * Action decided by the watchdog check.
 */
enum class WatchdogAction {
    NONE,
    RECOVER,
    WAIT_BACKOFF
}

/**
 * Detects frozen video frames and manages recovery rate limiting.
 *
 * Unlike standard ExoPlayer listeners which only report unhandled PlaybackException,
 * this watchdog verifies that actual decoded video frames are continuously rendered
 * onto the surface during playback.
 */
class PlaybackWatchdog(
    private val clock: MonotonicClock = SystemMonotonicClock(),
    val stallThresholdMs: Long = 12000L,
    val initialConnectTimeoutMs: Long = 15000L,
    val bufferingTimeoutMs: Long = 15000L,
    val minRetryIntervalMs: Long = 2000L,
    val maxRetryIntervalMs: Long = 20000L,
    val backoffMultiplier: Double = 2.0,
    val stablePlaybackResetMs: Long = 15000L
) {
    var isPrepared: Boolean = false
        private set
    var hasRenderedFirstFrame: Boolean = false
        private set
    var isBuffering: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    var isReleased: Boolean = false
        private set

    var prepareStartTimeMs: Long = 0L
        private set
    var bufferStartTimeMs: Long = 0L
        private set
    var lastFrameRenderedTimeMs: Long = 0L
        private set
    var lastRecoveryAttemptTimeMs: Long = 0L
        private set
    var firstFrameRenderedTimeMs: Long = 0L
        private set
    var stablePlaybackStartTimeMs: Long = 0L
        private set

    var totalFramesRendered: Long = 0L
        private set
    var consecutiveFailures: Int = 0
        private set

    fun onPrepareStarted() {
        val now = clock.elapsedRealtime()
        isPrepared = true
        hasRenderedFirstFrame = false
        isBuffering = false
        prepareStartTimeMs = now
        lastFrameRenderedTimeMs = 0L
        stablePlaybackStartTimeMs = 0L
    }

    fun onFirstFrameRendered() {
        val now = clock.elapsedRealtime()
        hasRenderedFirstFrame = true
        firstFrameRenderedTimeMs = now
        lastFrameRenderedTimeMs = now
        stablePlaybackStartTimeMs = now
        isBuffering = false
        totalFramesRendered++
    }

    fun onFrameRendered() {
        val now = clock.elapsedRealtime()
        hasRenderedFirstFrame = true
        val previousFrameTime = lastFrameRenderedTimeMs
        lastFrameRenderedTimeMs = now
        isBuffering = false
        totalFramesRendered++

        // Only reset failure counter after a sustained period of stable frame delivery.
        // A single frame after a long stall does not qualify as stable playback.
        if (stablePlaybackStartTimeMs == 0L || (previousFrameTime > 0L && (now - previousFrameTime) > stallThresholdMs)) {
            stablePlaybackStartTimeMs = now
        } else if (consecutiveFailures > 0 && (now - stablePlaybackStartTimeMs) >= stablePlaybackResetMs) {
            consecutiveFailures = 0
            stablePlaybackStartTimeMs = now
        }
    }

    fun onBufferingStarted() {
        val now = clock.elapsedRealtime()
        stablePlaybackStartTimeMs = 0L
        if (!isBuffering) {
            isBuffering = true
            bufferStartTimeMs = now
        }
    }

    fun onBufferingEnded() {
        isBuffering = false
    }

    fun pause() {
        isPaused = true
        stablePlaybackStartTimeMs = 0L
    }

    fun resume() {
        isPaused = false
        stablePlaybackStartTimeMs = 0L
        // Reset timers on resume so resume itself doesn't falsely trigger stall
        val now = clock.elapsedRealtime()
        if (hasRenderedFirstFrame) {
            lastFrameRenderedTimeMs = now
        } else if (isPrepared) {
            prepareStartTimeMs = now
        }
        if (isBuffering) {
            bufferStartTimeMs = now
        }
    }

    fun release() {
        isReleased = true
        isPrepared = false
        isBuffering = false
        isPaused = true
        stablePlaybackStartTimeMs = 0L
    }

    fun getNextCooldownMs(): Long {
        if (consecutiveFailures <= 0) return minRetryIntervalMs
        var delay = minRetryIntervalMs
        for (i in 1 until consecutiveFailures) {
            delay = (delay * backoffMultiplier).toLong()
            if (delay >= maxRetryIntervalMs) {
                return maxRetryIntervalMs
            }
        }
        return delay.coerceAtMost(maxRetryIntervalMs)
    }

    /**
     * Checks if playback has stalled and determines whether recovery should be initiated.
     */
    fun checkHealth(): WatchdogAction {
        if (isReleased || isPaused || !isPrepared) {
            return WatchdogAction.NONE
        }

        val now = clock.elapsedRealtime()

        // 1. Check initial connection timeout (started preparing, but no first frame received)
        if (!hasRenderedFirstFrame) {
            if (now - prepareStartTimeMs > initialConnectTimeoutMs) {
                return checkRateLimitAndDecide(now)
            }
            return WatchdogAction.NONE
        }

        // 2. Check buffering timeout (stuck in buffering without recovering)
        if (isBuffering) {
            if (now - bufferStartTimeMs > bufferingTimeoutMs) {
                return checkRateLimitAndDecide(now)
            }
            return WatchdogAction.NONE
        }

        // 3. Check frame delivery stall during normal playback
        if (now - lastFrameRenderedTimeMs > stallThresholdMs) {
            return checkRateLimitAndDecide(now)
        }

        return WatchdogAction.NONE
    }

    /**
     * Called when a recovery attempt is actually initiated.
     */
    fun recordRecoveryAttempt() {
        val now = clock.elapsedRealtime()
        lastRecoveryAttemptTimeMs = now
        stablePlaybackStartTimeMs = 0L
        consecutiveFailures++
    }

    private fun checkRateLimitAndDecide(now: Long): WatchdogAction {
        val cooldown = getNextCooldownMs()
        val timeSinceLastAttempt = now - lastRecoveryAttemptTimeMs
        return if (timeSinceLastAttempt >= cooldown) {
            WatchdogAction.RECOVER
        } else {
            WatchdogAction.WAIT_BACKOFF
        }
    }
}
