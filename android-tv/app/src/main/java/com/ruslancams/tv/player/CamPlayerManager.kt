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

/**
 * Legacy player manager providing safe player creation with watchdog-based stall recovery.
 *
 * For full view lifecycle management with PlayerView re-binding,
 * prefer using [StreamPlayerController].
 */
class CamPlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "RuslanTVLegacyPlayer"
    }

    interface StreamStateListener {
        fun onBuffering()
        fun onPlaying()
        fun onError(message: String)
    }

    private val activePlayers = mutableMapOf<ExoPlayer, PlayerHolder>()

    private class PlayerHolder(
        val player: ExoPlayer,
        val watchdog: PlaybackWatchdog,
        val handler: Handler,
        var reconnectRunnable: Runnable? = null,
        var watchdogRunnable: Runnable? = null,
        var frameListener: VideoFrameMetadataListener? = null,
        var playerListener: Player.Listener? = null,
        var isRecoveryPending: Boolean = false,
        var isReleased: Boolean = false
    )

    fun createPlayer(
        streamUrl: String,
        isGridTile: Boolean = true,
        listener: StreamStateListener? = null
    ): ExoPlayer {
        val targetBufferBytes = if (isGridTile) 512 * 1024 else 6 * 1024 * 1024
        val allocator = DefaultAllocator(true, 16 * 1024)

        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(targetBufferBytes)
            .setBufferDurationsMs(
                300,   // minBufferMs
                1200,  // maxBufferMs
                150,   // bufferForPlaybackMs
                300    // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        if (isGridTile) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val watchdog = PlaybackWatchdog()

        val holder = PlayerHolder(player, watchdog, mainHandler)
        activePlayers[player] = holder

        val frameListener = VideoFrameMetadataListener { _, _, _, _ ->
            if (holder.isReleased) return@VideoFrameMetadataListener
            mainHandler.post {
                if (holder.isReleased) return@post
                watchdog.onFrameRendered()
            }
        }
        holder.frameListener = frameListener
        player.setVideoFrameMetadataListener(frameListener)

        fun scheduleReconnect(reason: String) {
            if (holder.isReleased || holder.isRecoveryPending) return
            holder.isRecoveryPending = true

            val cooldown = watchdog.getNextCooldownMs()
            Log.w(TAG, "Переподключение через ${cooldown}мс (причина: $reason, попытка #${watchdog.consecutiveFailures + 1})")
            listener?.onError("Переподключение...")

            holder.reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (holder.isReleased) {
                    holder.isRecoveryPending = false
                    return@Runnable
                }
                holder.isRecoveryPending = false
                holder.reconnectRunnable = null
                watchdog.recordRecoveryAttempt()
                try {
                    val freshSource = RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .setTimeoutMs(5000)
                        .createMediaSource(MediaItem.fromUri(streamUrl))
                    watchdog.onPrepareStarted()
                    player.setMediaSource(freshSource)
                    player.prepare()
                    player.playWhenReady = true
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка переподключения: ${e.message}")
                }
            }
            holder.reconnectRunnable = runnable
            mainHandler.postDelayed(runnable, cooldown)
        }

        val watchdogRunnable = object : Runnable {
            override fun run() {
                if (holder.isReleased) return
                if (!holder.isRecoveryPending) {
                    val action = watchdog.checkHealth()
                    if (action == WatchdogAction.RECOVER) {
                        scheduleReconnect("Зависание видео (кадры не поступают)")
                    }
                }
                if (!holder.isReleased) {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        holder.watchdogRunnable = watchdogRunnable
        mainHandler.postDelayed(watchdogRunnable, 1000L)

        val playerListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (holder.isReleased) return
                watchdog.onFirstFrameRendered()
                holder.reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                holder.reconnectRunnable = null
                holder.isRecoveryPending = false
                listener?.onPlaying()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (holder.isReleased) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        watchdog.onBufferingStarted()
                        listener?.onBuffering()
                    }
                    Player.STATE_READY -> {
                        watchdog.onBufferingEnded()
                        listener?.onPlaying()
                    }
                    Player.STATE_ENDED -> {
                        scheduleReconnect("Поток завершён")
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (holder.isReleased) return
                scheduleReconnect("Ошибка плеера: ${error.errorCodeName}")
            }
        }
        holder.playerListener = playerListener
        player.addListener(playerListener)

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(5000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        watchdog.onPrepareStarted()
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true

        return player
    }

    /**
     * Safely releases the player and cancels all pending reconnect or watchdog runnables.
     */
    fun releasePlayer(player: ExoPlayer?) {
        if (player == null) return
        activePlayers.remove(player)?.let { holder ->
            holder.isReleased = true
            holder.isRecoveryPending = false
            holder.watchdog.release()
            holder.reconnectRunnable?.let { holder.handler.removeCallbacks(it) }
            holder.watchdogRunnable?.let { holder.handler.removeCallbacks(it) }
            holder.reconnectRunnable = null
            holder.watchdogRunnable = null
            try {
                holder.frameListener?.let { listener ->
                    player.clearVideoFrameMetadataListener(listener)
                }
                holder.playerListener?.let { listener ->
                    player.removeListener(listener)
                }
                player.stop()
                player.clearMediaItems()
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка при освобождении плеера: ${e.message}")
            }
        }
    }

    /**
     * Release all active players managed by this instance.
     */
    fun releaseAll() {
        val list = activePlayers.keys.toList()
        list.forEach { releasePlayer(it) }
    }
}
