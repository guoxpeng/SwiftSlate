package com.musheer360.swiftslate.manager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypt/decrypt seam for the stored API-key blob.
 *
 * Extracted from [KeyManager] so its storage, caching and rotation logic can actually be tested:
 * AndroidKeyStore's AES-GCM provider is HAL-backed and not implemented under Robolectric, so 11
 * of KeyManagerTest's 17 cases used to end in an `Assume` skip. The suite reported green while
 * the security-critical path was never exercised.
 */
internal interface KeyCipher {
    /** False when the backing key could not be created or loaded. */
    val available: Boolean

    /** @throws Exception when the key is unavailable or encryption fails. */
    fun encrypt(plainText: String): String

    /** Null when [encrypted] is not decryptable: wrong key, corrupt, or not this format. */
    fun decrypt(encrypted: String): String?
}

/**
 * AES-256-GCM through a non-exportable AndroidKeyStore key.
 *
 * The key is the single point of failure for every stored API key, so it must survive the two
 * ways AndroidKeyStore silently breaks a key:
 *
 * 1. **Uninstall does not reliably delete the key.** On many devices (especially TEE-backed
 *    keystores) the entry survives `adb uninstall`/Play uninstall but is bound to the old app
 *    UID. `containsAlias()` then reports true while every operation throws
 *    `KeyPermanentlyInvalidatedException` — and the old code trusted `containsAlias()`, so after
 *    a reinstall the key could never be generated again and no new API key could ever be saved.
 * 2. **Lock-screen changes invalidate user-auth-bound keys.** The previous
 *    `setUnlockedDeviceRequired(true)` bound the key to the current lock screen, so changing or
 *    removing the PIN/pattern permanently invalidated it, again with no regeneration path.
 *
 * Both are fixed by never trusting a key until it has been exercised, and by regenerating on
 * failure. The old encrypted blob is unrecoverable in those cases anyway, so dropping it loses
 * nothing — the user simply re-adds their keys, which is exactly what the UI already tells them.
 */
internal class AndroidKeystoreCipher : KeyCipher {

    private companion object {
        const val KEY_ALIAS = "typeslate_secure_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        /**
         * Separates the base64 IV from the base64 ciphertext. Safe because base64 (NO_WRAP)
         * emits only A-Za-z0-9+/= — see [KeyManager] for why detecting the *legacy* plaintext
         * format by the absence of this character was not safe.
         */
        const val IV_SEPARATOR = "]"
    }

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    override var available: Boolean = true
        private set

    init {
        try {
            loadOrCreateKey()
        } catch (e: Exception) {
            android.util.Log.e("KeyCipher", "Keystore init failed", e)
            available = false
        }
    }

    /**
     * Loads the key from AndroidKeyStore, generating it first if missing, and regenerating it if
     * the existing entry is stale or invalidated. A key is only trusted once it can complete a
     * real encrypt, because a stale post-uninstall key loads fine and only fails on use.
     */
    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existing = if (keyStore.containsAlias(KEY_ALIAS)) {
            try {
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        if (existing != null && isUsable(existing)) {
            cachedSecretKey = existing
            return existing
        }

        // Missing, stale, or invalidated: drop it (best effort) and mint a fresh key.
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Some builds throw when deleting an invalidated key; continue and generate anyway.
        }
        return generateKey(keyStore)
    }

    private fun generateKey(keyStore: KeyStore): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NO setUnlockedDeviceRequired(true): binding the key to the current
                // lock screen means changing/removing the PIN permanently invalidates it and
                // bricks key storage. The real protection is the non-exportable TEE-backed key
                // itself; the accessibility service only decrypts while the user is typing, so
                // the unlocked-device requirement added fragility without meaningful security.
                .build()
        )
        keyGenerator.generateKey()
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Keystore key missing after generation")
        cachedSecretKey = key
        return key
    }

    /** True when [key] can complete a real operation — catches stale and invalidated keys. */
    private fun isUsable(key: SecretKey): Boolean = try {
        val probe = Cipher.getInstance(TRANSFORMATION)
        probe.init(Cipher.ENCRYPT_MODE, key)
        probe.doFinal(ByteArray(16))
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    private fun secretKey(): SecretKey? {
        cachedSecretKey?.let { return it }
        return try {
            loadOrCreateKey()
        } catch (e: Exception) {
            android.util.Log.e("KeyCipher", "Failed to get secret key", e)
            available = false
            null
        }
    }

    @Synchronized
    override fun encrypt(plainText: String): String {
        val key = secretKey() ?: throw IllegalStateException("Keystore unavailable")
        return try {
            doEncrypt(key, plainText)
        } catch (e: Exception) {
            // The cached key can be invalidated after load (lock-screen change, restored backup).
            // Regenerate once and retry; the old encrypted blob is unrecoverable in that case anyway.
            cachedSecretKey = null
            val fresh = secretKey() ?: throw IllegalStateException("Keystore unavailable")
            try {
                doEncrypt(fresh, plainText)
            } catch (e2: Exception) {
                android.util.Log.e("KeyCipher", "Encrypt failed after regeneration", e2)
                available = false
                throw e2
            }
        }
    }

    private fun doEncrypt(key: SecretKey, plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(
            cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$iv$IV_SEPARATOR$body"
    }

    @Synchronized
    override fun decrypt(encrypted: String): String? {
        val parts = encrypted.split(IV_SEPARATOR)
        if (parts.size != 2) return null
        return try {
            doDecrypt(secretKey() ?: return null, parts)
        } catch (_: Exception) {
            null
        }
    }

    private fun doDecrypt(key: SecretKey, parts: List<String>): String {
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(body), StandardCharsets.UTF_8)
    }
}
