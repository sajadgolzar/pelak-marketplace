package com.pelakauto.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Session tokens are encrypted with a device-local Android Keystore AES key. No plaintext JWT in preferences. */
internal class PelakSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pelak_native_secure", Context.MODE_PRIVATE)
    private val alias = "pelak_native_session_aes_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun save(session: PelakSession) {
        val data = JSONObject().put("access", session.accessToken)
            .put("refresh", session.refreshToken).put("id", session.userId)
            .put("email", session.email).put("expiry", session.expiresAtMs).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        preferences.edit().putString("session", encoded).apply()
    }
    fun load(): PelakSession? {
        val encoded = preferences.getString("session", null) ?: return null
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            require(raw.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            val info = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
            PelakSession(info.getString("access"), info.getString("id"), info.getString("email"),
                info.getString("refresh"), info.getLong("expiry"))
        } catch (_: Exception) {
            clear()
            null
        }
    }
    fun clear() { preferences.edit().remove("session").apply() }
}
