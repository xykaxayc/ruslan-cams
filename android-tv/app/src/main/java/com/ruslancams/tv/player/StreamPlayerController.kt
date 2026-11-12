package com.ruslancams.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView

/**
 * Robust controller for video playback on Android TV.
 *
 * Encapsulates:
 * 1. ExoPlayer lifecycle and low-latency buffer configuration.
 * 2. Continuous frame arrival monitoring via [PlaybackWatchdog] and [ExoPlayer.setVideoFrameMetadataListener].
 * 3. Automatic player recreation upon freeze or fatal RTSP network disconnect with exponential backoff.
 * 4. Safe cleanup that cancels all scheduled runnables upon pause/release.
 * 5. Sanitized diagnostic logging (no passwords or tokens in logs).
 */
class StreamPlayerController(
    private val context: Context,
    private val playerView: PlayerView,
    private val tag: String = "StreamPlayer",
    private val isGridTile: Boolean = false,
    private val clock: MonotonicClock = SystemMonotonicClock(),
    private val onStatusChanged: (PlaybackStatus, String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val LOG_TAG = "RuslanTVPlayer"

        fun sanitizeUrl(url: String): String {
            return url
                .replace(Regex("://([^:@]+):([^@]+)@"), "://$1:***@")
                .replace(Regex("([?&]token=)[^&]+"), "$1***")
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val watchdog = PlaybackWatchdog(clock = clock)

    private var player: ExoPlayer? = null
    private var streamUrl: String = ""
    private var currentGeneration: Long = 0L
    private var currentFrameListener: VideoFrameMetadataListener? = null
    private var currentPlayerListener: Player.Listener? = null

    var isReleased: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    var currentStatus: PlaybackStatus = PlaybackStatus.IDLE
        private set

    private var reconnectRunnable: Runnable? = null
    private var isRecoveryPending = false
    var customBufferMs: Int? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isReleased || isPaused) return

            if (!isRecoveryPending) {
                val action = watchdog.checkHealth()
                when (action) {
                    WatchdogAction.RECOVER -> {
                        Log.w(
                            LOG_TAG,
                            "[$tag] Зависание обнаружено watchdog: кадры не поступают > ${watchdog.stallThresholdMs}мс. Инициализация переподключения."
                        )
                        scheduleRecovery("Зависание кадров (таймаут ${watchdog.stallThresholdMs}мс)")
                    }
                    WatchdogAction.WAIT_BACKOFF -> {
                        Log.d(
                            LOG_TAG,
                            "[$tag] Watchdog: поток не активен, ожидание задержки backoff (${watchdog.getNextCooldownMs()}мс)..."
                        )
                    }
                    WatchdogAction.NONE -> {}
                }
            }

            if (!isReleased && !isPaused) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    /**
     * Start playing an RTSP stream.
     */
    fun start(url: String) {
        if (isReleased) {
            Log.e(LOG_TAG, "[$tag] Невозможно запустить освобождённый контроллер!")
            return
        }
        streamUrl = url
        Log.i(LOG_TAG, "[$tag] Запуск потока: ${sanitizeUrl(url)}")
        updateStatus(PlaybackStatus.CONNECTING, "Подключение...")
        initPlayerAndStart()
        startWatchdogLoop()
    }

    /**
     * Pauses watchdog and playback when Activity is backgrounded or hidden.
     */
    fun pause() {
        if (isReleased || isPaused) return
        currentGeneration++
        isPaused = true
        Log.i(LOG_TAG, "[$tag] Приостановка контроллера (пауза)")
        mainHandler.removeCallbacks(watchdogRunnable)
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        isRecoveryPending = false
        watchdog.pause()
        detachAndReleaseCurrentPlayer()
        updateStatus(PlaybackStatus.IDLE, "Приостановлено")
    }

    /**
     * Resumes watchdog and playback when Activity returns to foreground.
     */
    fun resume() {
        if (isReleased || !isPaused) return
        isPaused = false
        isRecoveryPending = false
        Log.i(LOG_TAG, "[$tag] Возобновление контроллера")
        watchdog.resume()
        if (streamUrl.isNotEmpty()) {
            updateStatus(PlaybackStatus.CONNECTING, "Возобновление...")
            initPlayerAndStart()
            startWatchdogLoop()
        }
    }

    /**
     * Completely releases player, cancels all pending callbacks, and detaches from View.
     */
    fun release() {
        if (isReleased) return
        currentGeneration++
        isReleased = true
        isPaused = true
        isRecoveryPending = false
        Log.i(LOG_TAG, "[$tag] Полное освобождение контроллера (release)")

        mainHandler.removeCallbacks(watchdogRunnable)
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null

        watchdog.release()
        detachAndReleaseCurrentPlayer()
        updateStatus(PlaybackStatus.IDLE, "Остановлено")
    }

    private fun startWatchdogLoop() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, 1000L)
    }

    private fun initPlayerAndStart() {
        if (isReleased || isPaused) return

        currentGeneration++
        val generation = currentGeneration

        detachAndReleaseCurrentPlayer()

        val targetBufferBytes = if (isGridTile) 512 * 1024 else 6 * 1024 * 1024
        val allocator = DefaultAllocator(true, 16 * 1024)

        val baseBuffer = (customBufferMs ?: 3000).coerceAtLeast(1000)
        val minBufferMs: Int
        val maxBufferMs: Int
        val bufferForPlaybackMs: Int
        val bufferForPlaybackAfterRebufferMs: Int

        if (isGridTile) {
            minBufferMs = 1200
            maxBufferMs = 3000
            bufferForPlaybackMs = 500
            bufferForPlaybackAfterRebufferMs = 1000
        } else {
            minBufferMs = baseBuffer
            maxBufferMs = maxOf(baseBuffer * 2, 8000)
            bufferForPlaybackMs = minOf(1000, baseBuffer / 3)
            bufferForPlaybackAfterRebufferMs = minOf(baseBuffer - 200, (baseBuffer * 0.8).toInt())
        }

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(targetBufferBytes)
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                bufferForPlaybackMs,
                bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val newPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        if (isGridTile) {
            newPlayer.trackSelectionParameters = newPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

        // Active frame arrival tracking - sequentially updated on mainHandler
        val frameListener = VideoFrameMetadataListener { _, _, _, _ ->
            mainHandler.post {
                if (isReleased || isPaused || generation != currentGeneration || player !== newPlayer) {
                    return@post
                }
                watchdog.onFrameRendered()
                if (currentStatus != PlaybackStatus.LIVE) {
                    updateStatus(PlaybackStatus.LIVE, "Прямой эфир")
                }
            }
        }
        currentFrameListener = frameListener
        newPlayer.setVideoFrameMetadataListener(frameListener)

        val playerListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (isReleased || isPaused || generation != currentGeneration || player !== newPlayer) return
                watchdog.onFirstFrameRendered()
                Log.i(LOG_TAG, "[$tag] Отрисован первый видеокадр")
                updateStatus(PlaybackStatus.LIVE, "Прямой эфир")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (isReleased || isPaused || generation != currentGeneration || player !== newPlayer) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        watchdog.onBufferingStarted()
                        Log.d(LOG_TAG, "[$tag] Буферизация...")
                        updateStatus(PlaybackStatus.BUFFERING, "Буферизация...")
                    }
                    Player.STATE_READY -> {
                        watchdog.onBufferingEnded()
                        Log.d(LOG_TAG, "[$tag] Плеер готов к воспроизведению (STATE_READY)")
                        if (watchdog.hasRenderedFirstFrame) {
                            updateStatus(PlaybackStatus.LIVE, "Прямой эфир")
                        }
                    }
                    Player.STATE_ENDED -> {
                        Log.w(LOG_TAG, "[$tag] Воспроизведение завершилось (STATE_ENDED)")
                        scheduleRecovery("Поток завершён")
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isReleased || isPaused || generation != currentGeneration || player !== newPlayer) return
                val sanitizedMsg = error.message?.let { sanitizeUrl(it) } ?: error.errorCodeName
                Log.e(LOG_TAG, "[$tag] Ошибка ExoPlayer: $sanitizedMsg (код: ${error.errorCode})")
                scheduleRecovery("Ошибка плеера: $sanitizedMsg")
            }
        }
        currentPlayerListener = playerListener
        newPlayer.addListener(playerListener)

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(5000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        watchdog.onPrepareStarted()
        newPlayer.setMediaSource(mediaSource)
        newPlayer.prepare()
        newPlayer.playWhenReady = true

        player = newPlayer
        playerView.player = newPlayer
    }

    private fun scheduleRecovery(reason: String) {
        if (isReleased || isPaused || isRecoveryPending) return
        isRecoveryPending = true

        val cooldown = watchdog.getNextCooldownMs()
        Log.w(
            LOG_TAG,
            "[$tag] Запланировано переподключение через ${cooldown}мс (причина: $reason, попытка #${watchdog.consecutiveFailures + 1})"
        )

        updateStatus(PlaybackStatus.STALLED, "Переподключение...")

        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = Runnable {
            if (isReleased || isPaused) {
                isRecoveryPending = false
                return@Runnable
            }
            watchdog.recordRecoveryAttempt()
            isRecoveryPending = false
            reconnectRunnable = null
            Log.i(LOG_TAG, "[$tag] Выполнение переподключения #${watchdog.consecutiveFailures}...")
            initPlayerAndStart()
        }
        mainHandler.postDelayed(reconnectRunnable!!, cooldown)
    }

    private fun detachAndReleaseCurrentPlayer() {
        val oldPlayer = player
        val oldFrameListener = currentFrameListener
        val oldPlayerListener = currentPlayerListener
        player = null
        currentFrameListener = null
        currentPlayerListener = null
        playerView.player = null
        if (oldPlayer != null) {
            try {
                if (oldFrameListener != null) {
                    oldPlayer.clearVideoFrameMetadataListener(oldFrameListener)
                }
                if (oldPlayerListener != null) {
                    oldPlayer.removeListener(oldPlayerListener)
                }
                oldPlayer.stop()
                oldPlayer.clearMediaItems()
                oldPlayer.release()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "[$tag] Ошибка при освобождении старого плеера: ${e.message}")
            }
        }
    }

    private fun updateStatus(status: PlaybackStatus, message: String) {
        currentStatus = status
        onStatusChanged(status, message)
    }
}
