package com.communicator.data.core.sip

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.communicator.data.core.sip.SipConverters
import kotlinx.serialization.Serializable

/**
 * Transport used to reach a SIP account.
 *
 * This enum exists here, in the persistence layer, because
 * [SipAccountEntity] persists the value as a column.
 *
 * The pre-existing definition is nested inside the `SipTransport` interface in
 * :communication:sip, which :data:core cannot reference: :communication:sip
 * already depends on :data:core, so using it here would create a project cycle.
 * That reference made :data:core uncompilable, which KSP reported as
 *   e: [ksp] [MissingType]: Element 'com.communicator.data.core.CommunicatorDatabase'
 *      references a type that is not present
 *
 * The three constants (UDP, TCP, TLS) are taken verbatim from the existing
 * nested enum so the persisted values stay identical. Room stores an enum by
 * name, so these strings are already what a database written against the
 * nested type would contain.
 */
enum class SipTransportType {
    UDP,
    TCP,
    TLS
}

@Serializable
@Entity(
    tableName = "sip_accounts",
    indices = [
        Index("username"),
        Index("domain"),
        Index("enabled"),
        Index("isDefault")
    ]
)
@TypeConverters(SipConverters::class)
data class SipAccountEntity(
    @PrimaryKey
    val accountId: String,
    val username: String,
    val encryptedPassword: String,
    val domain: String,
    val displayName: String? = null,
    val port: Int = 5060,
    val transportType: SipTransportType = SipTransportType.TLS,
    val proxy: String? = null,
    val outboundProxy: String? = null,
    val authUsername: String? = null,
    val enableSrtp: Boolean = true,
    val enableZrtp: Boolean = false,
    val enableDtlsSrtp: Boolean = true,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "sip_calls",
    indices = [
        Index("accountId"),
        Index("threadId"),
        Index("timestamp"),
        Index("state"),
        Index("direction")
    ]
)
@TypeConverters(SipConverters::class)
data class SipCallEntity(
    @PrimaryKey
    val callId: String,
    val accountId: String,
    val threadId: String,
    val remoteUri: String,
    val remoteDisplayName: String? = null,
    val direction: CallDirection,
    val state: CallState = CallState.INITIATING,
    val timestamp: Long = System.currentTimeMillis(),
    val startTime: Long? = null,
    val endTime: Long? = null,
    val duration: Long = 0,
    val isVideo: Boolean = false,
    val isOnHold: Boolean = false,
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val sdp: String? = null,
    val codec: String? = null,
    val encryptionState: EncryptionState = EncryptionState.NONE,
    val errorMessage: String? = null,
    val subscriptionId: Int? = null
)

@Serializable
@Entity(
    tableName = "sip_messages",
    indices = [
        Index("accountId"),
        Index("threadId"),
        Index("timestamp"),
        Index("direction")
    ]
)
@TypeConverters(SipConverters::class)
data class SipMessageEntity(
    @PrimaryKey
    val messageId: String,
    val accountId: String,
    val threadId: String,
    val from: String,
    val to: String,
    val content: String,
    val contentType: String = "text/plain",
    val direction: MessageDirection,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    val subscriptionId: Int? = null
)

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallState {
    INITIATING,
    CONNECTING,
    RINGING,
    ACTIVE,
    ON_HOLD,
    ENDED,
    MISSED,
    REJECTED,
    FAILED
}

enum class MessageDirection {
    INCOMING,
    OUTGOING
}

enum class DeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class EncryptionState {
    NONE,
    ENCRYPTED,
    ENCRYPTED_VERIFIED,
    FAILED
}
