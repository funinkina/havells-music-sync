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
 * ┌─────────────────────────────────────────────────────────────┐
 * │  v3.3  – AES-128-ECB + CRC32                                │
 * │  v3.4  – AES-128-ECB + HMAC-SHA256 (16-byte tag)            │
 * │  v3.5  – Session-key negotiation, then AES + HMAC-SHA256    │
 * └─────────────────────────────────────────────────────────────┘
 *
 * Each call to [setColor] opens a fresh TCP connection and closes it when done.
 * This mirrors tinytuya's non-persistent mode and is the safest approach for
 * fire-and-forget colour changes from a background service.
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
        private const val CMD_SET              = 13
        private const val CMD_SESS_NEG_START   = 3
        private const val CMD_SESS_NEG_FINISH  = 5
    }

    private val seqNo = AtomicInteger(1)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Send a colour command (DPS 20/21/24) to the bulb.
     * Blocks until the TCP exchange completes or times out.
     * Safe to call from any background thread.
     */
    fun setColor(h: Int, s: Int, v: Int) {
        val t = System.currentTimeMillis() / 1000L
        val hex = "%04x%04x%04x".format(
            h.coerceIn(0, 360),
            s.coerceIn(0, 1000),
            v.coerceIn(10, 1000)
        )
        val json = buildJson(t, mapOf("20" to "true", "21" to "\"colour\"", "24" to "\"$hex\""))
        sendPayload(json)
    }

    // ── Payload builder ───────────────────────────────────────────────────────

    private fun buildJson(t: Long, dps: Map<String, String>): String {
        val dpsStr = dps.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        return """{"devId":"$devId","uid":"$devId","t":$t,"dps":{$dpsStr}}"""
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    private fun sendPayload(json: String) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
                sock.soTimeout = SO_TIMEOUT_MS
                val out = sock.getOutputStream()
                val inp = sock.getInputStream()

                val frame = when {
                    version >= 3.5 -> buildFrameV35(sock, json)
                    version >= 3.4 -> buildFrameV34(json)
                    else           -> buildFrameV33(json)
                }
                out.write(frame)
                out.flush()
                // Give the bulb a moment to process; we don't need to parse the response.
                Thread.sleep(120)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPayload failed: ${e.message}")
        }
    }

    // ── v3.3 ──────────────────────────────────────────────────────────────────

    private fun buildFrameV33(json: String): ByteArray {
        val encrypted = aesEcbEncrypt(json.toByteArray(Charsets.UTF_8), keyBytes())
        val payload = "3.3".toByteArray() + ByteArray(9) + encrypted  // 3+9 = 12-byte header
        return assembleFrame(CMD_SET, payload, authTag = crc32Tag(payload))
    }

    // ── v3.4 ──────────────────────────────────────────────────────────────────

    private fun buildFrameV34(json: String): ByteArray {
        val key = keyBytes()
        val encrypted = aesEcbEncrypt(json.toByteArray(Charsets.UTF_8), key)
        val payload = "3.4".toByteArray() + ByteArray(9) + encrypted
        return assembleFrame(CMD_SET, payload, authTag = hmacTag(key, payload))
    }

    // ── v3.5 ──────────────────────────────────────────────────────────────────

    /**
     * Full session-key negotiation (SESS_KEY_NEG_START → SESS_KEY_NEG_FINISH)
     * then encrypt with the derived session key and HMAC with it.
     *
     * If anything goes wrong during negotiation we fall back to v3.3 framing.
     */
    private fun buildFrameV35(sock: Socket, json: String): ByteArray {
        val key = keyBytes()
        return try {
            // 1. Generate 16-byte local nonce
            val localNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }

            // 2. Send SESS_KEY_NEG_START (negotiation messages use CRC32, not HMAC)
            val encryptedNonce = aesEcbEncrypt(localNonce, key)
            val startFrame = assembleFrame(CMD_SESS_NEG_START, encryptedNonce, authTag = crc32Tag(encryptedNonce))
            sock.getOutputStream().write(startFrame)
            sock.getOutputStream().flush()

            // 3. Read device response
            val buf = ByteArray(1024)
            val inp = sock.getInputStream()
            val n = inp.read(buf)
            if (n <= 0) {
                Log.w(TAG, "No response from device")
                return buildFrameV33(json)
            }
            if (n < 20) {
                Log.w(TAG, "v3.5 neg response too short ($n bytes), falling back to v3.3")
                return buildFrameV33(json)
            }

            // Response layout: PREFIX(4) SEQ(4) CMD(4) LEN(4) PAYLOAD CRC/HMAC(4+) SUFFIX(4)
            // Payload starts at byte 16; strip last 8 bytes (tag + suffix).
            val payloadLen = n - 16 - 8
            if (payloadLen < 16) {
                Log.w(TAG, "v3.5 neg payload too short, falling back")
                return buildFrameV33(json)
            }
            val deviceEncrypted = buf.copyOfRange(16, 16 + payloadLen)

            // 4. Decrypt device nonce with local key
            val remoteNonce: ByteArray = try {
                aesEcbDecrypt(deviceEncrypted, key)
            } catch (e: Exception) {
                Log.w(TAG, "v3.5 decrypt device nonce failed: ${e.message}, falling back")
                return buildFrameV33(json)
            }

            // 5. Derive session key: sessionKey[i] = localNonce[i] XOR remoteNonce[i]
            val sessionKey = ByteArray(16) { i ->
                (localNonce[i].toInt() xor remoteNonce[i % remoteNonce.size].toInt()).toByte()
            }

            // 6. Send SESS_KEY_NEG_FINISH: HMAC-SHA256(sessionKey, remoteNonce) first 16 bytes
            val hmacData = hmacSha256(sessionKey, remoteNonce).copyOf(16)
            val finishFrame = assembleFrame(CMD_SESS_NEG_FINISH, hmacData, authTag = crc32Tag(hmacData))
            sock.getOutputStream().write(finishFrame)
            sock.getOutputStream().flush()
            Thread.sleep(60)

            // 7. Build SET command using session key
            val encrypted = aesEcbEncrypt(json.toByteArray(Charsets.UTF_8), sessionKey)
            val payload = "3.5".toByteArray() + ByteArray(9) + encrypted
            assembleFrame(CMD_SET, payload, authTag = hmacTag(sessionKey, payload))

        } catch (e: Exception) {
            Log.e(TAG, "v3.5 session negotiation failed: ${e.message}, falling back to v3.3")
            buildFrameV33(json)
        }
    }

    // ── Frame assembly ────────────────────────────────────────────────────────

    /**
     * Assemble a complete Tuya frame:
     *   PREFIX(4) | SEQ(4) | CMD(4) | DATA_LEN(4) | PAYLOAD | AUTH_TAG | SUFFIX(4)
     *
     * DATA_LEN = len(PAYLOAD) + len(AUTH_TAG) + 4 (suffix)
     *
     * @param authTag Either a 4-byte CRC32 or a 16-byte HMAC-SHA256 slice.
     */
    private fun assembleFrame(cmd: Int, payload: ByteArray, authTag: ByteArray): ByteArray {
        val seq = seqNo.getAndIncrement()
        val dataLen = payload.size + authTag.size + 4  // +4 for SUFFIX

        val buf = ByteBuffer.allocate(PREFIX.size + 4 + 4 + 4 + payload.size + authTag.size + SUFFIX.size)
        buf.put(PREFIX)
        buf.putInt(seq)
        buf.putInt(cmd)
        buf.putInt(dataLen)
        buf.put(payload)
        buf.put(authTag)
        buf.put(SUFFIX)
        return buf.array()
    }

    // ── Auth tags ─────────────────────────────────────────────────────────────

    /** 4-byte CRC32 over the payload bytes (not over the full frame header). */
    private fun crc32Tag(payload: ByteArray): ByteArray {
        val crc = CRC32().also { it.update(payload) }
        return ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
    }

    /** First 16 bytes of HMAC-SHA256(key, payload). */
    private fun hmacTag(key: ByteArray, payload: ByteArray): ByteArray =
        hmacSha256(key, payload).copyOf(16)

    // ── Crypto helpers ────────────────────────────────────────────────────────

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
