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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.bg.TelegramProxyService
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

    private fun updateTelegramProxyUi(enabled: Boolean) {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.setupTelegramButton)?.visibility =
            if (enabled) View.VISIBLE else View.GONE
    }

    private fun openTelegramProxySettings() {
        val options = arrayOf(
            "MTProto (dd00... Рекомендуется)",
            "MTProto (0000...dd)",
            "MTProto (0000... Без dd)",
            "SOCKS5 (127.0.0.1:1080)"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Telegram Proxy")
            .setItems(options) { _, which ->
                val uri = when (which) {
                    0 -> "tg://proxy?server=${TelegramProxyService.BIND_HOST}&port=${TelegramProxyService.BIND_PORT_MTPROTO}&secret=${TelegramProxyService.DEFAULT_SECRET_HEX}".toUri()
                    1 -> "tg://proxy?server=${TelegramProxyService.BIND_HOST}&port=${TelegramProxyService.BIND_PORT_MTPROTO}&secret=000000000000000000000000000000dd".toUri()
                    2 -> "tg://proxy?server=${TelegramProxyService.BIND_HOST}&port=${TelegramProxyService.BIND_PORT_MTPROTO}&secret=00000000000000000000000000000000".toUri()
                    else -> "tg://socks?server=${TelegramProxyService.BIND_HOST}&port=${TelegramProxyService.BIND_PORT_SOCKS5}".toUri()
                }
                launchTelegramUri(uri)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchTelegramUri(proxyUri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, proxyUri))
        } catch (_: Exception) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Telegram Proxy", proxyUri.toString()))
            snackbar("Telegram proxy link copied to clipboard").show()
        }
    }

    private fun startTelegramProxy() {
        updateTelegramProxyUi(true)
        runOnMainDispatcher {
            statusLabel?.text = "TELEGRAM PROXY"
            statusLabel?.setTextColor(getColorAttr(android.R.attr.textColorPrimary))
        }
    }

    private fun stopTelegramProxy() {
        updateTelegramProxyUi(false)
        runOnMainDispatcher {
            statusLabel?.text = "OFFLINE"
            statusLabel?.setTextColor(getColorAttr(android.R.attr.textColorPrimary))
        }
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
        statusLabel?.setOnClickListener {
            if (DataStore.sshAccessKey.trim().isEmpty() && selectedMode == "Франция SSH") {
                showSshKeyDialog {
                    lifecycleScope.launch(Dispatchers.IO) {
                        connectToFranceSSH()
                    }
                }
            }
        }
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
            try {
                availableRelease = appUpdater.checkLatest()
                availableRelease?.let {
                    appUpdater.notifyIfNeeded(it)
                    invalidateOptionsMenu()
                }
            } catch (e: Exception) {
                io.nekohasekai.sagernet.ktx.Logs.w(e)
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        findViewById<com.google.android.material.button.MaterialButton>(R.id.setupTelegramButton)?.setOnClickListener {
            openTelegramProxySettings()
        }

        DataStore.ensureDefaultSshConfig()

        findViewById<SwitchMaterial>(R.id.telegramProxyToggle).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startTelegramProxy()
                try {
                    val serviceIntent = Intent(this, TelegramProxyService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(this, serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    showError("Cannot start Telegram Proxy: ${e.message}")
                    findViewById<SwitchMaterial>(R.id.telegramProxyToggle).isChecked = false
                }
            } else {
                stopTelegramProxy()
                try {
                    stopService(Intent(this, TelegramProxyService::class.java))
                } catch (e: Exception) {
                    io.nekohasekai.sagernet.ktx.Logs.w(e)
                }
            }
        }
    }

    private fun showSshKeyDialog(onSuccess: (() -> Unit)? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ssh_key, null, false)
        val input = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.keyInput)
        input.setText(DataStore.sshAccessKey)
        input.setSelection(input.text?.length ?: 0)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssh_access_key)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newKey = input.text?.toString()?.trim() ?: ""
                DataStore.sshAccessKey = newKey
                if (newKey.isNotEmpty()) {
                    statusLabel?.text = ""
                    onSuccess?.invoke()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun getSshBeanForCurrentKey(): SSHBean? {
        val key = DataStore.sshAccessKey.trim()
        if (key.isEmpty()) {
            withContext(Dispatchers.Main) {
                showSshKeyDialog {
                    lifecycleScope.launch(Dispatchers.IO) {
                        connectToFranceSSH()
                    }
                }
            }
            return null
        }

        val rawConfig = DataStore.getSshConfigForCurrentKey()
            ?: run {
                showError("SSH config for key '$key' not found")
                return null
            }
        return DataStore.parseSshConfig(rawConfig) ?: run {
            showError("SSH config for key '$key' is invalid")
            null
        }
    }

    private suspend fun connectToFranceSSH() {
        val sshBean = getSshBeanForCurrentKey() ?: return
        val groupId = DataStore.currentGroupId()
        val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
        val sshProfile = profiles.find {
            it.type == ProxyEntity.TYPE_SSH &&
                it.sshBean?.serverAddress == sshBean.serverAddress &&
                it.sshBean?.username == sshBean.username
        } ?: ProfileManager.createProfile(groupId, sshBean)

        DataStore.selectedProxy = sshProfile.id
        DataStore.currentProfile = sshProfile.id

        withContext(Dispatchers.Main) {
            connectLauncher.launch(null)
        }
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

    private fun checkHasInternetConnection(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo != null && networkInfo.isConnected
            }
        } catch (_: Exception) {
            isNetworkAvailable
        }
    }

    private suspend fun getOrCreateWhiteListGroup(): ProxyGroup {
        val targetLink = "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt"
        val allGroups = SagerDatabase.groupDao.allGroups()
        var group = allGroups.find { it.name == "Белые Списки" }
        if (group == null) {
            val sub = SubscriptionBean()
            sub.initializeDefaultValues()
            sub.link = targetLink
            sub.autoUpdate = true
            sub.autoUpdateDelay = 60
            
            group = ProxyGroup(
                name = "Белые Списки",
                type = GroupType.SUBSCRIPTION,
                subscription = sub
            )
            group = GroupManager.createGroup(group)
        } else {
            val sub = group.subscription ?: SubscriptionBean().apply { initializeDefaultValues() }
            var changed = false
            if (sub.link != targetLink) {
                sub.link = targetLink
                changed = true
            }
            if (sub.autoUpdate != true) {
                sub.autoUpdate = true
                changed = true
            }
            if (sub.autoUpdateDelay != 60) {
                sub.autoUpdateDelay = 60
                changed = true
            }
            if (changed) {
                group.subscription = sub
                GroupManager.updateGroup(group)
            }
        }
        SubscriptionUpdater.reconfigureUpdater()
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

    private suspend fun connectToWhiteList() {
        withContext(Dispatchers.Main) {
            statusLabel?.text = "WAIT..."
            statusLabel?.setTextColor(getColorAttr(android.R.attr.textColorPrimary))
        }

        val group = getOrCreateWhiteListGroup()
        val hasInternet = checkHasInternetConnection()

        if (hasInternet) {
            withContext(Dispatchers.Main) {
                searchStatusLabel?.visibility = View.VISIBLE
                searchStatusLabel?.text = "Updating list..."
            }
            try {
                GroupUpdater.executeUpdate(group, true)
            } catch (e: Exception) {
                io.nekohasekai.sagernet.ktx.Logs.w(e)
            }
        }

        val activeProxy = findActiveProxy(group.id)

        if (activeProxy != null) {
            DataStore.selectedProxy = activeProxy.id
            DataStore.currentProfile = activeProxy.id
            withContext(Dispatchers.Main) {
                connectLauncher.launch(null)
            }
        } else {
            if (!hasInternet && SagerDatabase.proxyDao.getByGroup(group.id).isEmpty()) {
                showError("No network connection")
            } else {
                showError("No active configs")
            }
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
                connectToWhiteList()
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
        } catch (_: Exception) {
        }
    }
}



