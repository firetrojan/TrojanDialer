package com.communicator.data.core.encrypted

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persistent MLS group (RFC 9420). One row per group this device is a member of.
 *
 * The Bouncy Castle 1.86 Group object exposes no public serializer, so the group
 * state is recovered by rebuilding from the group's founding material and
 * replaying the stored MLS message log (see [MlsGroupMessageEntity]):
 *
 *  - Groups created on this device: [creatorKeyPackage] is null; the group is
 *    rebuilt with the creator constructor from the identity keys + the stored
 *    commit/proposal/application messages.
 *  - Groups joined via Welcome: [creatorKeyPackage] holds the KeyPackage this
 *    device used to join and [welcomeMessage] holds the Welcome; the group is
 *    rebuilt with the joiner constructor, then the log is replayed.
 *
 * Private key material is NOT stored here. It lives in Android Keystore-backed
 * encrypted storage (see MlsKeyStore) and is referenced by [identityKeyId].
 */
@Entity(
    tableName = "mls_groups",
    indices = [
        Index("groupId", unique = true),
        Index("epoch"),
        Index("identityKeyId"),
        Index("updatedAt")
    ]
)
data class MlsGroupEntity(
    /** Stable local identifier (UUID), distinct from the wire MLS group id. */
    @PrimaryKey
    val groupId: String,
    /** RFC 9420 wire group identifier, base64. */
    val wireGroupId: String,
    /** Current epoch of the persisted state. */
    val epoch: Long,
    /** Epoch at which this device joined (0 for creator). */
    val joinedAtEpoch: Long,
    /** Identity (identityKeyId of EncryptedIdentityEntity) used for this group. */
    val identityKeyId: String,
    /** Cipher suite id (RFC 9420 registry value). */
    val cipherSuiteId: Int,
    /** Local leaf index, -1 until recovered. */
    val leafIndex: Int,
    /** Number of members at the last committed state. */
    val memberCount: Int,
    /** Epoch authenticator of the persisted state, base64 (public value, safe to store). */
    val epochAuthenticator: String,
    /** Base64 KeyPackage used by this device to join, null when creator. */
    val joinerKeyPackage: String? = null,
    /** Base64 Welcome message this device joined through, null when creator. */
    val welcomeMessage: String? = null,
    /** Public tree snapshot at last commit, base64 (contains only public nodes). */
    val publicTreeSnapshot: String? = null,
    /** Count of messages in the replay log. */
    val messageLogSize: Int = 0,
    /** Monotonic guard against stale writes. */
    val stateVersion: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Append-only MLS message log. Every protocol message this device has sent or
 * accepted for a group, in processing order. This is the replay source used to
 * reconstruct Group state after process death, and the deduplication ledger
 * used to reject duplicate commits/proposals/messages.
 */
@Entity(
    tableName = "mls_group_messages",
    indices = [
        Index(value = ["groupId", "sequence"], unique = true),
        Index(value = ["groupId", "messageHash"], unique = true),
        Index("groupId", "epoch"),
        Index("contentType")
    ]
)
data class MlsGroupMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    /** MlsGroupEntity.groupId this message belongs to. */
    val groupId: String,
    /** Ordering of the message inside the group's log (0-based, strictly increasing). */
    val sequence: Long,
    /** Epoch the message was sent in (per wire encoding). */
    val epoch: Long,
    /** RFC 9420 content type name: PROPOSAL, COMMIT, APPLICATION (Welcome is stored on the group row). */
    val contentType: String,
    /** SHA-256 of the encoded MLSMessage wire bytes, base64. Used for dedup. */
    val messageHash: String,
    /** Encoded MLSMessage wire bytes, base64. */
    val encodedMessage: String,
    /** True if generated locally, false if received from the network. */
    val originatedLocally: Boolean,
    val receivedAt: Long = System.currentTimeMillis()
)

/**
 * Durable delivery states for MLS messages (RFC 9420).
 * 
 * State transitions:
 * QUEUED -> SENDING -> TRANSPORT_ACCEPTED -> REMOTE_RECEIVED -> PROCESSED
 *                           -> FAILED (max retries exceeded or expiry)
 *                           -> EXPIRED (TTL exceeded)
 * 
 * Each transition is persisted atomically. The message is only removed
 * after PROCESSED or EXPIRED/FAILED (terminal states).
 * 
 * Concurrency: unique (groupId, messageHash) index prevents duplicates.
 * Row-level locking via Room transactions ensures exactly-once state transitions.
 */
