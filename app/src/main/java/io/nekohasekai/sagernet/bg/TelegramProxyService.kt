package io.nekohasekai.sagernet.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class TelegramProxyService : Service() {
    companion object {
        const val CHANNEL_ID = "telegram_proxy_channel"
        const val CHANNEL_NAME = "Telegram Proxy"
        const val BIND_HOST = "127.0.0.1"
        const val BIND_PORT = 1443
        const val DEFAULT_SECRET_HEX = "dd00000000000000000000000000000000"
        private const val WS_PATH = "/apiws"
        private const val CF_DOMAINS_URL = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

        private val DC_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.50",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "91.108.56.100"
        )

        private val DEFAULT_CF_DOMAINS = listOf(
            "offshor.co.uk",
            "pclead.co.uk",
            "lovetrue.co.uk",
            "sorokdva.co.uk",
            "stopblocking.co.uk",
            "nothingthere.co.uk",
            "onedaychamp.co.uk",
            "pomogite.co.uk",
            "fixtelega.co.uk",
            "notelega.co.uk",
            "ebally.co.uk",
            "nebally.co.uk",
            "havegreatday.co.uk",
            "sadnews.co.uk",
            "pyatdesyatodin.co.uk",
            "kartoshka.co.uk",
            "cakeisalie.co.uk",
            "sorokodin.co.uk",
            "noskomnadzor.co.uk",
            "pyatdesyatdva.co.uk"
        )

        private val KNOWN_SECRETS = listOf(
            ByteArray(16), // 16 zeros: matches dd00000000000000000000000000000000 or 00000000000000000000000000000000
            null,          // plain MTProto without secret
            hexToBytes("0123456789abcdef0123456789abcdef")
        )

        fun decodeCfDomain(s: String): String {
            val clean = s.trim()
            if (!clean.endsWith(".com", ignoreCase = true)) return clean
            val p = clean.substring(0, clean.length - 4)
            val n = p.count { it.isLetter() }
            val sb = StringBuilder()
            for (c in p) {
                if (c.isLetter()) {
                    val base = if (c.isLowerCase()) 'a'.code else 'A'.code
                    var shifted = (c.code - base - n) % 26
                    if (shifted < 0) shifted += 26
                    sb.append((base + shifted).toChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.append(".co.uk").toString()
        }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().let { if (it.startsWith("dd", true) && it.length == 34) it.substring(2) else it }
            val len = clean.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) +
                        Character.digit(clean[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        private fun isKnownProtoTag(tag: ByteArray): Boolean {
            if (tag.size < 4) return false
            val v = ((tag[0].toInt() and 0xFF) shl 24) or
                    ((tag[1].toInt() and 0xFF) shl 16) or
                    ((tag[2].toInt() and 0xFF) shl 8) or
                    (tag[3].toInt() and 0xFF)
            return v == 0xefefefef.toInt() || // Abridged
                    v == 0xeeeeeeee.toInt() || // Intermediate
                    v == 0xdddddddd.toInt()    // Padded Intermediate
        }
    }

    private data class ClientHandshakeResult(
        val dcId: Int,
        val dcIdx: Int,
        val protoTag: ByteArray,
        val cltDec: Cipher,
        val cltEnc: Cipher
    )

    private data class RelayInitResult(
        val relayInitPacket: ByteArray,
        val tgEnc: Cipher,
        val tgDec: Cipher
    )

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null
    private val workerPool = Executors.newCachedThreadPool()
    private val endpointCooldownUntil = ConcurrentHashMap<String, Long>()
    private val activeCfDomain = ConcurrentHashMap<Int, String>()
    private val cfDomainsList = CopyOnWriteArrayList<String>(DEFAULT_CF_DOMAINS)

    private val wsClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        refreshCfDomainsAsync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server == null || server?.isClosed == true) {
            startServer()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, buildNotification())
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        Executors.newSingleThreadExecutor().execute {
            try { wsClient.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
            try { wsClient.connectionPool.evictAll() } catch (_: Exception) {}
        }
        workerPool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram Proxy Active")
            .setContentText("Listening on 127.0.0.1:1443")
            .setSmallIcon(R.drawable.ic_service_active)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refreshCfDomainsAsync() {
        workerPool.execute {
            try {
                val req = Request.Builder()
                    .url(CF_DOMAINS_URL)
                    .header("User-Agent", "tg-ws-proxy")
                    .build()
                wsClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val decoded = body.lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .map { decodeCfDomain(it) }
                            .filter { it.contains(".") && it.length in 4..253 }
                            .toList()
                        if (decoded.size >= 3) {
                            val merged = (DEFAULT_CF_DOMAINS + decoded).distinct()
                            cfDomainsList.clear()
                            cfDomainsList.addAll(merged)
                            android.util.Log.i("TG_PROXY", "Refreshed ${merged.size} CF proxy domains from GitHub (kept fast ones first)")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("TG_PROXY", "Could not fetch dynamic CF domains: ${e.message}")
            }
        }
    }

    private fun startServer() {
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(BIND_HOST, BIND_PORT))
            server = s

            serverThread = Thread {
                while (!Thread.currentThread().isInterrupted && !s.isClosed) {
                    try {
                        val client = s.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 0
                        workerPool.execute { handleClient(client) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            serverThread?.isDaemon = true
            serverThread?.start()
            Logs.d("Telegram Proxy started on $BIND_HOST:$BIND_PORT")
        } catch (e: Exception) {
            Logs.e("Failed to start Telegram Proxy: ${e.readableMessage}")
        }
    }

    private fun stopServer() {
        try { serverThread?.interrupt() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
        serverThread = null
    }

    private fun handleClient(client: Socket) {
        try {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            val first = clientIn.read()
            if (first < 0) {
                android.util.Log.w("TG_PROXY", "Client closed connection immediately")
                client.close()
                return
            }
            android.util.Log.i("TG_PROXY", "Client connected from ${client.remoteSocketAddress}, first byte: 0x${first.toString(16)}")

            var forcedDc: Int? = null

            // 1. Check for SOCKS5 handshake
            if (first == 0x05) {
                android.util.Log.i("TG_PROXY", "Handling SOCKS5 handshake...")
                forcedDc = handleSocks5Handshake(clientIn, clientOut)
                if (forcedDc == null) {
                    android.util.Log.w("TG_PROXY", "SOCKS5 handshake failed")
                    client.close()
                    return
                }
                android.util.Log.i("TG_PROXY", "SOCKS5 handshake OK, target DC: $forcedDc")
            }

            // 2. Read client 64-byte MTProto Obfuscated2 header
            val handshakeBytes = ByteArray(64)
            if (first == 0x05) {
                if (!readExact(clientIn, handshakeBytes, 0, 64)) {
                    android.util.Log.w("TG_PROXY", "Failed to read 64 bytes MTProto header after SOCKS5")
                    client.close()
                    return
                }
            } else {
                handshakeBytes[0] = first.toByte()
                if (!readExact(clientIn, handshakeBytes, 1, 63)) {
                    android.util.Log.w("TG_PROXY", "Failed to read remaining 63 bytes MTProto header")
                    client.close()
                    return
                }
            }

            val hexPreview = handshakeBytes.take(16).joinToString("") { "%02x".format(it) }
            android.util.Log.i("TG_PROXY", "Read 64 bytes header, preview: $hexPreview...")

            // 3. Decrypt client handshake using known secrets
            val handshake = tryDecryptClientHandshake(handshakeBytes)
            if (handshake == null) {
                android.util.Log.e("TG_PROXY", "Failed to decrypt MTProto handshake! Header: ${handshakeBytes.joinToString("") { "%02x".format(it) }}")
                client.close()
                return
            }

            val targetDc = forcedDc ?: handshake.dcId
            android.util.Log.i("TG_PROXY", "Handshake decrypted! DC: $targetDc, Proto: ${handshake.protoTag.joinToString("") { "%02x".format(it) }}")

            // Remove timeout for active streaming session
            try { client.soTimeout = 0 } catch (_: Exception) {}

            // 4. Try fast racing WebSocket connection to CF-proxied endpoints
            val session = connectFastWs(clientOut, handshake.copy(dcId = targetDc), targetDc)
            if (session != null) {
                val splitter = MsgSplitter(handshake.protoTag)
                val upstream = Thread {
                    try {
                        val buf = ByteArray(64 * 1024)
                        while (session.running.get()) {
                            val n = clientIn.read(buf)
                            if (n <= 0) break
                            val chunk = buf.copyOf(n)
                            val plain = handshake.cltDec.update(chunk)
                            val tgCipher = session.tgEnc.update(plain)
                            val parts = splitter.split(tgCipher, plain)
                            for (part in parts) {
                                if (!session.ws.send(part.toByteString())) {
                                    session.running.set(false)
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {} finally {
                        session.running.set(false)
                        val tail = splitter.flush()
                        if (tail.isNotEmpty()) {
                            try { session.ws.send(tail[0].toByteString()) } catch (_: Exception) {}
                        }
                        try { session.ws.close(1000, null) } catch (_: Exception) {}
                        session.finished.countDown()
                        try { client.close() } catch (_: Exception) {}
                    }
                }
                upstream.isDaemon = true
                upstream.start()

                session.finished.await()
                try { client.close() } catch (_: Exception) {}
                return
            }

            // 5. Fallback to direct TCP with re-encryption if WebSocket is unreachable
            val dcIp = DC_IPS[targetDc] ?: DC_IPS[2]!!
            Logs.d("TelegramProxy: WS endpoints failed, trying direct TCP to DC$targetDc ($dcIp:443)")
            bridgeTcp(client, clientIn, clientOut, handshake.copy(dcId = targetDc), dcIp)

        } catch (e: Exception) {
            Logs.e("TelegramProxy client error: ${e.readableMessage}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleSocks5Handshake(clientIn: InputStream, clientOut: OutputStream): Int? {
        val nMethods = clientIn.read()
        if (nMethods <= 0) return null
        val methods = ByteArray(nMethods)
        if (!readExact(clientIn, methods, 0, nMethods)) return null

        // Reply 0x05 0x00 (No Authentication Required)
        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        val reqHead = ByteArray(4)
        if (!readExact(clientIn, reqHead, 0, reqHead.size)) return null
        val ver = reqHead[0].toInt() and 0xFF
        val cmd = reqHead[1].toInt() and 0xFF
        val atyp = reqHead[3].toInt() and 0xFF

        if (ver != 0x05 || cmd != 0x01) {
            clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
            return null
        }

        val host = when (atyp) {
            0x01 -> {
                val addr = ByteArray(4)
                if (!readExact(clientIn, addr, 0, 4)) return null
                "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
            }
            0x03 -> {
                val len = clientIn.read()
                if (len <= 0) return null
                val domain = ByteArray(len)
                if (!readExact(clientIn, domain, 0, len)) return null
                String(domain)
            }
            else -> return null
        }

        val portBytes = ByteArray(2)
        if (!readExact(clientIn, portBytes, 0, 2)) return null

        // Reply 0x05 0x00 (Success)
        clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        clientOut.flush()

        return getDcId(host)
    }

    private fun getDcId(host: String): Int {
        return when {
            host == "149.154.175.50" -> 1
            host == "149.154.167.50" || host == "149.154.167.51" -> 2
            host == "149.154.175.100" -> 3
            host == "149.154.167.91" -> 4
            host == "91.108.56.100" || host == "91.108.56.130" -> 5
            host.startsWith("149.154.") -> 2
            host.startsWith("91.108.") -> 5
            else -> 2
        }
    }

    private fun domainsForDc(dc: Int): List<String> {
        val result = mutableListOf<String>()
        val active = activeCfDomain[dc]
        if (active != null) {
            val d = "kws$dc.$active"
            if (!isEndpointCoolingDown(d)) result.add(d)
        }
        for (base in cfDomainsList) {
            val d = "kws$dc.$base"
            if (!result.contains(d) && !isEndpointCoolingDown(d)) {
                result.add(d)
            }
        }
        val direct = when (dc) {
            1 -> listOf("kws1.web.telegram.org", "kws1-1.web.telegram.org")
            2 -> listOf("kws2.web.telegram.org", "kws2-1.web.telegram.org")
            3 -> listOf("kws3.web.telegram.org", "kws3-1.web.telegram.org")
            4 -> listOf("kws4.web.telegram.org", "kws4-1.web.telegram.org")
            5 -> listOf("kws5.web.telegram.org", "kws5-1.web.telegram.org")
            else -> listOf("kws2.web.telegram.org")
        }
        for (d in direct) {
            if (!result.contains(d) && !isEndpointCoolingDown(d)) {
                result.add(d)
            }
        }
        return result
    }

    private fun extractBaseDomain(domain: String): String? {
        val parts = domain.split(".", limit = 2)
        return if (parts.size == 2 && parts[0].startsWith("kws")) parts[1] else null
    }

    private fun tryDecryptClientHandshake(handshake: ByteArray): ClientHandshakeResult? {
        if (handshake.size < 64) return null
        val decPrekey = handshake.copyOfRange(8, 40)
        val decIv = handshake.copyOfRange(40, 56)

        for (secret in KNOWN_SECRETS) {
            try {
                val decKey = if (secret != null) {
                    MessageDigest.getInstance("SHA-256").digest(decPrekey + secret)
                } else {
                    decPrekey
                }

                val decryptor = Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(decIv))
                }
                val decrypted = decryptor.update(handshake)

                val protoTag = decrypted.copyOfRange(56, 60)
                if (isKnownProtoTag(protoTag)) {
                    val dcIdx = ByteBuffer.wrap(decrypted, 60, 2).order(ByteOrder.LITTLE_ENDIAN).short
                    val rawDc = abs(dcIdx.toInt())
                    val dcId = if (rawDc in 1..5) rawDc else 2

                    val reversed48 = handshake.copyOfRange(8, 56).reversedArray()
                    val encPrekey = reversed48.copyOfRange(0, 32)
                    val encIv = reversed48.copyOfRange(32, 48)
                    val encKey = if (secret != null) {
                        MessageDigest.getInstance("SHA-256").digest(encPrekey + secret)
                    } else {
                        encPrekey
                    }
                    val cltEnc = Cipher.getInstance("AES/CTR/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))
                    }
                    return ClientHandshakeResult(dcId, dcIdx.toInt(), protoTag, decryptor, cltEnc)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): RelayInitResult {
        val rnd = ByteArray(64)
        val secureRandom = SecureRandom()
        while (true) {
            secureRandom.nextBytes(rnd)
            val b0 = rnd[0].toInt() and 0xFF
            if (b0 == 0xef || b0 == 0x44 || b0 == 0x50 || b0 == 0x47 || b0 == 0x48 || b0 == 0x16) continue
            val b03 = ((rnd[0].toInt() and 0xFF) shl 24) or ((rnd[1].toInt() and 0xFF) shl 16) or ((rnd[2].toInt() and 0xFF) shl 8) or (rnd[3].toInt() and 0xFF)
            if (b03 == 0xefefefef.toInt() || b03 == 0xeeeeeeee.toInt() || b03 == 0xdddddddd.toInt()) continue
            val b47 = ((rnd[4].toInt() and 0xFF) shl 24) or ((rnd[5].toInt() and 0xFF) shl 16) or ((rnd[6].toInt() and 0xFF) shl 8) or (rnd[7].toInt() and 0xFF)
            if (b47 == 0) continue
            break
        }

        val encKey = rnd.copyOfRange(8, 40)
        val encIv = rnd.copyOfRange(40, 56)

        val reversed48 = rnd.copyOfRange(8, 56).reversedArray()
        val decKey = reversed48.copyOfRange(0, 32)
        val decIv = reversed48.copyOfRange(32, 48)

        val tgEnc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv))
        }
        val tgDec = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(decIv))
        }

        val tailPlain = ByteArray(8)
        System.arraycopy(protoTag, 0, tailPlain, 0, 4)
        val dcShort = dcIdx.toShort()
        tailPlain[4] = (dcShort.toInt() and 0xFF).toByte()
        tailPlain[5] = ((dcShort.toInt() shr 8) and 0xFF).toByte()
        tailPlain[6] = rnd[62]
        tailPlain[7] = rnd[63]

        tgEnc.update(ByteArray(56)) // advance CTR stream by 56 bytes
        val encryptedTail = tgEnc.update(tailPlain)
        System.arraycopy(encryptedTail, 0, rnd, 56, 8)

        return RelayInitResult(rnd, tgEnc, tgDec)
    }

    private class WsSession(
        val ws: WebSocket,
        val domain: String,
        val tgEnc: Cipher,
        val tgDec: Cipher,
        val finished: CountDownLatch,
        val running: AtomicBoolean
    )

    private fun connectFastWs(
        clientOut: OutputStream,
        handshake: ClientHandshakeResult,
        targetDc: Int
    ): WsSession? {
        val candidates = domainsForDc(targetDc)
            .filter { !isEndpointCoolingDown(it) }
            .take(4)
            .ifEmpty { domainsForDc(targetDc).take(2) }

        if (candidates.isEmpty()) return null

        val winnerLatch = CountDownLatch(1)
        val winningSession = java.util.concurrent.atomic.AtomicReference<WsSession?>(null)
        val pendingWebSockets = CopyOnWriteArrayList<WebSocket>()

        for (domain in candidates) {
            if (winningSession.get() != null) break

            val (relayInit, tgEnc, tgDec) = generateRelayInit(handshake.protoTag, handshake.dcIdx)
            val finished = CountDownLatch(1)
            val running = AtomicBoolean(true)

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val session = WsSession(webSocket, domain, tgEnc, tgDec, finished, running)
                    if (winningSession.compareAndSet(null, session)) {
                        android.util.Log.i("TG_PROXY", "WS race WON by $domain (HTTP ${response.code})")
                        webSocket.send(relayInit.toByteString())
                        clearEndpointCooldown(domain)
                        val base = extractBaseDomain(domain)
                        if (base != null && !base.endsWith("telegram.org")) {
                            activeCfDomain[targetDc] = base
                        }
                        winnerLatch.countDown()
                    } else {
                        webSocket.cancel()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!running.get()) return
                    try {
                        val plain = tgDec.update(bytes.toByteArray())
                        val cltCipher = handshake.cltEnc.update(plain)
                        synchronized(clientOut) {
                            clientOut.write(cltCipher)
                            clientOut.flush()
                        }
                    } catch (e: Exception) {
                        running.set(false)
                        webSocket.cancel()
                        finished.countDown()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(webSocket, text.encodeUtf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    running.set(false)
                    finished.countDown()
                    webSocket.close(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    android.util.Log.w("TG_PROXY", "WS to $domain failed: ${t.message}")
                    markEndpointFailure(domain)
                    if (activeCfDomain[targetDc]?.let { domain.contains(it) } == true) {
                        activeCfDomain.remove(targetDc)
                    }
                    val session = winningSession.get()
                    if (session != null && session.domain == domain) {
                        running.set(false)
                        finished.countDown()
                    }
                }
            }

            val request = Request.Builder()
                .url("wss://$domain$WS_PATH")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Sec-WebSocket-Protocol", "binary")
                .build()

            val ws = wsClient.newWebSocket(request, listener)
            pendingWebSockets.add(ws)

            // Stagger candidates by 500ms; if one connects, break early
            if (winnerLatch.await(500, TimeUnit.MILLISECONDS)) {
                break
            }
        }

        // Wait up to 2500ms for any candidate to complete handshake
        if (winningSession.get() == null) {
            winnerLatch.await(2500, TimeUnit.MILLISECONDS)
        }

        val session = winningSession.get()
        // Cancel all unselected connections
        for (ws in pendingWebSockets) {
            if (session == null || ws != session.ws) {
                try { ws.cancel() } catch (_: Exception) {}
            }
        }
        return session
    }

    private fun bridgeTcp(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        handshake: ClientHandshakeResult,
        dcIp: String
    ): Boolean {
        return try {
            android.util.Log.i("TG_PROXY", "Attempting direct TCP connection to $dcIp:443 ...")
            val remote = Socket()
            remote.tcpNoDelay = true
            remote.soTimeout = 20000
            remote.connect(InetSocketAddress(dcIp, 443), 8000)
            android.util.Log.i("TG_PROXY", "Direct TCP connection to $dcIp:443 ESTABLISHED!")

            val (relayInit, tgEnc, tgDec) = generateRelayInit(handshake.protoTag, handshake.dcId)
            val remoteOut = remote.getOutputStream()
            remoteOut.write(relayInit)
            remoteOut.flush()

            val remoteIn = remote.getInputStream()
            val running = AtomicBoolean(true)

            workerPool.execute {
                try {
                    val buf = ByteArray(16 * 1024)
                    while (running.get()) {
                        val n = clientIn.read(buf)
                        if (n <= 0) break
                        val chunk = buf.copyOf(n)
                        val plain = handshake.cltDec.update(chunk)
                        val tgCipher = tgEnc.update(plain)
                        remoteOut.write(tgCipher)
                        remoteOut.flush()
                    }
                } catch (_: Exception) {} finally {
                    running.set(false)
                    try { remote.close() } catch (_: Exception) {}
                    try { client.close() } catch (_: Exception) {}
                }
            }

            workerPool.execute {
                try {
                    val buf = ByteArray(16 * 1024)
                    while (running.get()) {
                        val n = remoteIn.read(buf)
                        if (n <= 0) break
                        val chunk = buf.copyOf(n)
                        val plain = tgDec.update(chunk)
                        val cltCipher = handshake.cltEnc.update(plain)
                        clientOut.write(cltCipher)
                        clientOut.flush()
                    }
                } catch (_: Exception) {} finally {
                    running.set(false)
                    try { remote.close() } catch (_: Exception) {}
                    try { client.close() } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("TG_PROXY", "Direct TCP connection to $dcIp:443 failed: ${e.message}")
            false
        }
    }

    private fun readExact(input: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
        var readTotal = 0
        while (readTotal < len) {
            val n = input.read(buf, off + readTotal, len - readTotal)
            if (n <= 0) return false
            readTotal += n
        }
        return true
    }

    private fun isEndpointCoolingDown(host: String): Boolean {
        val until = endpointCooldownUntil[host] ?: return false
        return until > System.currentTimeMillis()
    }

    private fun markEndpointFailure(host: String) {
        endpointCooldownUntil[host] = System.currentTimeMillis() + 600_000L
    }

    private fun clearEndpointCooldown(host: String) {
        endpointCooldownUntil.remove(host)
    }

    private class MsgSplitter(protoTag: ByteArray) {
        private val protoInt = if (protoTag.size >= 4) {
            ((protoTag[0].toInt() and 0xFF) shl 24) or
                    ((protoTag[1].toInt() and 0xFF) shl 16) or
                    ((protoTag[2].toInt() and 0xFF) shl 8) or
                    (protoTag[3].toInt() and 0xFF)
        } else 0xeeeeeeee.toInt()

        private val cipherBuf = ByteArrayOutputStream()
        private val plainBuf = ByteArrayOutputStream()
        private var disabled = false

        @Synchronized
        fun split(chunk: ByteArray, plain: ByteArray): List<ByteArray> {
            if (chunk.isEmpty()) return emptyList()
            if (disabled) return listOf(chunk)

            cipherBuf.write(chunk)
            plainBuf.write(plain)

            val cipherBytes = cipherBuf.toByteArray()
            val plainBytes = plainBuf.toByteArray()

            val parts = mutableListOf<ByteArray>()
            var offset = 0
            val total = cipherBytes.size

            while (offset < total) {
                val packetLen = nextPacketLen(plainBytes, offset, total - offset) ?: break
                if (packetLen <= 0) {
                    parts.add(cipherBytes.copyOfRange(offset, total))
                    offset = total
                    disabled = true
                    break
                }
                parts.add(cipherBytes.copyOfRange(offset, offset + packetLen))
                offset += packetLen
            }

            if (offset > 0) {
                cipherBuf.reset()
                plainBuf.reset()
                if (offset < total) {
                    cipherBuf.write(cipherBytes, offset, total - offset)
                    plainBuf.write(plainBytes, offset, total - offset)
                }
            }
            return parts
        }

        @Synchronized
        fun flush(): List<ByteArray> {
            val tail = cipherBuf.toByteArray()
            cipherBuf.reset()
            plainBuf.reset()
            return if (tail.isNotEmpty()) listOf(tail) else emptyList()
        }

        private fun nextPacketLen(plain: ByteArray, offset: Int, avail: Int): Int? {
            if (avail <= 0) return null
            val needed = when (protoInt) {
                0xefefefef.toInt() -> { // Abridged
                    val first = plain[offset].toInt() and 0xFF
                    if (first == 0x7F || first == 0xFF) {
                        if (avail < 4) return null
                        val len = ((plain[offset + 1].toInt() and 0xFF) or
                                ((plain[offset + 2].toInt() and 0xFF) shl 8) or
                                ((plain[offset + 3].toInt() and 0xFF) shl 16)) * 4
                        if (len <= 0) 0 else 4 + len
                    } else {
                        val len = (first and 0x7F) * 4
                        if (len <= 0) 0 else 1 + len
                    }
                }
                0xeeeeeeee.toInt(), 0xdddddddd.toInt() -> { // Intermediate or Padded Intermediate
                    if (avail < 4) return null
                    val len = ((plain[offset].toInt() and 0xFF) or
                            ((plain[offset + 1].toInt() and 0xFF) shl 8) or
                            ((plain[offset + 2].toInt() and 0xFF) shl 16) or
                            ((plain[offset + 3].toInt() and 0x7F) shl 24))
                    if (len <= 0) 0 else 4 + len
                }
                else -> 0
            }
            if (needed <= 0) return 0
            return if (avail < needed) null else needed
        }
    }
}
