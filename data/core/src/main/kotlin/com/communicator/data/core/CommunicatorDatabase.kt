package com.communicator.data.core

import androidx.room.Database
import androidx.room.RoomDatabase
import com.communicator.data.core.encrypted.EncryptedIdentityEntity
import com.communicator.data.core.encrypted.EncryptedMessageEntity
import com.communicator.data.core.encrypted.EncryptedThreadEntity
import com.communicator.data.core.encrypted.EncryptedPartEntity
import com.communicator.data.core.encrypted.MlsGroupEntity
import com.communicator.data.core.encrypted.MlsGroupMessageEntity
import com.communicator.data.core.encrypted.MlsPendingSendEntity
import com.communicator.data.core.encrypted.IdentityVerificationDao
import com.communicator.data.core.encrypted.IdentityVerificationEntity
import com.communicator.data.core.encrypted.VerificationEventDao
import com.communicator.data.core.encrypted.VerificationEventEntity
import com.communicator.data.core.encrypted.QrVerificationPayloadDao
import com.communicator.data.core.encrypted.QrVerificationPayloadEntity
import com.communicator.data.core.encrypted.SafetyNumberDao
import com.communicator.data.core.encrypted.SafetyNumberEntity
import com.communicator.data.core.encrypted.MlsGroupDao
import com.communicator.data.core.encrypted.MlsGroupMessageDao
import com.communicator.data.core.encrypted.MlsPendingSendDao
import com.communicator.data.core.mms.MmsMessageEntity
import com.communicator.data.core.mms.MmsMessageDao
import com.communicator.data.core.mms.MmsPartDao
import com.communicator.data.core.mms.MmsPartEntity
import com.communicator.data.core.mms.MmsThreadEntity
import com.communicator.data.core.mms.MmsThreadDao
import com.communicator.data.core.sip.SipAccountDao
import com.communicator.data.core.sip.SipAccountEntity
import com.communicator.data.core.sip.SipCallDao
import com.communicator.data.core.sip.SipCallEntity
import com.communicator.data.core.sip.SipMessageDao
import com.communicator.data.core.sip.SipMessageEntity
import com.communicator.data.core.sms.ConversationEntity
import com.communicator.data.core.sms.ConversationDao
import com.communicator.data.core.sms.SmsMessageDao
import com.communicator.data.core.sms.SmsMessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        SmsMessageEntity::class,
        MmsThreadEntity::class,
        MmsMessageEntity::class,
        MmsPartEntity::class,
        SipAccountEntity::class,
        SipCallEntity::class,
        SipMessageEntity::class,
        EncryptedIdentityEntity::class,
        EncryptedMessageEntity::class,
        EncryptedThreadEntity::class,
        EncryptedPartEntity::class,
        MlsGroupEntity::class,
        MlsGroupMessageEntity::class,
        MlsPendingSendEntity::class,
        IdentityVerificationEntity::class,
        VerificationEventEntity::class,
        QrVerificationPayloadEntity::class,
        SafetyNumberEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class CommunicatorDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun smsMessageDao(): SmsMessageDao
    abstract fun mmsThreadDao(): MmsThreadDao
    abstract fun mmsMessageDao(): MmsMessageDao
    abstract fun mmsPartDao(): MmsPartDao
    abstract fun sipAccountDao(): SipAccountDao
    abstract fun sipCallDao(): SipCallDao
    abstract fun sipMessageDao(): SipMessageDao
    // NOTE: four accessors here used to return EncryptedIdentityDao,
    // EncryptedMessageDao, EncryptedThreadDao and EncryptedPartDao. None of
    // those DAO interfaces was ever declared in the repository, so Room could
    // not generate the database and KSP failed with [MissingType]. No caller
    // referenced them, so they were removed rather than invented. The matching
    // Encrypted*Entity classes are likewise still declared but unused.
    abstract fun mlsGroupDao(): MlsGroupDao
    abstract fun mlsGroupMessageDao(): MlsGroupMessageDao
    abstract fun mlsPendingSendDao(): MlsPendingSendDao
    abstract fun identityVerificationDao(): IdentityVerificationDao
    abstract fun verificationEventDao(): VerificationEventDao
    abstract fun qrVerificationPayloadDao(): QrVerificationPayloadDao
    abstract fun safetyNumberDao(): SafetyNumberDao

    companion object {
        private const val DATABASE_NAME = "communicator_database"
    }
}