enum class MlsDeliveryState {
    /** Message queued locally, not yet sent to transport */
    QUEUED,
    /** Message handed to transport layer (WebRTC data channel, etc.) */
    SENDING,
    /** Transport layer accepted the message for transmission */
    TRANSPORT_ACCEPTED,
    /** Remote peer acknowledged receipt (MLS application-layer ack) */
    REMOTE_RECEIVED,
    /** Remote peer processed and decrypted the message */
    PROCESSED,
    /** Transport failed permanently (max retries exceeded) */
    FAILED,
    /** Message expired (TTL exceeded without delivery) */
    EXPIRED;

    /** States from which no further transition is permitted. */
    val isTerminal: Boolean
        get() = this == PROCESSED || this == FAILED || this == EXPIRED

    /**
     * The only legal edges. Anything not listed here must be rejected before it
     * reaches SQL, so a caller cannot walk a message backwards (for example
     * PROCESSED -> SENDING) or skip a stage.
     *
     *  QUEUED             -> SENDING | EXPIRED | FAILED
     *  SENDING            -> TRANSPORT_ACCEPTED | EXPIRED | FAILED
     *  TRANSPORT_ACCEPTED -> REMOTE_RECEIVED | EXPIRED | FAILED
     *  REMOTE_RECEIVED    -> PROCESSED
     *
     * SENDING -> SENDING is intentionally absent: a retry does not change the
     * message's position in the lifecycle, it only consumes another unit of the
     * retry budget. That is modelled separately by
     * [MlsDeliveryState.canReclaimForRetry], which the queue processor uses
     * when it picks a backed-off SENDING row up again.
     */
    fun canTransitionTo(next: MlsDeliveryState): Boolean = when (this) {
        QUEUED -> next == SENDING || next == EXPIRED || next == FAILED
        SENDING -> next == TRANSPORT_ACCEPTED || next == EXPIRED || next == FAILED
        TRANSPORT_ACCEPTED -> next == REMOTE_RECEIVED || next == EXPIRED || next == FAILED
        REMOTE_RECEIVED -> next == PROCESSED
        PROCESSED, FAILED, EXPIRED -> false
    }

    /**
     * Whether a backed-off message may be handed to a transport again.
     *
     * Only SENDING qualifies: the message was claimed by a worker whose process
     * or transport failed before it reached TRANSPORT_ACCEPTED. Re-claiming
     * consumes another attempt via the same guarded UPDATE, so two workers can
     * still not claim the same row.
     *
     * QUEUED is included because a queued message has simply never been claimed.
     */
    fun canReclaimForRetry(): Boolean = this == QUEUED || this == SENDING
}

/**
 * Outbound MLS message awaiting delivery. Produced by commit/add/
 * remove/update operations; consumed by the transport layer, which removes
 * entries after successful send. Survives process death so a commit is never
 * lost between state advance and network delivery.
 */
@Entity(
    tableName = "mls_pending_sends",
    indices = [
        Index("groupId"),
        Index("createdAt"),
        Index("state"),
        Index(value = ["groupId", "messageHash"], unique = true)
    ]
)
data class MlsPendingSendEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    val groupId: String,
    /** Encoded MLSMessage wire bytes, base64. */
    val encodedMessage: String,
    /** PROPOSAL, COMMIT, or WELCOME. */
    val contentType: String,
    /** Current delivery state. */
    val state: MlsDeliveryState = MlsDeliveryState.QUEUED,
    /** Number of transmission attempts. */
    val attemptCount: Int = 0,
    /** Maximum number of retry attempts before FAILED. */
    val maxAttempts: Int = 5,
    /** Base64 SHA-256 of encodedMessage for deduplication. */
    val messageHash: String,
    /** Optional transport-specific correlation ID (e.g., WebRTC data channel ID). */
    val transportCorrelationId: String? = null,
    /** Unix epoch ms when this message was queued. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Unix epoch ms when state last changed. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** Unix epoch ms when message expires (0 = no expiry). */
    val expiresAt: Long = 0,
    /** Number of retry attempts made. */
    val retryCount: Int = 0,
    /** Unix epoch ms of next scheduled retry (0 = immediate/none). */
    val nextRetryAt: Long = 0
)
