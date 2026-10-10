package com.communicator.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.communicator.data.core.sip.SipAccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.Key
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SipCredentialManager {

    private const val ALIAS_PREFIX = "sip_account_"
    private const val MASTER_KEY_ALIAS = "sip_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private var masterKeyAlias: String? = null

    /**
     * Initialize the master key for SIP credential encryption.
     * Should be called during application startup.
     */
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
            // Fallback to AES-GCM with Android Keystore
            initializeFallback(context)
        }
    }

    private fun initializeFallback(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

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
        } catch (e: Exception) {
            throw IllegalStateException("Failed to initialize SIP credential encryption", e)
        }
    }

    /**
     * Encrypts a SIP account password using Android Keystore-backed encryption.
     */
    suspend fun encryptPassword(password: String): String = withContext(Dispatchers.IO) {
        val masterAlias = masterKeyAlias ?: throw IllegalStateException("SipCredentialManager not initialized")
        val key = getSecretKey(masterAlias)

        val cipher = Cipher.getInstance(AES_GCM)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val plaintext = password.toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext for storage
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

        Base64.encodeToString(result, Base64.NO_WRAP)
    }

    /**
     * Decrypts a SIP account password.
     */
    suspend fun decryptPassword(encryptedBase64: String): String = withContext(Dispatchers.IO) {
        val masterAlias = masterKeyAlias ?: throw IllegalStateException("SipCredentialManager not initialized")
        val key = getSecretKey(masterAlias)

        val data = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)

        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val plaintext = cipher.doFinal(ciphertext)
        String(plaintext, StandardCharsets.UTF_8)
    }

    /**
     * Gets the master secret key from Android Keystore.
     */
    private fun getSecretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as SecretKey
    }

    /**
     * Encrypts all sensitive fields of a SIP account.
     */
    suspend fun encryptAccount(account: SipAccountEntity): SipAccountEntity = withContext(Dispatchers.IO) {
        if (account.encryptedPassword.isNotEmpty() && !account.encryptedPassword.startsWith("enc:")) {
            val encrypted = encryptPassword(account.encryptedPassword)
            account.copy(
                encryptedPassword = "enc:$encrypted",
                updatedAt = System.currentTimeMillis()
            )
        } else {
            account
        }
    }

    /**
     * Decrypts all sensitive fields of a SIP account.
     */
    suspend fun decryptAccount(account: SipAccountEntity): SipAccountEntity = withContext(Dispatchers.IO) {
        if (account.encryptedPassword.startsWith("enc:")) {
            val encrypted = account.encryptedPassword.substringAfter("enc:")
            val decrypted = decryptPassword(encrypted)
            account.copy(
                encryptedPassword = decrypted
            )
        } else {
            account
        }
    }
}
