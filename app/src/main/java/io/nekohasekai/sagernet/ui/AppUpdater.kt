package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val version: String,
    val downloadUrl: String,
    val fileName: String
)

class AppUpdater(private val context: Context) {
    companion object {
        private const val releaseUrl = "https://api.github.com/repos/fdarryl/Flux/releases/latest"
        private const val preferencesName = "app-updater"
        private const val notifiedVersionKey = "notified-version"
        private const val notificationId = 1002

        private fun currentAbi(): String = Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" || it == "x86"
        } ?: "arm64-v8a"

        /**
         * Extracts all numeric version components from a version string or filename.
         * For example: "v1.4.16" -> [1, 4, 16], "Flux-1.4.16-universal.apk" -> [1, 4, 16].
         */
        fun parseVersion(version: String): List<Int> {
            val match = Regex("""\d+(?:\.\d+)+""").find(version)?.value
            if (match != null) {
                return match.split(".").mapNotNull { it.toIntOrNull() }
            }
            val numbers = Regex("""\d+""").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
            return numbers.ifEmpty { listOf(0) }
        }

        /**
         * Robust semver comparison across all version components.
         * Returns true only if availableVer is strictly greater than currentVer.
         */
        fun isNewer(currentVer: String, availableVer: String): Boolean {
            val current = parseVersion(currentVer)
            val available = parseVersion(availableVer)
            val maxLen = maxOf(current.size, available.size)
            for (i in 0 until maxLen) {
                val c = current.getOrElse(i) { 0 }
                val a = available.getOrElse(i) { 0 }
                if (a > c) return true
                if (a < c) return false
            }
            return false
        }
    }

    /**
     * Cleans up previously downloaded APKs in filesDir/updates that are no longer newer than
     * the currently installed version.
     */
    fun cleanOldUpdates() {
        try {
            val updateDirectory = File(context.filesDir, "updates")
            if (updateDirectory.isDirectory) {
                updateDirectory.listFiles()?.forEach { file ->
                    if (file.isFile && !isNewer(BuildConfig.VERSION_NAME, file.name)) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun checkLatest(): AppRelease? = withContext(Dispatchers.IO) {
        cleanOldUpdates()

        try {
            val connection = URL(releaseUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode !in 200..299) return@withContext null

                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val tagName = release.optString("tag_name").ifBlank { release.optString("name") }
                if (tagName.isBlank() || !isNewer(BuildConfig.VERSION_NAME, tagName)) return@withContext null

                val assets = release.optJSONArray("assets") ?: return@withContext null
                var targetAsset: JSONObject? = null

                // 1. Приоритет: универсальный APK для совместимости со всеми архитектурами
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", true) && name.contains("universal", ignoreCase = true)) {
                        targetAsset = asset
                        break
                    }
                }

                // 2. Фоллбэк: APK под архитектуру устройства, если универсальный не найден
                if (targetAsset == null) {
                    val abi = currentAbi()
                    for (index in 0 until assets.length()) {
                        val asset = assets.getJSONObject(index)
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", true) && name.contains(abi, ignoreCase = true)) {
                            targetAsset = asset
                            break
                        }
                    }
                }

                if (targetAsset != null) {
                    val fileName = targetAsset.optString("name")
                    // Защита от цикла: если имя APK содержит версию, проверяем, что она действительно новее установленной.
                    if (Regex("""\d+(?:\.\d+)+""").containsMatchIn(fileName) && !isNewer(BuildConfig.VERSION_NAME, fileName)) {
                        return@withContext null
                    }

                    return@withContext AppRelease(
                        tagName,
                        targetAsset.optString("browser_download_url"),
                        fileName
                    )
                }
                null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            io.nekohasekai.sagernet.ktx.Logs.w(e)
            null
        }
    }

    fun notifyIfNeeded(release: AppRelease) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (preferences.getString(notifiedVersionKey, null) == release.version ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) return

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            notificationId,
            UpdateActivity.intent(context, release),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
        )
        val notification = NotificationCompat.Builder(context, "app-updates")
            .setSmallIcon(R.drawable.ic_baseline_download_24)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_text, release.version))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        preferences.edit().putString(notifiedVersionKey, release.version).apply()
    }

    suspend fun download(
        release: AppRelease,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updateDirectory = File(context.filesDir, "updates").apply { mkdirs() }
        val file = File(updateDirectory, release.fileName)
        if (file.isFile && file.length() > 0) {
            onProgress(file.length(), file.length())
            return@withContext file
        }
        val temporaryFile = File(updateDirectory, "${release.fileName}.part")
        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (connection.responseCode !in 200..299) error("Download failed: HTTP ${connection.responseCode}")
            val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            var downloaded = 0L
            connection.inputStream.use { input ->
                temporaryFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }
            if (!temporaryFile.renameTo(file)) {
                temporaryFile.delete()
                error("Could not save downloaded APK")
            }
        } finally {
            connection.disconnect()
        }
        file
    }

    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return
        }
        require(file.isFile && file.length() > 0) { "Downloaded APK is missing" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
