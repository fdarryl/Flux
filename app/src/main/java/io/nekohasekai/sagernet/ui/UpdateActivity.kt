package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.launch
import java.io.File

class UpdateActivity : ThemedActivity() {
    companion object {
        private const val versionKey = "version"
        private const val urlKey = "url"
        private const val fileNameKey = "file-name"

        fun intent(context: Context, release: AppRelease) =
            Intent(context, UpdateActivity::class.java).apply {
                putExtra(versionKey, release.version)
                putExtra(urlKey, release.downloadUrl)
                putExtra(fileNameKey, release.fileName)
            }
    }

    private lateinit var progress: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var status: android.widget.TextView
    private lateinit var installButton: com.google.android.material.button.MaterialButton
    private lateinit var release: AppRelease
    private lateinit var updater: AppUpdater
    private var downloadedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_available)

        progress = findViewById(R.id.updateProgress)
        status = findViewById(R.id.updateStatus)
        installButton = findViewById(R.id.installUpdateButton)
        updater = AppUpdater(this)
        release = AppRelease(
            requireNotNull(intent.getStringExtra(versionKey)),
            requireNotNull(intent.getStringExtra(urlKey)),
            requireNotNull(intent.getStringExtra(fileNameKey))
        )

        val existingFile = File(filesDir, "updates/${release.fileName}")
        if (existingFile.isFile && existingFile.length() > 0) {
            downloadedFile = existingFile
            progress.isIndeterminate = false
            progress.progress = 100
            status.text = getString(R.string.update_ready)
            installButton.isEnabled = true
        }
        installButton.setOnClickListener {
            downloadedFile?.let { updater.install(it) }
        }
        if (downloadedFile == null) download()
    }

    private fun download() {
        installButton.isEnabled = false
        status.text = getString(R.string.update_downloading, release.version)
        lifecycleScope.launch {
            try {
                downloadedFile = updater.download(release) { downloaded, total ->
                    runOnUiThread {
                        if (total > 0) {
                            progress.isIndeterminate = false
                            progress.progress = (downloaded * 100 / total).toInt()
                            status.text = getString(
                                R.string.update_progress,
                                progress.progress
                            )
                        }
                    }
                }
                progress.isIndeterminate = false
                progress.progress = 100
                status.text = getString(R.string.update_ready)
                installButton.isEnabled = true
            } catch (e: Exception) {
                status.text = getString(R.string.update_failed, e.localizedMessage ?: "")
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
