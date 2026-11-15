package com.aniq.orbit.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.aniq.orbit.Prefs
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted with an AES key that lives in the tablet's hardware-backed Android
 * Keystore. The plain key is never written to disk and is only sent to its own provider.
 */
object KeyVault {
    private const val ALIAS = "orbit_ai_keys"

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun pref(p: AiProvider) = "ai_key_" + p.id

    fun save(ctx: Context, p: AiProvider, key: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        val ct = c.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        Prefs.of(ctx).edit().putString(pref(p), packed).apply()
    }

    fun load(ctx: Context, p: AiProvider): String? {
        val packed = Prefs.of(ctx).getString(pref(p), null) ?: return null
        return try {
            val (iv, ct) = packed.split(':').let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun has(ctx: Context, p: AiProvider) = Prefs.of(ctx).contains(pref(p))

    fun remove(ctx: Context, p: AiProvider) = Prefs.of(ctx).edit().remove(pref(p)).apply()

    /** "••••abcd" so you can tell which key is saved without showing it. */
    fun hint(ctx: Context, p: AiProvider): String? = load(ctx, p)?.takeLast(4)?.let { "••••$it" }
}
