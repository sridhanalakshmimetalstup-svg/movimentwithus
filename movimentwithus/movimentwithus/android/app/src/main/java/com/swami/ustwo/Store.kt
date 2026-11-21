package com.swami.ustwo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

data class Config(val owner: String, val repo: String, val token: String, val name: String)

/**
 * Local settings. The GitHub token is encrypted with a hardware-backed Android Keystore key;
 * the PIN is only stored as a salted PBKDF2 hash.
 */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ustwo", Context.MODE_PRIVATE)

    val isConfigured: Boolean get() = prefs.contains("token_enc")

    val config: Config?
        get() {
            val owner = prefs.getString("owner", null) ?: return null
            val repo = prefs.getString("repo", null) ?: return null
            val name = prefs.getString("name", null) ?: return null
            val enc = prefs.getString("token_enc", null) ?: return null
            val token = runCatching { decrypt(enc) }.getOrNull() ?: return null
            return Config(owner, repo, token, name)
        }

    fun saveConfig(c: Config) {
        prefs.edit()
            .putString("owner", c.owner)
            .putString("repo", c.repo)
            .putString("name", c.name)
            .putString("token_enc", encrypt(c.token))
            .commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    // ---- feed cache ----

    var feed: List<Post>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("feed", "[]"))
            (0 until arr.length()).map { Post.from(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(value) {
            val arr = JSONArray()
            value.forEach { arr.put(it.toJson()) }
            prefs.edit().putString("feed", arr.toString()).commit()
        }

    var latestPath: String?
        get() = prefs.getString("latest_path", null)
        set(value) {
            prefs.edit().putString("latest_path", value).commit()
        }

    val latestPost: Post?
        get() {
            val path = latestPath ?: return null
            return feed.firstOrNull { it.path == path }
        }

    // ---- PIN ----

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("pin_salt", b64(salt))
            .putString("pin_hash", b64(hashPin(pin, salt)))
            .putInt("pin_fails", 0)
            .putLong("pin_locked_until", 0L)
            .commit()
    }

    fun checkPin(pin: String): Boolean {
        val salt = unb64(prefs.getString("pin_salt", null) ?: return false)
        val expected = unb64(prefs.getString("pin_hash", null) ?: return false)
        return MessageDigest.isEqual(hashPin(pin, salt), expected)
    }

    /** Milliseconds until another attempt is allowed (0 = allowed now). */
    fun lockoutRemainingMs(): Long =
        (prefs.getLong("pin_locked_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun recordFailure() {
        val fails = prefs.getInt("pin_fails", 0) + 1
        val editor = prefs.edit().putInt("pin_fails", fails)
        if (fails >= 5) {
            // 30s, 60s, 2m, 4m ... capped at 1 hour
            val delay = min(30_000L shl min(fails - 5, 7), 3_600_000L)
            editor.putLong("pin_locked_until", System.currentTimeMillis() + delay)
        }
        editor.commit()
    }

    fun resetFailures() {
        prefs.edit().putInt("pin_fails", 0).putLong("pin_locked_until", 0L).commit()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 40_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    // ---- token encryption (Android Keystore, AES-GCM) ----

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
        return String(cipher.doFinal(unb64(parts[1])), Charsets.UTF_8)
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val KEY_ALIAS = "ustwo_token_key"
    }
}
