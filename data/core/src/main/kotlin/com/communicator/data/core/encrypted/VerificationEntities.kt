package com.communicator.data.core.encrypted

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.communicator.data.core.model.VerificationState
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "identity_verifications",
    indices = [
        Index("identityKeyId"),
        Index("peerIdentityKeyId"),
        Index("verificationState"),
        Index("fingerprint")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class IdentityVerificationEntity(
    @PrimaryKey
    val verificationId: String,
    val identityKeyId: String,
    val peerIdentityKeyId: String,
    val fingerprint: String,
    val peerFingerprint: String,
    val verificationState: VerificationState = VerificationState.UNVERIFIED,
    val trustLevel: TrustLevel = TrustLevel.UNTRUSTED,
    val verifiedAt: Long? = null,
    val lastCheckedAt: Long? = null,
    val keyRotationAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
enum class TrustLevel {
    UNTRUSTED,
    TOFU,
    VERIFIED,
    REVOKED,
    COMPROMISED
}

@Serializable
@Entity(
    tableName = "verification_events",
    indices = [
        Index("verificationId"),
        Index("timestamp"),
        Index("eventType")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class VerificationEventEntity(
    @PrimaryKey(autoGenerate = true)
    val eventId: Long = 0,
    val verificationId: String,
    val eventType: VerificationEventType,
    val fingerprint: String,
    val previousFingerprint: String? = null,
    val details: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class VerificationEventType {
    TOFU_INITIAL,
    VERIFIED,
    FINGERPRINT_CHANGED,
    REVOKED,
    EXPIRED,
    QR_SCANNED,
    SAFETY_NUMBER_COMPARED,
    KEY_ROTATION_DETECTED,
    IDENTITY_CHANGED
}

@Serializable
@Entity(
    tableName = "qr_verification_payloads",
    indices = [
        Index("payloadId"),
        Index("identityKeyId"),
        // This entity has no `createdAt` column; the timestamp column is
        // `timestamp`. Room could not resolve the index and refused to build.
        Index("timestamp")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class QrVerificationPayloadEntity(
    @PrimaryKey
    val payloadId: String,
    val identityKeyId: String,
    val version: Int,
    val protocol: String,
    val identityKeyIdBase64: String,
    val fingerprint: String,
    val signaturePublicKeyBase64: String,
    val encryptionPublicKeyBase64: String,
    val credentialDisplayName: String,
    val keyPackageBase64: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val nonce: String
)

@Serializable
@Entity(
    tableName = "safety_numbers",
    indices = [
        Index("safetyNumberId"),
        Index("identityKeyId"),
        Index("peerIdentityKeyId")
    ]
)
@TypeConverters(EncryptedConverters::class)
data class SafetyNumberEntity(
    @PrimaryKey
    val safetyNumberId: String,
    val identityKeyId: String,
    val peerIdentityKeyId: String,
    val safetyNumber: String,
    val verifiedAt: Long? = null,
    val verificationState: VerificationState = VerificationState.UNVERIFIED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
