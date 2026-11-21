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
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.abs

/**
 * Local Telegram proxy bridge using your personal Cloudflare Worker WebSocket tunnel.
 *
 * Dedicated local listeners:
 * - MTProto listener on 127.0.0.1:1443 (pure Obfuscated2)
 * - SOCKS5 listener on 127.0.0.1:1080 (pure SOCKS5)
 */
@android.annotation.SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
class TelegramProxyService : Service() {
    companion object {
        const val CHANNEL_ID = "telegram_proxy_channel"
        const val CHANNEL_NAME = "Telegram Proxy"
        const val BIND_HOST = "127.0.0.1"
        const val BIND_PORT_MTPROTO = 1443
        const val BIND_PORT_SOCKS5 = 1080
        const val BIND_PORT = BIND_PORT_MTPROTO // Backward-compatible alias
        const val DEFAULT_SECRET_HEX = "dd00000000000000000000000000000000"

        // =========================================================================
        // ЕДИНСТВЕННЫЙ ДОМЕН ДЛЯ ПОДКЛЮЧЕНИЯ (ВАШ CLOUDFLARE WORKER)
        // =========================================================================
        const val WORKER_DOMAIN = "bitter-morning-5992.gafkman15.workers.dev"
        private const val WS_PATH = "/apiws"

        private val DC_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.220",
            5 to "149.154.171.5"
        )

        private val KNOWN_SECRETS = listOf(
            ByteArray(16), // 16 zeros: matches dd00000000000000000000000000000000 and 00000000000000000000000000000000
            hexToBytes("000000000000000000000000000000dd"), // 15 zeros + 0xdd
            hexToBytes("0123456789abcdef0123456789abcdef"),
            null // Plain MTProto without secret
        )

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

