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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.abs

/**
 * Local Telegram proxy bridge based on Flowseal/tg-ws-proxy & LemoLev/tgwsp-android-rewrite.
 *
 * Runs a local server on 127.0.0.1:1443 supporting both standard SOCKS5 proxy
 * (tg://socks?server=127.0.0.1&port=1443) and MTProto proxy with/without secret.
 *
 * For SOCKS5 traffic, MTProto obfuscation is passed end-to-end to Telegram's Web relay
 * (/apiws) with zero re-encryption overhead, matching the native Flowseal backend.
 * Multiple MTProto messages in a single TCP chunk are detected and split via MsgSplitter
 * so the WS relay handles DH exchange without stalling.
 */
@android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
class TelegramProxyService : Service() {
    companion object {
        const val CHANNEL_ID = "telegram_proxy_channel"
        const val CHANNEL_NAME = "Telegram Proxy"
        const val BIND_HOST = "127.0.0.1"
        const val BIND_PORT = 1443
        const val DEFAULT_SECRET_HEX = "dd00000000000000000000000000000000"
        private const val WS_PATH = "/apiws"
        private const val CF_DOMAINS_URL = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

        // Official Telegram DC IP mapping from tg-ws-proxy
        private val IP_TO_DC = mapOf(
            // DC1
            "149.154.175.50" to Pair(1, false), "149.154.175.51" to Pair(1, false),
            "149.154.175.53" to Pair(1, false), "149.154.175.54" to Pair(1, false),
            "149.154.175.52" to Pair(1, true),
            // DC2
            "149.154.167.41" to Pair(2, false), "149.154.167.50" to Pair(2, false),
            "149.154.167.51" to Pair(2, false), "149.154.167.220" to Pair(2, false),
            "95.161.76.100" to Pair(2, false),
            "149.154.167.151" to Pair(2, true), "149.154.167.222" to Pair(2, true),
            "149.154.167.223" to Pair(2, true), "149.154.162.123" to Pair(2, true),
            // DC3
            "149.154.175.100" to Pair(3, false), "149.154.175.101" to Pair(3, false),
            "149.154.175.102" to Pair(3, true),
            // DC4
            "149.154.167.91" to Pair(4, false), "149.154.167.92" to Pair(4, false),
            "149.154.164.250" to Pair(4, true), "149.154.166.120" to Pair(4, true),
            "149.154.166.121" to Pair(4, true), "149.154.167.118" to Pair(4, true),
            "149.154.165.111" to Pair(4, true),
            // DC5
            "91.108.56.100" to Pair(5, false), "91.108.56.101" to Pair(5, false),
            "91.108.56.116" to Pair(5, false), "91.108.56.126" to Pair(5, false),
            "149.154.171.5" to Pair(5, false),
            "91.108.56.102" to Pair(5, true), "91.108.56.128" to Pair(5, true),
            "91.108.56.151" to Pair(5, true),
            // DC203
            "91.105.192.100" to Pair(203, false)
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
            ByteArray(16), // 16 zeros
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

        private fun isTelegramIp(ip: String): Boolean {
            val parts = ip.split(".")
            if (parts.size != 4) return false
            val n = try {
                ((parts[0].toLong() and 0xFF) shl 24) or
                        ((parts[1].toLong() and 0xFF) shl 16) or
                        ((parts[2].toLong() and 0xFF) shl 8) or
                        (parts[3].toLong() and 0xFF)
            } catch (_: Exception) { return false }

            fun ipToLong(s: String): Long {
                val p = s.split(".")
                return ((p[0].toLong() and 0xFF) shl 24) or
                        ((p[1].toLong() and 0xFF) shl 16) or
                        ((p[2].toLong() and 0xFF) shl 8) or
                        (p[3].toLong() and 0xFF)
            }

            // 185.76.151.0/24
            if (n in ipToLong("185.76.151.0")..ipToLong("185.76.151.255")) return true
            // 149.154.160.0/20
            if (n in ipToLong("149.154.160.0")..ipToLong("149.154.175.255")) return true
            // 91.105.192.0/23
            if (n in ipToLong("91.105.192.0")..ipToLong("91.105.193.255")) return true
            // 91.108.0.0/16
            if (n in ipToLong("91.108.0.0")..ipToLong("91.108.255.255")) return true
            // 95.161.0.0/16 (includes 95.161.76.100 DC2 edge)
            if (n in ipToLong("95.161.0.0")..ipToLong("95.161.255.255")) return true

            return false
        }

        private fun isHttpTransport(data: ByteArray): Boolean {
            if (data.size < 4) return false
            val s = String(data, 0, minOf(data.size, 8))
            return s.startsWith("POST ") || s.startsWith("GET ") || s.startsWith("HEAD ") || s.startsWith("OPTIONS ")
        }
    }

    private data class InitInfo(
        val dc: Int?,
        val isMedia: Boolean,
        val proto: Int
    )

    private data class ClientHandshakeResult(
        val dcId: Int,
        val dcIdx: Int,
        val protoTag: ByteArray,
        val secret: ByteArray?,
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
            maxRequests = 256
            maxRequestsPerHost = 64
        }

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        // Custom DNS: resolves *.web.telegram.org directly to Telegram Web DC IP 149.154.167.220
        val customDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname.endsWith(".web.telegram.org")) {
                    return try {
                        listOf(InetAddress.getByAddress(hostname, byteArrayOf(149.toByte(), 154.toByte(), 167.toByte(), 220.toByte())))
                    } catch (_: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        }

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(customDns)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.SECONDS) // Keepalive is handled at MTProto layer
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
            .setContentText("Listening on 127.0.0.1:1443 (SOCKS5 / MTProto)")
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
                            android.util.Log.i("TG_PROXY", "Refreshed ${merged.size} CF proxy domains from GitHub")
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
                        try {
                            client.receiveBufferSize = 256 * 1024
                            client.sendBufferSize = 256 * 1024
                        } catch (_: Exception) {}
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
                client.close()
                return
            }

            // Case A: Standard SOCKS5 Handshake (Flowseal / LemoLev approach)
            if (first == 0x05) {
                handleSocks5Client(client, clientIn, clientOut)
                return
            }

            // Case B: Direct MTProto Obfuscated2 Handshake
            handleDirectMtprotoClient(client, clientIn, clientOut, first)

        } catch (e: Exception) {
            Logs.e("TelegramProxy client error: ${e.readableMessage}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Handles SOCKS5 proxy connections.
     * Passes raw MTProto obfuscated traffic through WebSocket relay end-to-end
     * with DC patch and MsgSplitter, exactly as in Flowseal / LemoLev tg-ws-proxy.
     */
    private fun handleSocks5Client(client: Socket, clientIn: InputStream, clientOut: OutputStream) {
        val nMethods = clientIn.read()
        if (nMethods <= 0) {
            client.close()
            return
        }
        val methods = ByteArray(nMethods)
        if (!readExact(clientIn, methods, 0, nMethods)) {
            client.close()
            return
        }

        // SOCKS5 greeting response: NO AUTH REQUIRED
        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        // SOCKS5 request: VER(1) CMD(1) RSV(1) ATYP(1)
        val req = ByteArray(4)
        if (!readExact(clientIn, req, 0, 4)) {
            client.close()
            return
        }

        val ver = req[0].toInt() and 0xFF
        val cmd = req[1].toInt() and 0xFF
        val atyp = req[3].toInt() and 0xFF

        if (ver != 0x05 || cmd != 0x01) { // Only CONNECT is supported
            clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
            client.close()
            return
        }

        val dstHost: String = when (atyp) {
            0x01 -> { // IPv4
                val addr = ByteArray(4)
                if (!readExact(clientIn, addr, 0, 4)) { client.close(); return }
                "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
            }
            0x03 -> { // Domain name
                val len = clientIn.read()
                if (len <= 0) { client.close(); return }
                val d = ByteArray(len)
                if (!readExact(clientIn, d, 0, len)) { client.close(); return }
                String(d)
            }
            0x04 -> { // IPv6 - reject as in backend.py
                val addr = ByteArray(16)
                readExact(clientIn, addr, 0, 16)
                clientOut.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                client.close()
                return
            }
            else -> {
                clientOut.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
                client.close()
                return
            }
        }

        val portBytes = ByteArray(2)
        if (!readExact(clientIn, portBytes, 0, 2)) {
            client.close()
            return
        }
        val dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        // Check if destination is Telegram DC (by IP, domain, or common Telegram ports)
        val isTg = isTelegramIp(dstHost) || dstPort in intArrayOf(443, 80, 8443, 5222) || dstHost.contains("telegram")

        if (!isTg) {
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
            bridgeTcpPlain(client, clientIn, clientOut, dstHost, dstPort)
            return
        }

        // Telegram DC destination: accept SOCKS connection
        clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        clientOut.flush()

        // Read 64-byte MTProto Obfuscated2 initial packet
        val init = ByteArray(64)
        if (!readExact(clientIn, init, 0, 64)) {
            client.close()
            return
        }

        if (isHttpTransport(init)) {
            client.close()
            return
        }

        // Extract DC ID, protocol tag, and media flag from initial handshake
        val info = extractDcFromInit(init)
        var dc = info?.dc
        var isMedia = info?.isMedia ?: false
        val proto = info?.proto ?: 0xeeeeeeee.toInt()

        if (dc == null) {
            val (mappedDc, mappedMedia) = getDcFromHostFallback(dstHost)
            dc = mappedDc
            isMedia = mappedMedia
        }

        if (dc == 203) dc = 2
        val finalDc = if (dc in 1..5) dc else 2

        // Patch DC in init packet (critical for mobile clients with useSecret=0)
        val patchedInit = patchInitDc(init, finalDc, isMedia)

        // Connect to fast WebSocket relay
        val session = connectFastWs(clientOut, finalDc, isMedia, patchedInit)
        if (session != null) {
            streamWs(client, clientIn, session, patchedInit, proto)
            return
        }

        // Fallback to direct TCP
        bridgeTcpDirect(client, clientIn, clientOut, dstHost, dstPort, patchedInit)
    }

    /**
     * Handles direct MTProto connections (e.g. from tg://proxy?server=... links).
     */
    private fun handleDirectMtprotoClient(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        firstByte: Int
    ) {
        val handshakeBytes = ByteArray(64)
        handshakeBytes[0] = firstByte.toByte()
        if (!readExact(clientIn, handshakeBytes, 1, 63)) {
            client.close()
            return
        }

        if (isHttpTransport(handshakeBytes)) {
            client.close()
            return
        }

        val handshake = tryDecryptClientHandshake(handshakeBytes)
        if (handshake == null) {
            client.close()
            return
        }

        val targetDc = if (handshake.dcId in 1..5) handshake.dcId else 2
        val proto = ((handshake.protoTag[0].toInt() and 0xFF) shl 24) or
                ((handshake.protoTag[1].toInt() and 0xFF) shl 16) or
                ((handshake.protoTag[2].toInt() and 0xFF) shl 8) or
                (handshake.protoTag[3].toInt() and 0xFF)

        // If client connects without secret, we can use zero-reencryption directly
        if (handshake.secret == null) {
            val patchedInit = patchInitDc(handshakeBytes, targetDc, false)
            val session = connectFastWs(clientOut, targetDc, false, patchedInit)
            if (session != null) {
                streamWs(client, clientIn, session, patchedInit, proto)
                return
            }
            bridgeTcpDirect(client, clientIn, clientOut, "149.154.167.50", 443, patchedInit)
            return
        }

        // MTProto with secret (e.g. dd00...): unwrap & re-encrypt
        val (relayInit, tgEnc, tgDec) = generateRelayInit(handshake.protoTag, handshake.dcIdx)
        val session = connectFastWs(clientOut, targetDc, false, relayInit) { data ->
            val plain = tgDec.update(data)
            handshake.cltEnc.update(plain)
        }
        if (session != null) {
            val splitter = MsgSplitter(handshakeBytes, proto)
            val upstream = Thread {
                try {
                    val buf = ByteArray(64 * 1024)
                    while (session.running.get()) {
                        val n = clientIn.read(buf)
                        if (n <= 0) break
                        val chunk = buf.copyOf(n)
                        val plain = handshake.cltDec.update(chunk)
                        val tgCipher = tgEnc.update(plain)
                        val parts = splitter.process(tgCipher)
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
                    for (part in tail) {
                        try { session.ws.send(part.toByteString()) } catch (_: Exception) {}
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

        client.close()
    }

    /**
     * Streams client TCP data to WebSocket using MsgSplitter.
     * Buffers partial MTProto messages and ensures each WebSocket binary frame
     * corresponds to exactly one MTProto message.
     */
    private fun streamWs(client: Socket, clientIn: InputStream, session: WsSession, initPacket: ByteArray, proto: Int) {
        val splitter = MsgSplitter(initPacket, proto)
        val upstream = Thread {
            try {
                val buf = ByteArray(64 * 1024)
                while (session.running.get()) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    val parts = splitter.process(chunk)
                    for (part in parts) {
                        if (!session.ws.send(part.toByteString())) {
                            session.running.set(false)
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                session.running.set(false)
                val tail = splitter.flush()
                for (part in tail) {
                    try { session.ws.send(part.toByteString()) } catch (_: Exception) {}
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
    }

    /**
     * Decrypts bytes 56..64 of 64-byte init packet using AES-CTR keystream
     * to extract DC ID and protocol tag.
     */
    private fun extractDcFromInit(data: ByteArray): InitInfo? {
        if (data.size < 64) return null
        return try {
            val key = data.copyOfRange(8, 40)
            val iv = data.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            val keystream = cipher.update(ByteArray(64))
            val plain = ByteArray(8)
            for (i in 0 until 8) {
                plain[i] = (data[56 + i].toInt() xor keystream[56 + i].toInt()).toByte()
            }
            val proto = (plain[0].toInt() and 0xFF) or
                    ((plain[1].toInt() and 0xFF) shl 8) or
                    ((plain[2].toInt() and 0xFF) shl 16) or
                    ((plain[3].toInt() and 0xFF) shl 24)

            val dcRaw = ((plain[4].toInt() and 0xFF) or (plain[5].toInt() shl 8)).toShort().toInt()

            val validProto = proto == 0xEFEFEFEF.toInt() || proto == 0xEEEEEEEE.toInt() || proto == 0xDDDDDDDD.toInt()
            val effectiveProto = if (validProto) proto else 0xeeeeeeee.toInt()

            val dc = abs(dcRaw)
            val hasValidDc = dc in 1..5 || dc == 203
            val isMedia = dcRaw < 0

            InitInfo(
                dc = if (hasValidDc) (if (dc == 203) 2 else dc) else null,
                isMedia = isMedia,
                proto = effectiveProto
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Patches bytes 60..61 (dc_id) in the 64-byte init packet using AES-CTR keystream.
     * Required by Telegram's WS relay when client leaves DC bytes randomized.
     */
    private fun patchInitDc(data: ByteArray, dc: Int, isMedia: Boolean): ByteArray {
        if (data.size < 64) return data
        return try {
            val key = data.copyOfRange(8, 40)
            val iv = data.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
            val keystream = cipher.update(ByteArray(64))
            val patched = data.clone()
            val dcRaw = (if (isMedia) -dc else dc).toShort()
            val dc0 = (dcRaw.toInt() and 0xFF).toByte()
            val dc1 = ((dcRaw.toInt() shr 8) and 0xFF).toByte()
            patched[60] = (keystream[60].toInt() xor dc0.toInt()).toByte()
            patched[61] = (keystream[61].toInt() xor dc1.toInt()).toByte()
            patched
        } catch (_: Exception) {
            data
        }
    }

    private fun getDcFromHostFallback(host: String): Pair<Int, Boolean> {
        val mapped = IP_TO_DC[host]
        if (mapped != null) return mapped
        return when {
            host.startsWith("149.154.175.") -> Pair(1, false)
            host.startsWith("149.154.167.") -> Pair(2, false)
            host.startsWith("91.108.") -> Pair(5, false)
            else -> Pair(2, false)
        }
    }

    private fun domainsForDc(dc: Int, isMedia: Boolean): List<String> {
        val effectiveDc = if (dc == 203) 2 else (if (dc in 1..5) dc else 2)
        val result = mutableListOf<String>()

        val active = activeCfDomain[effectiveDc]
        val sub = if (isMedia) "kws$effectiveDc-1" else "kws$effectiveDc"
        val altSub = if (isMedia) "kws$effectiveDc" else "kws$effectiveDc-1"

        // 1. If we have a previously successful domain for this DC, try it first
        if (active != null) {
            val d = "$sub.$active"
            if (!isEndpointCoolingDown(d)) result.add(d)
        }

        // 2. Direct Telegram Web endpoints (LemoLev approach, connects via 149.154.167.220)
        val direct1 = "$sub.web.telegram.org"
        val direct2 = "$altSub.web.telegram.org"
        if (!result.contains(direct1) && !isEndpointCoolingDown(direct1)) result.add(direct1)
        if (!result.contains(direct2) && !isEndpointCoolingDown(direct2)) result.add(direct2)

        // 3. Fast Cloudflare proxy domains (Flowseal approach)
        for (base in cfDomainsList) {
            val d = "$sub.$base"
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

    private class WsSession(
        val ws: WebSocket,
        val domain: String,
        val finished: CountDownLatch,
        val running: AtomicBoolean
    )

    private fun connectFastWs(
        clientOut: OutputStream,
        targetDc: Int,
        isMedia: Boolean,
        initPacket: ByteArray,
        onIncomingData: ((ByteArray) -> ByteArray)? = null
    ): WsSession? {
        val candidates = domainsForDc(targetDc, isMedia)
            .filter { !isEndpointCoolingDown(it) }
            .take(4)
            .ifEmpty { domainsForDc(targetDc, isMedia).take(2) }

        if (candidates.isEmpty()) return null

        val winnerLatch = CountDownLatch(1)
        val winningSession = java.util.concurrent.atomic.AtomicReference<WsSession?>(null)
        val pendingWebSockets = CopyOnWriteArrayList<WebSocket>()

        for (domain in candidates) {
            if (winningSession.get() != null) break

            val finished = CountDownLatch(1)
            val running = AtomicBoolean(true)

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val session = WsSession(webSocket, domain, finished, running)
                    if (winningSession.compareAndSet(null, session)) {
                        android.util.Log.i("TG_PROXY", "WS race WON by $domain (HTTP ${response.code})")
                        // Send patched init packet as the first binary frame
                        webSocket.send(initPacket.toByteString())
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
                        val raw = bytes.toByteArray()
                        val toWrite = if (onIncomingData != null) onIncomingData(raw) else raw
                        synchronized(clientOut) {
                            clientOut.write(toWrite)
                            clientOut.flush()
                        }
                    } catch (_: Exception) {
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

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    running.set(false)
                    finished.countDown()
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
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header("Sec-WebSocket-Protocol", "binary")
                .header("Origin", "https://web.telegram.org")
                .build()

            val ws = wsClient.newWebSocket(request, listener)
            pendingWebSockets.add(ws)

            // Stagger candidates by 250ms; if one connects, break early
            if (winnerLatch.await(250, TimeUnit.MILLISECONDS)) {
                break
            }
        }

        // Wait up to 3000ms for any candidate to complete handshake
        if (winningSession.get() == null) {
            winnerLatch.await(3000, TimeUnit.MILLISECONDS)
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

    private fun bridgeTcpPlain(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dstHost: String,
        dstPort: Int
    ) {
        workerPool.execute {
            try {
                val remote = Socket()
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(dstHost, dstPort), 5000)
                val remoteIn = remote.getInputStream()
                val remoteOut = remote.getOutputStream()
                val running = AtomicBoolean(true)

                workerPool.execute {
                    try {
                        val buf = ByteArray(64 * 1024)
                        while (running.get()) {
                            val n = clientIn.read(buf)
                            if (n <= 0) break
                            remoteOut.write(buf, 0, n)
                            remoteOut.flush()
                        }
                    } catch (_: Exception) {} finally {
                        running.set(false)
                        try { remote.close() } catch (_: Exception) {}
                        try { client.close() } catch (_: Exception) {}
                    }
                }

                try {
                    val buf = ByteArray(64 * 1024)
                    while (running.get()) {
                        val n = remoteIn.read(buf)
                        if (n <= 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Exception) {} finally {
                    running.set(false)
                    try { remote.close() } catch (_: Exception) {}
                    try { client.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun bridgeTcpDirect(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dstIp: String,
        dstPort: Int,
        initPacket: ByteArray
    ): Boolean {
        return try {
            val remote = Socket()
            remote.tcpNoDelay = true
            remote.receiveBufferSize = 256 * 1024
            remote.sendBufferSize = 256 * 1024
            remote.connect(InetSocketAddress(dstIp, dstPort), 5000)
            val remoteOut = remote.getOutputStream()
            remoteOut.write(initPacket)
            remoteOut.flush()

            val remoteIn = remote.getInputStream()
            val running = AtomicBoolean(true)

            workerPool.execute {
                try {
                    val buf = ByteArray(64 * 1024)
                    while (running.get()) {
                        val n = clientIn.read(buf)
                        if (n <= 0) break
                        remoteOut.write(buf, 0, n)
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
                    val buf = ByteArray(64 * 1024)
                    while (running.get()) {
                        val n = remoteIn.read(buf)
                        if (n <= 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Exception) {} finally {
                    running.set(false)
                    try { remote.close() } catch (_: Exception) {}
                    try { client.close() } catch (_: Exception) {}
                }
            }
            true
        } catch (_: Exception) {
            false
        }
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
                val v = ((protoTag[0].toInt() and 0xFF) shl 24) or
                        ((protoTag[1].toInt() and 0xFF) shl 16) or
                        ((protoTag[2].toInt() and 0xFF) shl 8) or
                        (protoTag[3].toInt() and 0xFF)
                if (v == 0xefefefef.toInt() || v == 0xeeeeeeee.toInt() || v == 0xdddddddd.toInt()) {
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
                    return ClientHandshakeResult(dcId, dcIdx.toInt(), protoTag, secret, decryptor, cltEnc)
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

        tgEnc.update(ByteArray(56))
        val encryptedTail = tgEnc.update(tailPlain)
        System.arraycopy(encryptedTail, 0, rnd, 56, 8)

        return RelayInitResult(rnd, tgEnc, tgDec)
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

    /**
     * Splits client TCP data into individual MTProto messages based on Flowseal/LemoLev implementation.
     * Decrypts the stream in memory to discover message length boundaries,
     * buffers partial messages across TCP chunks, and returns only 100% complete MTProto messages
     * so each message is sent as a distinct WebSocket binary frame without desync.
     */
    private class MsgSplitter(initData: ByteArray, private val proto: Int) {
        private val decCipher: Cipher? = try {
            val key = initData.copyOfRange(8, 40)
            val iv = initData.copyOfRange(40, 56)
            Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                update(ByteArray(64)) // skip first 64 bytes of keystream
            }
        } catch (_: Exception) { null }

        private var pendingCipher = ByteArray(0)
        private var pendingPlain = ByteArray(0)

        @Synchronized
        fun process(chunk: ByteArray): List<ByteArray> {
            val cipher = decCipher ?: return listOf(chunk)
            val plain = cipher.update(chunk) ?: return listOf(chunk)

            val allCipher = if (pendingCipher.isEmpty()) chunk else pendingCipher + chunk
            val allPlain = if (pendingPlain.isEmpty()) plain else pendingPlain + plain

            val frames = mutableListOf<ByteArray>()
            var pos = 0
            val total = allPlain.size

            while (pos < total) {
                val remain = total - pos
                val frameLen: Int

                when (proto) {
                    0xefefefef.toInt() -> { // Abridged
                        val first = allPlain[pos].toInt() and 0xFF
                        if (first == 0x7F) {
                            if (remain < 4) break
                            val l = (allPlain[pos + 1].toInt() and 0xFF) or
                                    ((allPlain[pos + 2].toInt() and 0xFF) shl 8) or
                                    ((allPlain[pos + 3].toInt() and 0xFF) shl 16)
                            frameLen = 4 + (l * 4)
                        } else {
                            frameLen = 1 + (first * 4)
                        }
                    }
                    0xeeeeeeee.toInt(), 0xdddddddd.toInt() -> { // Intermediate / Padded Intermediate
                        if (remain < 4) break
                        val l = (allPlain[pos].toInt() and 0xFF) or
                                ((allPlain[pos + 1].toInt() and 0xFF) shl 8) or
                                ((allPlain[pos + 2].toInt() and 0xFF) shl 16) or
                                ((allPlain[pos + 3].toInt() and 0xFF) shl 24)
                        frameLen = 4 + l
                    }
                    else -> {
                        frameLen = remain
                    }
                }

                if (frameLen <= 0 || frameLen > 2 * 1024 * 1024) {
                    frames.add(allCipher.copyOfRange(pos, total))
                    pos = total
                    break
                }

                if (remain < frameLen) {
                    // Partial message! Buffer and wait for remaining TCP data
                    break
                }

                // Exactly one complete MTProto message
                frames.add(allCipher.copyOfRange(pos, pos + frameLen))
                pos += frameLen
            }

            if (pos < total) {
                pendingCipher = allCipher.copyOfRange(pos, total)
                pendingPlain = allPlain.copyOfRange(pos, total)
            } else {
                pendingCipher = ByteArray(0)
                pendingPlain = ByteArray(0)
            }

            return frames
        }

        @Synchronized
        fun flush(): List<ByteArray> {
            if (pendingCipher.isNotEmpty()) {
                val tail = pendingCipher
                pendingCipher = ByteArray(0)
                pendingPlain = ByteArray(0)
                return listOf(tail)
            }
            return emptyList()
        }
    }
}
