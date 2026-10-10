package com.communicator.di

import android.content.Context
import com.communicator.app.SmsRepository
import com.communicator.communication.encrypted.KeyVault
import com.communicator.communication.encrypted.MlsGroupManager
import com.communicator.data.core.CommunicatorDatabase
import com.communicator.data.security.MlsKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Stage 5D durable-delivery manager and the SMS repository.
 *
 * [MlsGroupManager] receives all four identity-verification DAOs. They were
 * previously optional (nullable) because no construction site supplied them and
 * every verification entry point failed closed. Supplying them here is what
 * makes identity verification reachable at runtime; the fail-closed guards
 * inside MlsGroupManager remain as a safety net.
 *
 * No provider here returns a no-op or partial object. If a dependency cannot be
 * constructed, the graph fails at startup rather than silently degrading.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Production [KeyVault] over the Android Keystore-backed [MlsKeyStore].
     * Only the storage location is substitutable; the MLS cryptography is never
     * replaced.
     */
    @Provides
    @Singleton
    fun provideKeyVault(@ApplicationContext context: Context): KeyVault = object : KeyVault {
        override suspend fun store(alias: String, rawKey: ByteArray) {
            MlsKeyStore.storeKey(context, alias, rawKey)
        }

        override suspend fun load(alias: String): ByteArray? =
            MlsKeyStore.loadKey(context, alias)

        override suspend fun delete(alias: String) {
            MlsKeyStore.deleteKey(context, alias)
        }
    }

    @Provides
    @Singleton
    fun provideSubscriptionManager(
        @ApplicationContext context: Context
    ): android.telephony.SubscriptionManager =
        context.getSystemService(android.telephony.SubscriptionManager::class.java)

    @Provides
    @Singleton
    fun provideMlsGroupManager(
        @ApplicationContext context: Context,
        db: CommunicatorDatabase,
        vault: KeyVault
    ): MlsGroupManager = MlsGroupManager(
        context = context,
        groupDao = db.mlsGroupDao(),
        messageDao = db.mlsGroupMessageDao(),
        pendingSendDao = db.mlsPendingSendDao(),
        keyVault = vault,
        identityVerificationDao = db.identityVerificationDao(),
        verificationEventDao = db.verificationEventDao(),
        qrVerificationPayloadDao = db.qrVerificationPayloadDao(),
        safetyNumberDao = db.safetyNumberDao()
    )

    @Provides
    @Singleton
    fun provideSmsRepository(
        @ApplicationContext context: Context,
        db: CommunicatorDatabase,
        subscriptionManager: android.telephony.SubscriptionManager
    ): SmsRepository = SmsRepository(
        context = context,
        conversationDao = db.conversationDao(),
        messageDao = db.smsMessageDao(),
        subscriptionManager = subscriptionManager
    )
}
