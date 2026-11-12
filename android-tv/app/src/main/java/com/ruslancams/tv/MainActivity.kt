package com.ruslancams.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ruslancams.tv.databinding.ActivityMainBinding
import com.ruslancams.tv.model.CameraItem
import com.ruslancams.tv.model.GridConfig
import com.ruslancams.tv.player.PlaybackStatus
import com.ruslancams.tv.player.StreamPlayerController
import com.ruslancams.tv.update.AppUpdateManager
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
    private var mosaicController: StreamPlayerController? = null
    private var fullscreenController: StreamPlayerController? = null
    private var isFullscreen = false

    private val serverHost = "monobuket.tabcast.ru"
    private val authToken = "ruslan2026"
    private val configUrl = "https://$serverHost/grid.json?token=$authToken"
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

    private var currentTvSettings = com.ruslancams.tv.model.TvSettings()

    private val updateCheckHandler = Handler(Looper.getMainLooper())
    private val updateCheckRunnable = object : Runnable {
        override fun run() {
            AppUpdateManager.checkForUpdates(this@MainActivity)
            updateCheckHandler.postDelayed(this, 60 * 60 * 1000L) // check every 1 hour
        }
    }

    private val configPollerHandler = Handler(Looper.getMainLooper())
    private val configPollerRunnable = object : Runnable {
        override fun run() {
            CoroutineScope(Dispatchers.IO).launch {
                val cfg = loadGridConfig()
                if (cfg != null) {
                    withContext(Dispatchers.Main) {
                        if (cfg.tvSettings != currentTvSettings) {
                            Log.i("RuslanTVPlayer", "Обновление настроек ТВ из облака: ${cfg.tvSettings}")
                            val oldBuffer = currentTvSettings.bufferMs
                            applyTvSettings(cfg.tvSettings)
                            if (oldBuffer != cfg.tvSettings.bufferMs) {
                                mosaicController?.customBufferMs = cfg.tvSettings.bufferMs
                                fullscreenController?.customBufferMs = cfg.tvSettings.bufferMs
                                if (!isFullscreen) {
                                    startMosaicPlayer()
                                }
                            }
                        }
                    }
                }
            }
            configPollerHandler.postDelayed(this, 30_000L)
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
        updateCheckHandler.post(updateCheckRunnable)
        configPollerHandler.post(configPollerRunnable)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (isFullscreen) {
            fullscreenController?.resume()
        } else {
            if (mosaicController == null) {
                startMosaicPlayer()
            } else {
                mosaicController?.resume()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        mosaicController?.pause()
        fullscreenController?.pause()
    }

    override fun onStop() {
        super.onStop()
        updateCheckHandler.removeCallbacks(updateCheckRunnable)
        configPollerHandler.removeCallbacks(configPollerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        updateCheckHandler.removeCallbacks(updateCheckRunnable)
        configPollerHandler.removeCallbacks(configPollerRunnable)
        stopMosaicPlayer()
        fullscreenController?.release()
        fullscreenController = null
    }

    private fun startMosaicPlayer() {
        stopMosaicPlayer()
        val controller = StreamPlayerController(
            context = this,
            playerView = binding.playerMosaic,
            tag = "Mosaic",
            isGridTile = false,
            onStatusChanged = { status, message ->
                updateMosaicStatusUI(status, message)
            }
        )
        controller.customBufferMs = currentTvSettings.bufferMs
        mosaicController = controller
        controller.start(mosaicStreamUrl)
    }

    private fun stopMosaicPlayer() {
        mosaicController?.release()
        mosaicController = null
    }

    private fun updateMosaicStatusUI(status: PlaybackStatus, message: String) {
        val (text, textColor, dotDrawable) = when (status) {
            PlaybackStatus.LIVE -> Triple("ЭФИР МОЗАИКИ", R.color.status_green, R.drawable.dot_live)
            PlaybackStatus.BUFFERING -> Triple("БУФЕРИЗАЦИЯ...", R.color.status_yellow, R.drawable.dot_buffering)
            PlaybackStatus.CONNECTING -> Triple("ПОДКЛЮЧЕНИЕ...", R.color.status_yellow, R.drawable.dot_buffering)
            PlaybackStatus.STALLED -> Triple("ВОССТАНОВЛЕНИЕ...", R.color.status_yellow, R.drawable.dot_buffering)
            PlaybackStatus.ERROR -> Triple("НЕТ СИГНАЛА", R.color.status_red, R.drawable.dot_error)
            PlaybackStatus.IDLE -> Triple("ОСТАНОВЛЕНО", R.color.text_secondary, R.drawable.dot_error)
        }
        binding.textPlaybackStatus.text = text
        binding.textPlaybackStatus.setTextColor(ContextCompat.getColor(this, textColor))
        binding.dotStatus.setBackgroundResource(dotDrawable)
    }

    private fun updateFullscreenStatusUI(status: PlaybackStatus, message: String) {
        val (text, dotDrawable) = when (status) {
            PlaybackStatus.LIVE -> Pair("HD MAIN", R.drawable.dot_live)
            PlaybackStatus.BUFFERING -> Pair("БУФЕРИЗАЦИЯ", R.drawable.dot_buffering)
            PlaybackStatus.CONNECTING -> Pair("ПОДКЛЮЧЕНИЕ", R.drawable.dot_buffering)
            PlaybackStatus.STALLED -> Pair("ВОССТАНОВЛЕНИЕ", R.drawable.dot_buffering)
            PlaybackStatus.ERROR -> Pair("ОШИБКА", R.drawable.dot_error)
            PlaybackStatus.IDLE -> Pair("ПАУЗА", R.drawable.dot_error)
        }
        binding.textFullscreenStatus.text = text
        binding.dotFullscreenStatus.setBackgroundResource(dotDrawable)
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

        binding.tile0.postDelayed({
            binding.tile0.requestFocus()
        }, 500)

        CoroutineScope(Dispatchers.IO).launch {
            val cfg = loadGridConfig()
            val serverCams = cfg?.cameras ?: emptyList()
            withContext(Dispatchers.Main) {
                if (cfg != null) {
                    applyTvSettings(cfg.tvSettings)
                }
                if (serverCams.isNotEmpty()) {
                    val map = serverCams.associateBy { it.id }
                    camerasList = defaultCameras.map { def -> map[def.id] ?: def }
                }
            }
        }
    }

    private fun applyTvSettings(settings: com.ruslancams.tv.model.TvSettings) {
        currentTvSettings = settings
        binding.textClock.visibility = if (settings.clockEnabled) View.VISIBLE else View.GONE
        mosaicController?.customBufferMs = settings.bufferMs
        fullscreenController?.customBufferMs = settings.bufferMs
    }

    private fun loadGridConfig(): com.ruslancams.tv.model.GridConfig? {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val urls = listOf(
            configUrl,
            "http://$serverHost:8080/grid.json?token=$authToken",
            "http://66.151.34.27:8080/grid.json?token=$authToken"
        )
        for (u in urls) {
            try {
                val request = Request.Builder().url(u).build()
                val config = client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            com.ruslancams.tv.model.GridConfig.fromJson(body)
                        } else null
                    } else {
                        Log.w("RuslanTVPlayer", "HTTP ${response.code} from $u")
                        null
                    }
                }
                if (config != null) {
                    return config
                }
            } catch (e: Exception) {
                Log.w("RuslanTVPlayer", "Failed to fetch from $u: ${e.message}")
            }
        }
        return null
    }

    private fun openFullscreen(camera: CameraItem) {
        isFullscreen = true
        binding.layoutFullscreen.visibility = View.VISIBLE
        binding.textFullscreenTitle.text = camera.title

        // Pause mosaic stream to conserve TV decoder bandwidth
        mosaicController?.pause()

        fullscreenController?.release()
        val controller = StreamPlayerController(
            context = this,
            playerView = binding.playerFullscreen,
            tag = "Fullscreen",
            isGridTile = false,
            onStatusChanged = { status, message ->
                updateFullscreenStatusUI(status, message)
            }
        )
        fullscreenController = controller
        val streamUrl = camera.getMainStreamUrl(serverHost)
        controller.start(streamUrl)

        binding.layoutFullscreen.isFocusable = true
        binding.layoutFullscreen.requestFocus()
    }

    private fun closeFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        binding.layoutFullscreen.visibility = View.GONE

        fullscreenController?.release()
        fullscreenController = null

        // Resume mosaic
        if (mosaicController == null) {
            startMosaicPlayer()
        } else {
            mosaicController?.resume()
        }
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
}
