package com.ruslancams.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.exoplayer.ExoPlayer
import com.ruslancams.tv.databinding.ActivityMainBinding
import com.ruslancams.tv.model.CameraItem
import com.ruslancams.tv.model.GridConfig
import com.ruslancams.tv.player.CamPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mosaicPlayer: ExoPlayer? = null
    private var fullscreenPlayer: ExoPlayer? = null
    private var isFullscreen = false

    private val serverHost = "66.151.34.27"
    private val serverHttpPort = 8080
    private val authToken = "ruslan2026"
    private val configUrl = "http://$serverHost:$serverHttpPort/grid.json?token=$authToken"
    private val mosaicStreamUrl = "rtsp://$serverHost:8554/mosaic_all"

    private var camerasList: List<CameraItem> = emptyList()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            binding.textClock.text = sdf.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        clockHandler.post(clockRunnable)

        binding.layoutFullscreen.setOnClickListener {
            closeFullscreen()
        }

        setupMosaicGrid()
    }

    override fun onStart() {
        super.onStart()
        hideSystemUI()
        startMosaicPlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (mosaicPlayer == null && !isFullscreen) {
            startMosaicPlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        stopMosaicPlayer()
        fullscreenPlayer?.release()
        fullscreenPlayer = null
    }

    private fun startMosaicPlayer() {
        if (mosaicPlayer == null) {
            val playerManager = CamPlayerManager(this)
            // Mosaic tile is a single stream decoded in HW
            mosaicPlayer = playerManager.createPlayer(mosaicStreamUrl, isGridTile = false)
            binding.playerMosaic.player = mosaicPlayer
        }
    }

    private fun stopMosaicPlayer() {
        mosaicPlayer?.let {
            it.stop()
            it.release()
        }
        mosaicPlayer = null
        binding.playerMosaic.player = null
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun setupMosaicGrid() {
        binding.layoutFullscreen.visibility = View.GONE

        // Default 8 cameras in 4x2 order:
        // Row 0: Karamova 5, Karamova 7, Kirtbaya 2, Kirtbaya 3
        // Row 1: Lenina 4, Lenina 6, EZVIZ fridge, EZVIZ general
        val tiles = listOf(
            binding.tile0, binding.tile1, binding.tile2, binding.tile3,
            binding.tile4, binding.tile5, binding.tile6, binding.tile7
        )

        val defaultCameras = listOf(
            CameraItem("karamova_5", "Карамова · Камера 5", "src", mapOf("main" to "karamova_5_main")),
            CameraItem("karamova_7", "Карамова · Камера 7", "src", mapOf("main" to "karamova_7_main")),
            CameraItem("kirtbaya_2", "Киртбая · Камера 2", "src", mapOf("main" to "kirtbaya_2_main")),
            CameraItem("kirtbaya_3", "Киртбая · Камера 3", "src", mapOf("main" to "kirtbaya_3_main")),
            CameraItem("lenina_4", "Ленина · Камера 4", "src", mapOf("main" to "lenina_4_main")),
            CameraItem("lenina_6", "Ленина · Камера 6", "src", mapOf("main" to "lenina_6_main")),
            CameraItem("ezviz_fridge_entry", "EZVIZ · Хол-ник вход", "main", mapOf("main" to "ezviz_fridge_entry")),
            CameraItem("ezviz_general", "EZVIZ · Общий вид", "main", mapOf("main" to "ezviz_general"))
        )
        camerasList = defaultCameras

        tiles.forEachIndexed { index, tile ->
            tile.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).translationZ(6f).setDuration(120).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start()
                }
            }

            tile.setOnClickListener {
                if (index < camerasList.size) {
                    openFullscreen(camerasList[index])
                }
            }
        }

        // Focus first tile
        binding.tile0.postDelayed({
            binding.tile0.requestFocus()
        }, 500)

        // Load cameras config from server in background
        CoroutineScope(Dispatchers.IO).launch {
            val serverCams = loadCamerasConfig()
            if (serverCams.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    // Match titles and IDs
                    val map = serverCams.associateBy { it.id }
                    camerasList = defaultCameras.map { def -> map[def.id] ?: def }
                }
            }
        }
    }

    private fun loadCamerasConfig(): List<CameraItem> {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        try {
            val request = Request.Builder().url(configUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        return GridConfig.fromJson(body).cameras
                    }
                }
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    private fun openFullscreen(camera: CameraItem) {
        isFullscreen = true
        binding.layoutFullscreen.visibility = View.VISIBLE
        binding.textFullscreenTitle.text = camera.title

        stopMosaicPlayer()

        fullscreenPlayer?.release()
        val playerManager = CamPlayerManager(this)
        val streamUrl = camera.getMainStreamUrl(serverHost)

        fullscreenPlayer = playerManager.createPlayer(streamUrl, isGridTile = false)
        binding.playerFullscreen.player = fullscreenPlayer
        binding.layoutFullscreen.isFocusable = true
        binding.layoutFullscreen.requestFocus()
    }

    private fun closeFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        binding.layoutFullscreen.visibility = View.GONE
        fullscreenPlayer?.stop()
        fullscreenPlayer?.release()
        fullscreenPlayer = null
        binding.playerFullscreen.player = null

        startMosaicPlayer()
        binding.gridInteractiveOverlay.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isFullscreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    closeFullscreen()
                    return true
                }
            }
        } else {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        stopMosaicPlayer()
        fullscreenPlayer?.release()
    }
}
