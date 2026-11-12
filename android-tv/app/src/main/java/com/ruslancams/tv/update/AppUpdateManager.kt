package com.ruslancams.tv.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ruslancams.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val UPDATE_URL = "https://monobuket.tabcast.ru/version.json"

    private val isUpdating = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(activity: Activity) {
        if (isUpdating.get()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(UPDATE_URL)
                    .header("Cache-Control", "no-cache")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Update check failed: HTTP ${response.code}")
                        return@launch
                    }

                    val bodyString = response.body?.string() ?: return@launch
                    val json = JSONObject(bodyString)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val changelog = json.optString("changelog", "")

                    val currentCode = BuildConfig.VERSION_CODE
                    Log.i(TAG, "Current version: $currentCode, Remote version: $remoteVersionCode ($remoteVersionName)")

                    if (remoteVersionCode > currentCode && apkUrl.isNotEmpty()) {
                        isUpdating.set(true)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                activity,
                                "Обнаружено обновление $remoteVersionName! Загрузка...",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        downloadAndInstall(activity, apkUrl, remoteVersionName)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking update: ${e.message}")
            } finally {
                // If not currently downloading, reset flag
            }
        }
    }

    private suspend fun downloadAndInstall(activity: Activity, apkUrl: String, versionName: String) {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(apkUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed: HTTP ${response.code}")
                        isUpdating.set(false)
                        return@use
                    }

                    val body = response.body ?: run {
                        isUpdating.set(false)
                        return@use
                    }

                    val cacheDir = activity.externalCacheDir ?: activity.cacheDir
                    val apkFile = File(cacheDir, "ruslan-cams-update.apk")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(apkFile)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    Log.i(TAG, "Downloaded update to ${apkFile.absolutePath} (${apkFile.length()} bytes)")

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            "Обновление $versionName загружено. Запуск установки...",
                            Toast.LENGTH_LONG
                        ).show()

                        installApk(activity, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Ошибка загрузки обновления", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isUpdating.set(false)
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        context,
                        "Разрешите установку из этого источника в настройках",
                        Toast.LENGTH_LONG
                    ).show()
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(manageIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Install intent error: ${e.message}")
            Toast.makeText(context, "Ошибка запуска установщика: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
