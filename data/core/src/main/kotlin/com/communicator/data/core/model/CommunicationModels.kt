package com.communicator.data.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Represents a communication endpoint for a contact.
 * This is the abstraction layer between contacts and transports.
 */
@Serializable
data class CommunicationEndpoint(
    val endpointId: String,
    val contactId: String,
    val transportId: String,
    val address: String,
    val capabilities: TransportCapabilities = TransportCapabilities(),
    val availability: EndpointAvailability = EndpointAvailability.UNKNOWN,
    val verificationState: VerificationState = VerificationState.UNVERIFIED,
    val priority: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    fun supports(capability: Capability): Boolean = capabilities.supports(capability)
    fun isAvailable(): Boolean = availability == EndpointAvailability.AVAILABLE
}

/**
 * Availability state of an endpoint.
 */
enum class EndpointAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
    BUSY,
    OFFLINE,
    DO_NOT_DISTURB
}

/**
 * Verification state for encrypted communications.
 */
enum class VerificationState {
    UNVERIFIED,
    VERIFIED,
    VERIFICATION_FAILED,
    PENDING,
    CHANGED,
    REVOKED
}

/**
 * Capabilities that a transport or endpoint can support.
 */
@Serializable
data class TransportCapabilities(
    val capabilities: Set<Capability> = emptySet()
) {
    fun supports(capability: Capability): Boolean = capability in capabilities
    fun supportsAll(vararg capabilities: Capability): Boolean = capabilities.all { this.supports(it) }
    fun supportsAny(vararg capabilities: Capability): Boolean = capabilities.any { this.supports(it) }

    operator fun plus(other: TransportCapabilities): TransportCapabilities {
        return TransportCapabilities(capabilities + other.capabilities)
    }

    operator fun minus(other: TransportCapabilities): TransportCapabilities {
        return TransportCapabilities(capabilities - other.capabilities)
    }

    companion object {
        val VOICE_ONLY = TransportCapabilities(setOf(Capability.VOICE))
        val VIDEO_ONLY = TransportCapabilities(setOf(Capability.VIDEO))
        val MESSAGING_ONLY = TransportCapabilities(setOf(Capability.MESSAGING))
        val FULL_DUPLEX = TransportCapabilities(setOf(Capability.VOICE, Capability.VIDEO, Capability.MESSAGING))
        val CARRIER_FULL = TransportCapabilities(
            setOf(
                Capability.VOICE, Capability.VIDEO, Capability.MESSAGING,
                Capability.SMS, Capability.MMS, Capability.CALL_HOLD,
                Capability.CALL_WAITING, Capability.CONFERENCE,
                Capability.CALL_TRANSFER, Capability.VOICE_TO_VIDEO,
                Capability.VIDEO_TO_VOICE, Capability.CARRIER
            )
        )
    }
}

/**
 * Individual capability flags.
 */
enum class Capability {
    VOICE,
    VIDEO,
    MESSAGING,
    SMS,
    MMS,
    RCS,
    FILE_TRANSFER,
    GROUP_CALL,
    GROUP_VIDEO,
    SCREEN_SHARE,
    CALL_HOLD,
    CALL_WAITING,
    CONFERENCE,
    CALL_TRANSFER,
    VOICE_TO_VIDEO,
    VIDEO_TO_VOICE,
    RECORDING,
    TRANSCRIPTION,
    END_TO_END_ENCRYPTION,
    OFFLINE,
    MESH,
    CARRIER,
    INTERNET,
    LOCAL_NETWORK
}

/**
 * Contact model - transport independent.
 */
@Serializable
data class Contact(
    val contactId: String,
    val androidContactId: Long? = null,
    val displayName: String,
    val phoneNumbers: List<PhoneNumber> = emptyList(),
    val emails: List<EmailAddress> = emptyList(),
    val sipUris: List<String> = emptyList(),
    val applicationIdentities: Map<String, String> = emptyMap(),
    val cryptographicIdentities: List<CryptographicIdentity> = emptyList(),
    val meshIdentities: List<String> = emptyList(),
    val preferredSim: Int? = null,
    val preferredTransport: String? = null,
    val isPrivate: Boolean = false,
    val isFavorite: Boolean = false,
    val isSpam: Boolean = false,
    val notes: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getEndpointsForTransport(transportId: String): List<CommunicationEndpoint> = emptyList()
    fun getPreferredEndpoint(): CommunicationEndpoint? = null
}

/**
 * Phone number with metadata.
 */
@Serializable
data class PhoneNumber(
    val number: String,
    val label: String = "mobile",
    val isPrimary: Boolean = false,
    val simSlot: Int? = null,
    val transportPreference: String? = null
) {
    val normalized: String get() = number.normalizePhoneNumber()
}

/**
 * Email address with metadata.
 */
@Serializable
data class EmailAddress(
    val address: String,
    val label: String = "home",
    val isPrimary: Boolean = false
)

/**
 * Cryptographic identity for E2E encryption.
 */
@Serializable
data class CryptographicIdentity(
    val identityKey: String,
    val deviceKeys: List<String> = emptyList(),
    val protocol: CryptoProtocol = CryptoProtocol.SIGNAL,
    val verifiedAt: Long? = null
)

enum class CryptoProtocol {
    SIGNAL,
    MLS,
    OMEMO,
    CUSTOM
}

/**
 * Call model.
 */
@Serializable
data class Call(
    val callId: String,
    val contactId: String?,
    val direction: CallDirection,
    val transportId: String,
    val subscriptionId: Int? = null,
    val phoneAccountHandle: String? = null,
    val isVideo: Boolean = false,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val duration: Long = 0,
    val status: CallStatus = CallStatus.INITIATING,
    val recordingId: String? = null,
    val transcriptId: String? = null,
    val notes: String = "",
    val metadata: Map<String, String> = emptyMap()
) {
    fun isActive(): Boolean = status in setOf(CallStatus.CONNECTING, CallStatus.ACTIVE, CallStatus.ON_HOLD)
    fun isEnded(): Boolean = status in setOf(CallStatus.ENDED, CallStatus.MISSED, CallStatus.REJECTED, CallStatus.FAILED, CallStatus.BLOCKED)
}

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallStatus {
    INITIATING,
    CONNECTING,
    ACTIVE,
    ON_HOLD,
    ENDED,
    MISSED,
    REJECTED,
    FAILED,
    BLOCKED
}

/**
 * Message model.
 */
@Serializable
data class Message(
    val messageId: String,
    val threadId: String,
    val transportId: String,
    val sender: String,
    val recipients: List<String>,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val encryptionState: EncryptionState = EncryptionState.NONE,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class Attachment(
    val attachmentId: String,
    val mimeType: String,
    val size: Long,
    val uri: String,
    val thumbnailUri: String? = null,
    val filename: String? = null
)

enum class EncryptionState {
    NONE,
    ENCRYPTED,
    ENCRYPTED_VERIFIED,
    FAILED
}

enum class DeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

fun String.normalizePhoneNumber(): String = replace(Regex("[^0-9+*#]"), "")
