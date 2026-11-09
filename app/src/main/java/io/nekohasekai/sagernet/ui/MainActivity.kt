package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.RemoteException
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.ActivityMainFluxBinding
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(), SagerConnection.Callback {

    private lateinit var binding: ActivityMainFluxBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)

    private val connectLauncher = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainFluxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener {
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

    private fun updateUi(state: BaseService.State) {
        binding.connectButton.text = if (state.canStop) "Disconnect" else "Connect"
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
        return Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.disconnect(this)
    }
}
