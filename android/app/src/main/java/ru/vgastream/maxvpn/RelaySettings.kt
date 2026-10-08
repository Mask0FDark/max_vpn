package ru.vgastream.maxvpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Store a direct-VPS pairing key encrypted in Android Keystore; never log it. */
object RelaySettings {
    private const val ALIAS = "maxvpn-private-relay-key-v1"
    private const val PREFS = "vpn-private-settings"
    private const val KEY = "relay-aes-gcm"

    private fun secret(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keystore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Exchange an owner-issued one-time code over verified HTTPS. Never log it. */
    fun pair(context: Context, rawCode: String) {
        val code = rawCode.trim().lowercase()
        require(Regex("[0-9a-f]{24}").matches(code)) { "Invalid pairing code" }
        val requestBody = JSONObject().put("code", code).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder()
                .url("https://max-vpn.mask-0f-darkness.ru/api/pair")
                .post(requestBody).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Pairing was rejected" }
                val json = JSONObject(response.body?.string() ?: "")
                check(json.optString("mode") == "direct_vps_https")
                val token = json.getString("token")
                save(context, token)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    fun save(context: Context, token: String) {
        require(token.length in 40..128 && token.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "Invalid private pairing key"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val data = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.US_ASCII))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): String {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return ""
        try {
            val data = Base64.decode(encrypted, Base64.NO_WRAP)
            if (data.size < 28) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.US_ASCII)
        } catch (_: Exception) {
            return ""
        }
    }
}
