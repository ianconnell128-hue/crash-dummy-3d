package com.example.crashdummy3d

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class GameInterface(private val context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val currentVersion = "3.1"
    private val repoOwner = "ianconnell128-hue"
    private val repoName = "crash-dummy-3d"

    @JavascriptInterface
    fun vibrate(type: String) {
        if (!vibrator.hasVibrator()) return

        val duration = when (type) {
            "light" -> 15L
            "medium" -> 35L
            "heavy" -> 70L
            "explosion" -> 140L
            else -> 25L
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = when (type) {
                "light" -> 60
                "medium" -> 140
                "heavy" -> 220
                "explosion" -> 255
                else -> 100
            }
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    @JavascriptInterface
    fun getCurrentVersion(): String {
        return currentVersion
    }

    @JavascriptInterface
    fun checkForUpdates() {
        (context as? Activity)?.runOnUiThread {
            Toast.makeText(context, "Checking GitHub for updates...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
                val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "CrashDummy3D-App")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val tagName = json.optString("tag_name", "").replace("v", "")
                    val assets = json.optJSONArray("assets")

                    var downloadUrl: String? = null
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (downloadUrl != null && isNewerVersion(tagName, currentVersion)) {
                            notifyWebview("updateAvailable", tagName, downloadUrl)
                        } else {
                            notifyWebview("noUpdate", tagName, "")
                            Toast.makeText(context, "You are on the latest version (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (connection.responseCode == 404) {
                    withContext(Dispatchers.Main) {
                        notifyWebview("noUpdate", currentVersion, "")
                        Toast.makeText(context, "No GitHub releases found yet for this repo.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "GitHub API returned code: ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Update check failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @JavascriptInterface
    fun downloadAndInstallUpdate(downloadUrl: String) {
        (context as? Activity)?.runOnUiThread {
            Toast.makeText(context, "Downloading update APK...", Toast.LENGTH_SHORT).show()
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val apkFile = File(context.cacheDir, "update.apk")
                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download complete, launching installer...", Toast.LENGTH_SHORT).show()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to download update: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isEmpty()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun installApk(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun notifyWebview(event: String, version: String, downloadUrl: String) {
        (context as? MainActivity)?.runOnUiThread {
            (context as? MainActivity)?.evaluateJavascript(
                "if (window.onUpdateResult) { window.onUpdateResult('$event', '$version', '$downloadUrl'); }"
            )
        }
    }
}
