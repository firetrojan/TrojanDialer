package com.communicator.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for MLS private key material (HPKE init/encryption keys and
 * signature keys) using the Android Keystore, mirroring SipCredentialManager.
 *
 * Stored values are the raw serializations produced by Bouncy Castle
 * (HPKE.serializePrivateKey / MlsCipherSuite.serializeSignaturePrivateKey).
 * Nothing is stored in plaintext: every value is AES-GCM sealed under a
 * Keystore-resident master key. Room rows only reference keys by alias.
 */
object MlsKeyStore {

    private const val MASTER_KEY_ALIAS = "mls_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PREFS = "mls_key_store"

    private const val KEY_PREFIX = "mls_key_"

    @Volatile
    private var masterKeyAlias: String? = null

    /** Initialize the Keystore master key. Call once at app start. */
    fun initialize(context: Context) {
        try {
            masterKeyAlias = MasterKeys.getOrCreate(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        } catch (e: Exception) {
            initializeFallback()
        }
    }

    private fun initializeFallback() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            keyGenerator.generateKey()
        }
        masterKeyAlias = MASTER_KEY_ALIAS
    }

    private fun requireMaster(): String =
        masterKeyAlias ?: throw IllegalStateException("MlsKeyStore not initialized")

    private fun secretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as SecretKey
    }

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            PREFS,
            requireMaster(),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /**
     * Seals [rawKey] under the Keystore master key and stores it under [alias].
     * Returns the alias so callers can persist the reference.
     */
    suspend fun storeKey(context: Context, alias: String, rawKey: ByteArray): String =
        withContext(Dispatchers.IO) {
            val key = secretKey(requireMaster())
            val cipher = Cipher.getInstance(AES_GCM)
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val sealed = cipher.doFinal(rawKey)

            val out = ByteArray(iv.size + sealed.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(sealed, 0, out, iv.size, sealed.size)

            prefs(context).edit()
                .putString(KEY_PREFIX + alias, Base64.encodeToString(out, Base64.NO_WRAP))
                .apply()
            alias
        }

    /** Opens the key stored under [alias]; null if absent. */
    suspend fun loadKey(context: Context, alias: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val stored = prefs(context).getString(KEY_PREFIX + alias, null) ?: return@withContext null
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, 12)
            val sealed = data.copyOfRange(12, data.size)

            val key = secretKey(requireMaster())
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.doFinal(sealed)
        }

    suspend fun deleteKey(context: Context, alias: String) = withContext(Dispatchers.IO) {
        prefs(context).edit().remove(KEY_PREFIX + alias).apply()
        Unit
    }
}
