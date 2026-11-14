package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ThemedActivity(), SagerConnection.Callback {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    private var connectButton: Button? = null
    private var statusLabel: TextView? = null
    private var searchStatusLabel: TextView? = null
    private var speedLayout: View? = null
    private var txSpeedLabel: TextView? = null
    private var rxSpeedLabel: TextView? = null
    private val appUpdater by lazy { AppUpdater(this) }
    private var availableRelease: AppRelease? = null
    private var updateMenuItem: MenuItem? = null
    
    private val connectMutex = Mutex()
    private var selectedMode = "Франция SSH"
    private val modes = arrayOf("Франция SSH", "Белые Списки")

    private var isNetworkAvailable = true
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isNetworkAvailable = true
            updateUi(DataStore.serviceState)
        }
        override fun onLost(network: Network) {
            isNetworkAvailable = false
            updateUi(DataStore.serviceState)
        }
    }

    private var monitorJob: Job? = null

    private val connectLauncher = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_flux)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Flux"

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        statusLabel = findViewById(R.id.statusLabel)
        searchStatusLabel = findViewById(R.id.searchStatusLabel)
        connectButton = findViewById(R.id.connectButton)
        speedLayout = findViewById(R.id.speedLayout)
        txSpeedLabel = findViewById(R.id.txSpeed)
        rxSpeedLabel = findViewById(R.id.rxSpeed)

        connectButton?.setOnClickListener {
            toggleVpn()
        }

        setupModeSelector()
        connection.connect(this, this)
        lifecycleScope.launch {
            availableRelease = appUpdater.checkLatest()
            availableRelease?.let {
                appUpdater.notifyIfNeeded(it)
                invalidateOptionsMenu()
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    private fun setupModeSelector() {
        val adapter = ArrayAdapter(this, R.layout.simple_menu_dropdown_item, modes)
        val selector = findViewById<AutoCompleteTextView>(R.id.modeSelector)
        selector.setAdapter(adapter)
        selector.setText(selectedMode, false)
        selector.setOnItemClickListener { _, _, position, _ ->
            selectedMode = modes[position]
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        updateMenuItem = menu.findItem(R.id.action_update)
        updateMenuItem?.isVisible = availableRelease != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            R.id.action_update -> {
                val release = availableRelease ?: return true
                startActivity(UpdateActivity.intent(this, release))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            connectMutex.withLock {
                if (DataStore.serviceState.canStop) {
                    SagerNet.stopService()
                } else {
                    if (selectedMode == "Франция SSH") {
                        connectToFranceSSH()
                    } else {
                        connectToWhiteList()
                    }
                }
            }
        }
    }

    private suspend fun connectToFranceSSH() {
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

    private suspend fun getOrCreateWhiteListGroup(): ProxyGroup {
        val allGroups = SagerDatabase.groupDao.allGroups()
        var group = allGroups.find { it.name == "Белые Списки" }
        if (group == null) {
            val sub = SubscriptionBean()
            sub.initializeDefaultValues()
            sub.link = "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt"
            sub.autoUpdate = true
            sub.autoUpdateDelay = 60
            
            group = ProxyGroup(
                name = "Белые Списки",
                type = GroupType.SUBSCRIPTION,
                subscription = sub
            )
            group = GroupManager.createGroup(group)
            GroupUpdater.executeUpdate(group, true)
        }
        return group
    }

    private suspend fun findActiveProxy(groupId: Long): ProxyEntity? = coroutineScope {
        val proxies = SagerDatabase.proxyDao.getByGroup(groupId)
        if (proxies.isEmpty()) return@coroutineScope null
        
        val urlTest = UrlTest()
        val testLimit = 40
        val proxiesToTest = proxies.take(testLimit)
        val channel = Channel<ProxyEntity?>(proxiesToTest.size)

        withContext(Dispatchers.Main) {
            searchStatusLabel?.visibility = View.VISIBLE
            searchStatusLabel?.text = "Testing ${proxiesToTest.size} configs..."
        }

        val jobs = proxiesToTest.map { proxy ->
            launch(Dispatchers.IO) {
                try {
                    val ping = withTimeoutOrNull(2500) { urlTest.doTest(proxy) }
                    if (ping != null && ping > 0) {
                        channel.send(proxy)
                    } else {
                        channel.send(null)
                    }
                } catch (_: Exception) {
                    channel.send(null)
                }
            }
        }

        var found: ProxyEntity? = null
        var completed = 0
        while (completed < proxiesToTest.size) {
            val res = channel.receive()
            completed++
            if (res != null) {
                found = res
                break
            }
            withContext(Dispatchers.Main) {
                searchStatusLabel?.text = "Checked $completed/${proxiesToTest.size}..."
            }
        }

        jobs.forEach { it.cancel() }
        
        withContext(Dispatchers.Main) {
            searchStatusLabel?.visibility = View.GONE
        }
        found
    }

    private suspend fun connectToWhiteList(retryWithUpdate: Boolean = true) {
        withContext(Dispatchers.Main) {
            statusLabel?.text = "WAIT..."
            statusLabel?.setTextColor(getColorAttr(android.R.attr.textColorPrimary))
        }

        val group = getOrCreateWhiteListGroup()
        val activeProxy = findActiveProxy(group.id)

        if (activeProxy != null) {
            DataStore.selectedProxy = activeProxy.id
            DataStore.currentProfile = activeProxy.id
            withContext(Dispatchers.Main) {
                connectLauncher.launch(null)
            }
        } else if (retryWithUpdate) {
            withContext(Dispatchers.Main) {
                searchStatusLabel?.visibility = View.VISIBLE
                searchStatusLabel?.text = "Updating list..."
            }
            try {
                val success = GroupUpdater.executeUpdate(group, true)
                if (success) {
                    connectToWhiteList(false)
                } else {
                    showError("Update failed")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        } else {
            showError("No active configs")
        }
    }

    private fun showError(msg: String) {
        runOnMainDispatcher {
            statusLabel?.text = msg
            statusLabel?.setTextColor(Color.RED)
            searchStatusLabel?.visibility = View.GONE
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60000)
                if (selectedMode == "Белые Списки" && DataStore.serviceState == BaseService.State.Connected) {
                    val currentId = DataStore.currentProfile
                    val proxy = SagerDatabase.proxyDao.getById(currentId) ?: continue
                    try {
                        val ping = withTimeoutOrNull(4000) { UrlTest().doTest(proxy) }
                        if (ping == null || ping <= 0) {
                            withContext(Dispatchers.Main) {
                                searchStatusLabel?.visibility = View.VISIBLE
                                searchStatusLabel?.text = "Profile dead, switching..."
                            }
                            SagerNet.stopService()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun updateUi(state: BaseService.State, msg: String? = null) {
        runOnMainDispatcher {
            if (!isNetworkAvailable && state == BaseService.State.Connected) {
                statusLabel?.text = "NO NETWORK"
                statusLabel?.setTextColor(Color.RED)
            } else if (msg != null && state == BaseService.State.Idle) {
                statusLabel?.text = "ERROR"
                statusLabel?.setTextColor(Color.RED)
                searchStatusLabel?.visibility = View.VISIBLE
                searchStatusLabel?.text = msg
            } else {
                statusLabel?.text = when (state) {
                    BaseService.State.Connected -> "ONLINE"
                    BaseService.State.Connecting -> "WAIT..."
                    BaseService.State.Stopping -> "STOP..."
                    else -> "OFFLINE"
                }
                statusLabel?.setTextColor(getColorAttr(android.R.attr.textColorPrimary))
            }

            connectButton?.text = if (state.canStop) "Disconnect" else "Connect"
            connectButton?.isEnabled = state != BaseService.State.Stopping
            speedLayout?.visibility = if (state == BaseService.State.Connected) View.VISIBLE else View.GONE
            
            if (state == BaseService.State.Connected && selectedMode == "Белые Списки") {
                startMonitor()
            } else if (state == BaseService.State.Idle) {
                monitorJob?.cancel()
                if (msg == null) searchStatusLabel?.visibility = View.GONE
            }
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        DataStore.serviceState = state
        updateUi(state, msg)
        
        if (selectedMode == "Белые Списки" && state == BaseService.State.Idle && msg != null && isNetworkAvailable) {
            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000)
                connectToWhiteList(true)
            }
        }
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
        monitorJob?.cancel()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }
}
