package com.communicator.di

import android.content.Context
import androidx.room.Room
import com.communicator.data.core.CommunicatorDatabase
import com.communicator.data.core.encrypted.IdentityVerificationDao
import com.communicator.data.core.encrypted.MlsGroupDao
import com.communicator.data.core.encrypted.MlsGroupMessageDao
import com.communicator.data.core.encrypted.MlsPendingSendDao
import com.communicator.data.core.encrypted.QrVerificationPayloadDao
import com.communicator.data.core.encrypted.SafetyNumberDao
import com.communicator.data.core.encrypted.VerificationEventDao
import com.communicator.data.core.mms.MmsMessageDao
import com.communicator.data.core.mms.MmsPartDao
import com.communicator.data.core.mms.MmsThreadDao
import com.communicator.data.core.sip.SipAccountDao
import com.communicator.data.core.sip.SipCallDao
import com.communicator.data.core.sip.SipMessageDao
import com.communicator.data.core.sms.ConversationDao
import com.communicator.data.core.sms.SmsMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the single [CommunicatorDatabase] instance and its DAOs.
 *
 * Migration policy: there is deliberately NO fallbackToDestructiveMigration and
 * no fallbackToDestructiveMigrationOnDowngrade. The database is at version 3 and
 * no Migration objects exist, so an upgrade from an older installed schema
 * FAILS LOUDLY rather than silently deleting a user's messages. That failure is
 * the correct behaviour until real migrations are written; see the feature
 * audit for the exact limitation and recovery path.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "communicator_database"

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CommunicatorDatabase =
        Room.databaseBuilder(context, CommunicatorDatabase::class.java, DATABASE_NAME)
            .build()

    @Provides
    fun provideConversationDao(db: CommunicatorDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideSmsMessageDao(db: CommunicatorDatabase): SmsMessageDao = db.smsMessageDao()

    @Provides
    fun provideMmsThreadDao(db: CommunicatorDatabase): MmsThreadDao = db.mmsThreadDao()

    @Provides
    fun provideMmsMessageDao(db: CommunicatorDatabase): MmsMessageDao = db.mmsMessageDao()

    @Provides
    fun provideMmsPartDao(db: CommunicatorDatabase): MmsPartDao = db.mmsPartDao()

    @Provides
    fun provideSipAccountDao(db: CommunicatorDatabase): SipAccountDao = db.sipAccountDao()

    @Provides
    fun provideSipCallDao(db: CommunicatorDatabase): SipCallDao = db.sipCallDao()

    @Provides
    fun provideSipMessageDao(db: CommunicatorDatabase): SipMessageDao = db.sipMessageDao()

    @Provides
    fun provideMlsGroupDao(db: CommunicatorDatabase): MlsGroupDao = db.mlsGroupDao()

    @Provides
    fun provideMlsGroupMessageDao(db: CommunicatorDatabase): MlsGroupMessageDao =
        db.mlsGroupMessageDao()

    @Provides
    fun provideMlsPendingSendDao(db: CommunicatorDatabase): MlsPendingSendDao =
        db.mlsPendingSendDao()

    @Provides
    fun provideIdentityVerificationDao(db: CommunicatorDatabase): IdentityVerificationDao =
        db.identityVerificationDao()

    @Provides
    fun provideVerificationEventDao(db: CommunicatorDatabase): VerificationEventDao =
        db.verificationEventDao()

    @Provides
    fun provideQrVerificationPayloadDao(db: CommunicatorDatabase): QrVerificationPayloadDao =
        db.qrVerificationPayloadDao()

    @Provides
    fun provideSafetyNumberDao(db: CommunicatorDatabase): SafetyNumberDao = db.safetyNumberDao()
}
