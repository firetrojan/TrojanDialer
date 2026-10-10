package com.communicator.data.core.encrypted

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.communicator.data.core.model.EncryptionState
import com.communicator.data.core.model.VerificationState
import com.communicator.data.core.model.DeliveryState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
@Entity(
    tableName = "encrypted_identities",
    indices = [
        Index("identityKeyId"),
        Index("contactId"),
        Index("isVerified")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class EncryptedIdentityEntity(
    @PrimaryKey
    val identityKeyId: String,
    val contactId: String,
    val identityKey: String, // Base64 public key
    val deviceKeys: String, // JSON array of base64 public keys
    val protocol: String, // CryptoProtocol name
    val createdAt: Long = System.currentTimeMillis(),
    val isVerified: Boolean = false,
    val verificationState: VerificationState = VerificationState.UNVERIFIED,
    val safetyNumber: String? = null,
    val lastVerifiedAt: Long? = null,
    val keyRotationAt: Long? = null
)

@Serializable
@Entity(
    tableName = "encrypted_messages",
    indices = [
        Index("messageId"),
        Index("threadId"),
        Index("senderId"),
        Index("recipientId"),
        Index("timestamp"),
        Index("deliveryState"),
        Index("encryptionState"),
        Index("subscriptionId")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class EncryptedMessageEntity(
    @PrimaryKey
    val messageId: String,
    val threadId: String,
    val senderId: String,
    val recipientId: String,
    val encryptedContent: String,
    val attachmentMetadata: String, // JSON string
    val encryptionState: EncryptionState = EncryptionState.NONE,
    val protocol: String = "MLS", // CryptoProtocol name
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    val subscriptionId: Int? = null
)

@Serializable
@Entity(
    tableName = "encrypted_threads",
    indices = [
        Index("threadId"),
        Index("lastMessageTimestamp"),
        Index("participant"),
        Index("unreadCount")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class EncryptedThreadEntity(
    @PrimaryKey
    val threadId: String,
    val participant: String,
    val contactId: String? = null,
    val lastMessageId: String? = null,
    val lastMessageContent: String? = null,
    val lastMessageTimestamp: Long = 0,
    val unreadCount: Int = 0,
    val isArchived: Boolean = false,
    val isSpam: Boolean = false,
    val transportId: String = "encrypted-mls",
    val subscriptionId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "encrypted_parts",
    indices = [
        Index("messageId"),
        // The column is `contentType`; `mimeType` never existed on this entity,
        // so Room could not resolve the index and refused to build.
        Index("contentType"),
        Index("contentId")
    ]
)
data class EncryptedPartEntity(
    @PrimaryKey
    val partId: String,
    val messageId: String,
    val contentType: String,
    val contentUri: String? = null,
    val filename: String? = null,
    val size: Long = 0,
    val isEncrypted: Boolean = true,
    val contentId: String? = null,
    val contentLocation: String? = null
)

class EncryptedConverters {
    // One Json instance serves every converter here. The previous version also
    // held a Gson instance, but nothing imported com.google.gson and no gson
    // dependency was declared, so the type could not be resolved and Room's
    // KSP pass failed. Using the already-present kotlinx.serialization Json
    // removes that unresolvable reference without adding a dependency.
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromVerificationState(value: String?): VerificationState? {
        return value?.let { VerificationState.valueOf(it) }
    }

    @TypeConverter
    fun toVerificationState(state: VerificationState?): String? {
        return state?.name
    }

    @TypeConverter
    fun fromEncryptionState(value: String?): EncryptionState? {
        return value?.let { EncryptionState.valueOf(it) }
    }

    @TypeConverter
    fun toEncryptionState(state: EncryptionState?): String? {
        return state?.name
    }

    @TypeConverter
    fun fromDeliveryState(value: String?): DeliveryState? {
        return value?.let { DeliveryState.valueOf(it) }
    }

    @TypeConverter
    fun toDeliveryState(state: DeliveryState?): String? {
        return state?.name
    }

    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toStringList(stored: String?): List<String>? {
        return stored?.let { json.decodeFromString(it) }
    }

    @TypeConverter
    fun fromAttachmentList(list: List<com.communicator.data.core.model.Attachment>?): String? {
        return list?.let { json.encodeToString(it) }
    }

    @TypeConverter
    fun toAttachmentList(stored: String?): List<com.communicator.data.core.model.Attachment>? {
        return stored?.let { json.decodeFromString(it) }
    }
}
