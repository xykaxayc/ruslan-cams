package com.ruslancams.tv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import com.ruslancams.tv.R
import com.ruslancams.tv.databinding.ItemCameraTileBinding
import com.ruslancams.tv.model.CameraItem
import com.ruslancams.tv.player.CamPlayerManager

class CameraTileAdapter(
    private val cameras: List<CameraItem>,
    private val serverHost: String,
    private val onCameraClick: (CameraItem, Int) -> Unit
) : RecyclerView.Adapter<CameraTileAdapter.CameraViewHolder>() {

    private val players = mutableMapOf<Int, ExoPlayer>()
    private var playerManager: CamPlayerManager? = null

    private fun getOrCreatePlayerManager(context: Context): CamPlayerManager {
        return playerManager ?: CamPlayerManager(context.applicationContext).also {
            playerManager = it
        }
    }

    inner class CameraViewHolder(val binding: ItemCameraTileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(camera: CameraItem, position: Int) {
            binding.textTileTitle.text = camera.title

            // D-Pad TV Focus Animation (scale up slightly + glowing border)
            binding.cardContainer.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .translationZ(8f)
                        .setDuration(120)
                        .start()
                } else {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(120)
                        .start()
                }
            }

            // Click on tile -> Zoom into Fullscreen HD
            binding.cardContainer.setOnClickListener {
                onCameraClick(camera, position)
            }

            // Clean previous player on this tile
            binding.playerTile.player = null
            releasePlayer(position)

            val streamUrl = camera.getStreamUrl(baseUrl = serverHost)
            val manager = getOrCreatePlayerManager(binding.root.context)

            binding.overlayLoading.visibility = View.VISIBLE
            binding.textLoadingStatus.text = binding.root.context.getString(R.string.status_connecting)

            val player = manager.createPlayer(
                streamUrl = streamUrl,
                isGridTile = true,
                listener = object : CamPlayerManager.StreamStateListener {
                    override fun onBuffering() {
                        binding.overlayLoading.visibility = View.VISIBLE
                        binding.textLoadingStatus.text = binding.root.context.getString(R.string.status_connecting)
                    }

                    override fun onPlaying() {
                        binding.overlayLoading.visibility = View.GONE
                        binding.dotStatus.setBackgroundResource(R.drawable.dot_live)
                    }

                    override fun onError(message: String) {
                        binding.overlayLoading.visibility = View.VISIBLE
                        binding.textLoadingStatus.text = binding.root.context.getString(R.string.status_reconnecting)
                    }
                }
            )

            players[position] = player
            binding.playerTile.player = player
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CameraViewHolder {
        val binding = ItemCameraTileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        // Automatically size each tile so 2 rows x 4 columns fit on screen simultaneously without scrolling
        val dm = parent.context.resources.displayMetrics
        val screenHeight = dm.heightPixels
        val headerHeight = (56 * dm.density).toInt()
        val spacing = (8 * dm.density).toInt()
        val usableHeight = screenHeight - headerHeight - (spacing * 3)
        val itemHeight = maxOf(usableHeight / 2, (160 * dm.density).toInt())
        binding.root.layoutParams.height = itemHeight

        return CameraViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CameraViewHolder, position: Int) {
        holder.bind(cameras[position], position)
    }

    override fun getItemCount(): Int = cameras.size

    override fun onViewRecycled(holder: CameraViewHolder) {
        super.onViewRecycled(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            holder.binding.playerTile.player = null
            releasePlayer(pos)
        }
    }

    private fun releasePlayer(position: Int) {
        players.remove(position)?.let { player ->
            playerManager?.releasePlayer(player) ?: run {
                try {
                    player.stop()
                    player.clearMediaItems()
                    player.release()
                } catch (_: Exception) {}
            }
        }
    }

    fun releaseAll() {
        players.clear()
        playerManager?.releaseAll()
        playerManager = null
    }
}
