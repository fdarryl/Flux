package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.widget.Button
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(), SagerConnection.Callback {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    private var connectButton: Button? = null

    private val connectLauncher = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_flux)

        // Запрос разрешения на уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        connectButton = findViewById(R.id.connectButton)
        connectButton?.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                toggleVpn()
            }
        }

        connection.connect(this, this)
    }

    private fun toggleVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            setupDefaults()

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

    private suspend fun setupDefaults() {
        DataStore.serviceMode = Key.MODE_VPN
        DataStore.proxyApps = false
        DataStore.bypassLan = true
        ProfileManager.getRules()
        
        if (DataStore.remoteDns.isBlank()) {
            DataStore.remoteDns = "https://1.1.1.1/dns-query"
        }
    }

    private fun updateUi(state: BaseService.State) {
        runOnMainDispatcher {
            connectButton?.text = when (state) {
                BaseService.State.Connected -> "Disconnect"
                BaseService.State.Connecting -> "Connecting..."
                BaseService.State.Stopping -> "Stopping..."
                else -> "Connect"
            }
            connectButton?.isEnabled = state != BaseService.State.Stopping
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        DataStore.serviceState = state
        updateUi(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun onServiceConnected(service: ISagerNetService) {
        try {
            val state = BaseService.State.values()[service.state]
            DataStore.serviceState = state
            updateUi(state)
        } catch (e: RemoteException) {
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
