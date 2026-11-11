package com.ruslancams.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator

class CamPlayerManager(private val context: Context) {

    interface StreamStateListener {
        fun onBuffering()
        fun onPlaying()
        fun onError(message: String)
    }

    fun createPlayer(
        streamUrl: String,
        isGridTile: Boolean = true,
        listener: StreamStateListener? = null
    ): ExoPlayer {
        // Strict buffer limits: 512KB for grid tiles, 6MB for fullscreen HD
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

        // Hardware accelerated MediaCodec with fallback
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        if (isGridTile) {
            // Mute audio for grid tiles: saves audio tracks and CPU
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

        val mainHandler = Handler(Looper.getMainLooper())
        var reconnectRunnable: Runnable? = null

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(5000)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                listener?.onPlaying()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> listener?.onPlaying()
                    Player.STATE_ENDED -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(error.localizedMessage ?: "Переподключение...")
                // Auto-reconnect isolated to this tile only!
                reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
                reconnectRunnable = Runnable {
                    try {
                        player.setMediaSource(mediaSource)
                        player.prepare()
                        player.playWhenReady = true
                    } catch (_: Exception) {}
                }
                mainHandler.postDelayed(reconnectRunnable!!, 3000)
            }
        })

        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true

        return player
    }
}
