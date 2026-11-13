package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(), SagerConnection.Callback {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    private var connectButton: Button? = null
    private var statusLabel: TextView? = null
    private var speedLayout: View? = null
    private var txSpeedLabel: TextView? = null
    private var rxSpeedLabel: TextView? = null

    private val connectLauncher = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_flux)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Flux"

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        statusLabel = findViewById(R.id.statusLabel)
        connectButton = findViewById(R.id.connectButton)
        speedLayout = findViewById(R.id.speedLayout)
        txSpeedLabel = findViewById(R.id.txSpeed)
        rxSpeedLabel = findViewById(R.id.rxSpeed)

        connectButton?.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                toggleVpn()
            }
        }

        connection.connect(this, this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            // setupDefaults() call removed to honor user settings

            val groupId = DataStore.currentGroupId()
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
            var sshProfile = profiles.find {
                it.type == ProxyEntity.TYPE_SSH &&
                        it.sshBean?.serverAddress == "ssh-bithop.alwaysdata.net" &&
                        it.sshBean?.username == "bithop"
            }

            if (sshProfile == null) {
                val bean = SSHBean().apply {
                    serverAddress = "ssh-bithop.alwaysdata.net"
                    serverPort = 22
                    username = "bithop"
                    password = "1q3q5q7Q!!!"
                    authType = SSHBean.AUTH_TYPE_PASSWORD
                    name = "Flux SSH"
                }
                sshProfile = ProfileManager.createProfile(groupId, bean)
            }

            DataStore.selectedProxy = sshProfile.id
            DataStore.currentProfile = sshProfile.id

            withContext(Dispatchers.Main) {
                connectLauncher.launch(null)
            }
        }
    }

    private fun updateUi(state: BaseService.State, msg: String? = null) {
        runOnMainDispatcher {
            if (msg != null && state != BaseService.State.Connected) {
                statusLabel?.text = "Error: $msg"
                statusLabel?.setTextColor(Color.RED)
            } else {
                statusLabel?.text = when (state) {
                    BaseService.State.Connected -> "Connected"
                    BaseService.State.Connecting -> "Connecting"
                    BaseService.State.Stopping -> "Stopping"
                    else -> "Disconnected"
                }
                statusLabel?.setTextColor(getColorAttr(android.R.attr.textColorPrimary))
            }

            connectButton?.text = if (state.canStop) "Disconnect" else "Connect"
            connectButton?.isEnabled = state != BaseService.State.Stopping
            speedLayout?.visibility = if (state == BaseService.State.Connected) View.VISIBLE else View.GONE
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        DataStore.serviceState = state
        updateUi(state, msg)
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        runOnMainDispatcher {
            txSpeedLabel?.text = "▲ ${Formatter.formatFileSize(this@MainActivity, stats.txRateProxy)}/s"
            rxSpeedLabel?.text = "▼ ${Formatter.formatFileSize(this@MainActivity, stats.rxRateProxy)}/s"
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        try {
            val state = BaseService.State.entries[service.state]
            DataStore.serviceState = state
            updateUi(state)
        } catch (_: RemoteException) {
            updateUi(BaseService.State.Idle)
        }
    }

    override fun onServiceDisconnected() {
        updateUi(BaseService.State.Idle)
    }

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.disconnect(this)
    }
}