        private fun isHttpTransport(data: ByteArray): Boolean {
            if (data.size < 4) return false
            val s = String(data, 0, minOf(data.size, 8))
            return s.startsWith("POST ") || s.startsWith("GET ") || s.startsWith("HEAD ") || s.startsWith("OPTIONS ")
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

    private var mtprotoServer: ServerSocket? = null
    private var mtprotoServerThread: Thread? = null
    private var socks5Server: ServerSocket? = null
    private var socks5ServerThread: Thread? = null

    private val workerPool = Executors.newCachedThreadPool()

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

        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mtprotoServer == null || mtprotoServer?.isClosed == true) {
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
            .setContentTitle("Telegram Proxy (Cloudflare Worker)")
            .setContentText("Worker: $WORKER_DOMAIN")
            .setSmallIcon(R.drawable.ic_service_active)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startServer() {
        // 1. Start pure MTProto server on port 1443
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(BIND_HOST, BIND_PORT_MTPROTO))
            mtprotoServer = s

            mtprotoServerThread = Thread {
                while (!Thread.currentThread().isInterrupted && !s.isClosed) {
                    try {
                        val client = s.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 0
                        try {
                            client.receiveBufferSize = 256 * 1024
                            client.sendBufferSize = 256 * 1024
                        } catch (_: Exception) {}
                        workerPool.execute { handleMtprotoClient(client) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            Logs.d("Telegram MTProto Proxy started on $BIND_HOST:$BIND_PORT_MTPROTO -> $WORKER_DOMAIN")
        } catch (e: Exception) {
            Logs.e("Failed to start MTProto Proxy on $BIND_PORT_MTPROTO: ${e.readableMessage}")
        }

        // 2. Start separate SOCKS5 server on port 1080
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(BIND_HOST, BIND_PORT_SOCKS5))
            socks5Server = s

            socks5ServerThread = Thread {
                while (!Thread.currentThread().isInterrupted && !s.isClosed) {
                    try {
                        val client = s.accept()
                        client.tcpNoDelay = true
                        client.soTimeout = 0
                        try {
                            client.receiveBufferSize = 256 * 1024
                            client.sendBufferSize = 256 * 1024
                        } catch (_: Exception) {}
                        workerPool.execute { handleSocks5Client(client) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            Logs.d("Telegram SOCKS5 Proxy started on $BIND_HOST:$BIND_PORT_SOCKS5 -> $WORKER_DOMAIN")
        } catch (e: Exception) {
            Logs.e("Failed to start SOCKS5 Proxy on $BIND_PORT_SOCKS5: ${e.readableMessage}")
        }
    }

    private fun stopServer() {
        try { mtprotoServerThread?.interrupt() } catch (_: Exception) {}
        try { mtprotoServer?.close() } catch (_: Exception) {}
        mtprotoServer = null
        mtprotoServerThread = null

        try { socks5ServerThread?.interrupt() } catch (_: Exception) {}
        try { socks5Server?.close() } catch (_: Exception) {}
        socks5Server = null
        socks5ServerThread = null
    }

    /**
     * Dedicated handler for Telegram MTProto Obfuscated2 client on port 1443.
     */
    private fun handleMtprotoClient(client: Socket) {
        try {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            val handshakeBytes = ByteArray(64)
            if (!readExact(clientIn, handshakeBytes, 0, 64)) {
                client.close()
                return
            }

            if (isHttpTransport(handshakeBytes)) {
                client.close()
                return
            }

            val handshake = tryDecryptClientHandshake(handshakeBytes)
            if (handshake == null) {
                android.util.Log.w("TG_PROXY", "Failed to decrypt MTProto handshake: ${handshakeBytes.take(16).joinToString("") { "%02x".format(it) }}")
                client.close()
                return
            }

            val targetDc = if (handshake.dcId in 1..5) handshake.dcId else 2
            android.util.Log.i("TG_PROXY", "MTProto client connected for DC$targetDc -> tunneling via $WORKER_DOMAIN")

            val session = connectWorkerWs(clientOut, handshake.copy(dcId = targetDc), targetDc)
            if (session != null) {
                val upstream = Thread {
                    try {
                        val buf = ByteArray(32 * 1024)
                        while (session.running.get()) {
                            val n = clientIn.read(buf)
                            if (n <= 0) break
                            val chunk = buf.copyOf(n)
                            val plain = handshake.cltDec.update(chunk)
                            val tgCipher = session.tgEnc.update(plain)
                            if (!session.ws.send(tgCipher.toByteString())) {
                                session.running.set(false)
                                break
                            }
                        }
                    } catch (_: Exception) {} finally {
                        session.running.set(false)
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

            android.util.Log.w("TG_PROXY", "WebSocket connection to $WORKER_DOMAIN failed for DC$targetDc")
            try { client.close() } catch (_: Exception) {}

        } catch (e: Exception) {
            Logs.e("Telegram MTProto client error: ${e.readableMessage}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Dedicated handler for Telegram SOCKS5 client on port 1080.
     */
    private fun handleSocks5Client(client: Socket) {
        try {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            val first = clientIn.read()
            if (first != 0x05) {
                client.close()
                return
            }

            val targetDc = handleSocks5Handshake(clientIn, clientOut)
            if (targetDc == null) {
                client.close()
                return
            }

            val handshakeBytes = ByteArray(64)
            if (!readExact(clientIn, handshakeBytes, 0, 64)) {
                client.close()
                return
            }

            val handshake = tryDecryptClientHandshake(handshakeBytes)
            if (handshake != null) {
                val session = connectWorkerWs(clientOut, handshake.copy(dcId = targetDc), targetDc)
                if (session != null) {
                    val upstream = Thread {
                        try {
                            val buf = ByteArray(32 * 1024)
                            while (session.running.get()) {
                                val n = clientIn.read(buf)
                                if (n <= 0) break
                                val chunk = buf.copyOf(n)
                                val plain = handshake.cltDec.update(chunk)
                                val tgCipher = session.tgEnc.update(plain)
                                if (!session.ws.send(tgCipher.toByteString())) {
                                    session.running.set(false)
                                    break
                                }
                            }
                        } catch (_: Exception) {} finally {
                            session.running.set(false)
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
            }

            android.util.Log.w("TG_PROXY", "SOCKS5 WS connection to $WORKER_DOMAIN failed for DC$targetDc")
            try { client.close() } catch (_: Exception) {}

        } catch (e: Exception) {
            Logs.e("Telegram SOCKS5 client error: ${e.readableMessage}")
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

        return when {
            host == "149.154.175.50" || host.startsWith("149.154.175.") -> 1
            host == "149.154.167.50" || host == "149.154.167.51" || host.startsWith("149.154.167.") -> 2
            host == "149.154.175.100" -> 3
            host == "149.154.167.91" || host == "149.154.167.220" || host.startsWith("149.154.164.") || host.startsWith("149.154.166.") -> 4
            host == "149.154.171.5" || host.startsWith("91.108.") -> 5
            else -> 2
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
        val tgEnc: Cipher,
        val finished: CountDownLatch,
        val running: AtomicBoolean
    )

    /**
     * Connects directly to the user's Cloudflare Worker:
     * https://bitter-morning-5992.gafkman15.workers.dev/apiws?dst=<telegram_dc_ip>
     */
    private fun connectWorkerWs(
        clientOut: OutputStream,
        handshake: ClientHandshakeResult,
        targetDc: Int
    ): WsSession? {
        val dcIp = DC_IPS[targetDc] ?: DC_IPS[2] ?: "149.154.167.51"
        val targetUrl = "https://$WORKER_DOMAIN$WS_PATH?dst=$dcIp"
        android.util.Log.i("TG_PROXY", "Connecting WS -> $targetUrl (DC$targetDc)")

        val (relayInit, tgEnc, tgDec) = generateRelayInit(handshake.protoTag, handshake.dcIdx)
        val finished = CountDownLatch(1)
        val running = AtomicBoolean(true)
        val openLatch = CountDownLatch(1)
        val winningSession = AtomicReference<WsSession?>(null)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                android.util.Log.i("TG_PROXY", "WS connected to $WORKER_DOMAIN (HTTP ${response.code}) for DC$targetDc")
                val session = WsSession(webSocket, tgEnc, finished, running)
                winningSession.set(session)
                webSocket.send(relayInit.toByteString())
                openLatch.countDown()
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
                if (running.getAndSet(false)) {
                    finished.countDown()
                }
                try {
                    webSocket.close(1000, null)
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running.getAndSet(false)) {
                    if (t !is SocketException || t.message?.contains("closed", ignoreCase = true) != true) {
                        android.util.Log.w("TG_PROXY", "WS to $WORKER_DOMAIN failed: ${t.javaClass.simpleName} - ${t.message}")
                    }
                    finished.countDown()
                }
                openLatch.countDown()
            }
        }

        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Sec-WebSocket-Protocol", "binary")
            .header("Origin", "https://web.telegram.org")
            .build()

        val ws = wsClient.newWebSocket(request, listener)
        if (!openLatch.await(10, TimeUnit.SECONDS) || winningSession.get() == null) {
            try { ws.cancel() } catch (_: Exception) {}
            return null
        }
        return winningSession.get()
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
}
