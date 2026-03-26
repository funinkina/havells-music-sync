package com.example.albumlight

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal Tuya local-protocol client supporting protocol versions 3.3, 3.4, and 3.5.
 *
 * Supports both one-shot (open-send-close) and persistent connections.
 * Use [connect]/[disconnect] around a batch of [setColor] calls to keep
 * the TCP socket (and v3.5 session key) alive across multiple commands.
 */
class TuyaLocalDevice(
    private val ip: String,
    private val devId: String,
    private val localKey: String,
    private val version: Double
) {

    companion object {
        private const val TAG = "TuyaDevice"
        private const val PORT = 6668
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SO_TIMEOUT_MS = 5_000

        // Frame delimiters
        private val PREFIX = byteArrayOf(0x00, 0x00, 0x55.toByte(), 0xAA.toByte())
        private val SUFFIX = byteArrayOf(0x00, 0x00, 0xAA.toByte(), 0x55.toByte())

        // Command IDs
        private const val CMD_CONTROL          = 7   // v3.1 / v3.3
        private const val CMD_CONTROL_NEW      = 13  // v3.4 / v3.5
        private const val CMD_SESS_NEG_START   = 3
        private const val CMD_SESS_NEG_FINISH  = 5

        // Auth tag sizes
        private const val CRC32_SIZE = 4
        private const val HMAC_SIZE  = 32  // full HMAC-SHA256
    }

    private val seqNo = AtomicInteger(1)

    // ── Persistent connection state ─────────────────────────────────────────

    private var socket: Socket? = null
    private var v35SessionKey: ByteArray? = null

    /**
     * Open a persistent TCP connection (and negotiate a v3.5 session key if needed).
     * Subsequent [setColor] calls will reuse this connection until [disconnect].
     */
    fun connect() {
        disconnect()
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            sock.soTimeout = SO_TIMEOUT_MS
            sock.tcpNoDelay = true

            if (version >= 3.5) {
                val sk = negotiateV35Session(sock)
                if (sk != null) {
                    v35SessionKey = sk
                    Log.i(TAG, "v3.5 session negotiated (persistent)")
                } else {
                    Log.w(TAG, "v3.5 negotiation failed; will use v3.3 fallback")
                }
            }

            socket = sock
            Log.d(TAG, "Connected to $ip:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "connect() failed: ${e.message}")
        }
    }

    /** Close the persistent connection, if any. */
    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        v35SessionKey = null
    }

    fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Send a colour command (DPS 20/21/24) to the bulb.
     * If a persistent connection is open it is reused; otherwise a one-shot
     * TCP connection is opened, used, and closed.
     */
    fun setColor(h: Int, s: Int, v: Int) {
        val t = System.currentTimeMillis() / 1000L
        val hex = "%04x%04x%04x".format(
            h.coerceIn(0, 360),
            s.coerceIn(0, 1000),
            v.coerceIn(10, 1000)
        )
        val dps = mapOf("20" to "true", "21" to "\"colour\"", "24" to "\"$hex\"")
        val json = buildJson(t, dps)

        val sock = socket
        if (sock != null && !sock.isClosed) {
            sendOnSocket(sock, json)
        } else {
            sendOneShot(json)
        }
    }

    // ── JSON payload ────────────────────────────────────────────────────────

    private fun buildJson(t: Long, dps: Map<String, String>): String {
        val dpsStr = dps.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        // v3.4+ uses the "protocol 5" envelope; v3.3 uses the legacy format.
        return if (version >= 3.4) {
            """{"protocol":5,"t":"$t","data":{"dps":{$dpsStr}}}"""
        } else {
            """{"devId":"$devId","uid":"$devId","t":"$t","dps":{$dpsStr}}"""
        }
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private fun sendOnSocket(sock: Socket, json: String) {
        try {
            val frame = buildControlFrame(json, v35SessionKey)
            sock.getOutputStream().write(frame)
            sock.getOutputStream().flush()
            Thread.sleep(80)
        } catch (e: Exception) {
            Log.e(TAG, "sendOnSocket failed: ${e.message}")
            disconnect()   // connection may be dead
        }
    }

    private fun sendOneShot(json: String) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
                sock.soTimeout = SO_TIMEOUT_MS
                sock.tcpNoDelay = true

                var sk: ByteArray? = null
                if (version >= 3.5) {
                    sk = negotiateV35Session(sock)
                }

                val frame = buildControlFrame(json, sk)
                sock.getOutputStream().write(frame)
                sock.getOutputStream().flush()
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendOneShot failed: ${e.message}")
        }
    }

    // ── Control-frame builders ──────────────────────────────────────────────

    private fun buildControlFrame(json: String, sessionKey: ByteArray?): ByteArray {
        return when {
            version >= 3.5 && sessionKey != null -> buildFrameV35(json, sessionKey)
            version >= 3.5 -> {
                Log.w(TAG, "v3.5 no session key, falling back to v3.3")
                buildFrameV33(json)
            }
            version >= 3.4 -> buildFrameV34(json)
            else           -> buildFrameV33(json)
        }
    }

    private fun buildFrameV33(json: String): ByteArray {
        val key = keyBytes()
        val encrypted = aesEcbEncrypt(json.toByteArray(Charsets.UTF_8), key)
        val versionHeader = "3.3".toByteArray() + ByteArray(12)   // 15 bytes
        val payload = versionHeader + encrypted
        return assembleFrame(CMD_CONTROL, payload, useHmac = false, hmacKey = null)
    }

    private fun buildFrameV34(json: String): ByteArray {
        val key = keyBytes()
        val encrypted = aesEcbEncrypt(json.toByteArray(Charsets.UTF_8), key)
        val versionHeader = "3.4".toByteArray() + ByteArray(12)
        val payload = versionHeader + encrypted
        return assembleFrame(CMD_CONTROL_NEW, payload, useHmac = true, hmacKey = key)
    }

    private fun buildFrameV35(json: String, sessionKey: ByteArray): ByteArray {
        val encrypted = aesEcbEncrypt(json.toByteArray(Charsets.UTF_8), sessionKey)
        val versionHeader = "3.5".toByteArray() + ByteArray(12)
        val payload = versionHeader + encrypted
        return assembleFrame(CMD_CONTROL_NEW, payload, useHmac = true, hmacKey = sessionKey)
    }

    // ── v3.5 session-key negotiation ────────────────────────────────────────

    private fun negotiateV35Session(sock: Socket): ByteArray? {
        val key = keyBytes()
        return try {
            // 1. Generate 16-byte local nonce
            val localNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }

            // 2. Send SESS_KEY_NEG_START  (negotiation uses CRC32)
            val encryptedNonce = aesEcbEncrypt(localNonce, key)
            val startFrame = assembleFrame(CMD_SESS_NEG_START, encryptedNonce,
                useHmac = false, hmacKey = null)
            sock.getOutputStream().write(startFrame)
            sock.getOutputStream().flush()

            // 3. Read device response
            val buf = ByteArray(1024)
            val n = sock.getInputStream().read(buf)
            if (n < 28) {
                Log.w(TAG, "v3.5 neg response too short ($n bytes)")
                return null
            }

            // 4. Extract & decrypt remote nonce (handles optional return-code field)
            val remoteNonce = extractAndDecryptNonce(buf, n, key) ?: return null

            // 5. Derive session key: localNonce XOR remoteNonce
            val sessionKey = ByteArray(16) { i ->
                (localNonce[i].toInt() xor remoteNonce[i % remoteNonce.size].toInt()).toByte()
            }

            // 6. Send SESS_KEY_NEG_FINISH: HMAC-SHA256(sessionKey, remoteNonce)[:16]
            val hmacFinish = hmacSha256(sessionKey, remoteNonce).copyOf(16)
            val finishFrame = assembleFrame(CMD_SESS_NEG_FINISH, hmacFinish,
                useHmac = false, hmacKey = null)
            sock.getOutputStream().write(finishFrame)
            sock.getOutputStream().flush()

            // 7. Read & discard the finish-ack so it doesn't pollute later reads
            try {
                val prev = sock.soTimeout
                sock.soTimeout = 500
                val ackBuf = ByteArray(256)
                sock.getInputStream().read(ackBuf)
                sock.soTimeout = prev
            } catch (_: Exception) { /* device may not send an ack — that's OK */ }

            sessionKey
        } catch (e: Exception) {
            Log.e(TAG, "v3.5 negotiation error: ${e.message}")
            null
        }
    }

    /**
     * Try to locate the 32-byte encrypted nonce inside the negotiation response.
     * The response may or may not contain a 4-byte return-code field after the
     * 16-byte header; we try both offsets and keep whichever decrypts cleanly.
     */
    private fun extractAndDecryptNonce(buf: ByteArray, n: Int, key: ByteArray): ByteArray? {
        // Encrypted 16-byte nonce with AES-ECB/PKCS5 → 32 bytes of ciphertext.
        val encSize = 32
        for (offset in intArrayOf(16, 20)) {                     // without / with return code
            if (offset + encSize > n) continue
            try {
                val candidate = buf.copyOfRange(offset, offset + encSize)
                val decrypted = aesEcbDecrypt(candidate, key)
                if (decrypted.size == 16) return decrypted        // valid 16-byte nonce
            } catch (_: Exception) { /* bad padding → wrong offset */ }
        }
        Log.w(TAG, "Could not extract remote nonce ($n bytes received)")
        return null
    }

    // ── Frame assembly ──────────────────────────────────────────────────────

    /**
     * Assemble a complete Tuya frame.  The auth tag (CRC32 or HMAC-SHA256) is
     * computed over the **full header + payload**, matching tinytuya behaviour.
     *
     *   PREFIX(4) | SEQ(4) | CMD(4) | LEN(4) | PAYLOAD | AUTH | SUFFIX(4)
     */
    private fun assembleFrame(
        cmd: Int,
        payload: ByteArray,
        useHmac: Boolean,
        hmacKey: ByteArray?
    ): ByteArray {
        val seq = seqNo.getAndIncrement()
        val tagSize = if (useHmac) HMAC_SIZE else CRC32_SIZE
        val dataLen = payload.size + tagSize + SUFFIX.size

        // ── header (16 bytes) ──
        val header = ByteBuffer.allocate(16)
        header.put(PREFIX)
        header.putInt(seq)
        header.putInt(cmd)
        header.putInt(dataLen)
        val headerBytes = header.array()

        // ── auth tag over header + payload ──
        val authInput = headerBytes + payload
        val authTag = if (useHmac) {
            hmacSha256(hmacKey!!, authInput)          // full 32 bytes
        } else {
            val crc = CRC32()
            crc.update(authInput)
            ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
        }

        return headerBytes + payload + authTag + SUFFIX
    }

    // ── Crypto helpers ──────────────────────────────────────────────────────

    private fun keyBytes(): ByteArray =
        localKey.toByteArray(Charsets.UTF_8).copyOf(16)

    private fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
        return cipher.doFinal(data)
    }

    private fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"))
        return cipher.doFinal(data)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.copyOf(16), "HmacSHA256"))
        return mac.doFinal(data)
    }
}
