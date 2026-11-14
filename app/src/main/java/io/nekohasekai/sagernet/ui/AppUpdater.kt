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

        private fun normalizedVersion(version: String): List<Int> =
            Regex("""\d+(?:\.\d+)+""").find(version)?.value.orEmpty()
                .split(".").map { it.toIntOrNull() ?: 0 }

        private fun isNewer(latest: String): Boolean {
            val current = normalizedVersion(BuildConfig.VERSION_NAME)
            val available = normalizedVersion(latest)
            return (0..2).firstNotNullOfOrNull { index ->
                when {
                    available.getOrElse(index) { 0 } > current.getOrElse(index) { 0 } -> true
                    available.getOrElse(index) { 0 } < current.getOrElse(index) { 0 } -> false
                    else -> null
                }
            } ?: false
        }
    }

    suspend fun checkLatest(): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(releaseUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode !in 200..299) return@withContext null

            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.optString("tag_name")
            if (version.isBlank() || !isNewer(version)) return@withContext null

            val abi = currentAbi()
            val assets = release.optJSONArray("assets") ?: return@withContext null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                if (name.endsWith(".apk", true) && name.contains(abi)) {
                    return@withContext AppRelease(
                        version,
                        asset.optString("browser_download_url"),
                        name
                    )
                }
            }
            null
        } finally {
            connection.disconnect()
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
            val total = connection.contentLengthLong
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
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
