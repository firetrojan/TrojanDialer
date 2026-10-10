package com.communicator.communication.encrypted

import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.Attachment
import com.communicator.data.core.model.TransportCapabilities
import com.communicator.data.core.encrypted.IdentityFingerprint
import com.communicator.data.core.encrypted.IdentityVerificationEntity
import com.communicator.data.core.encrypted.QrVerificationPayloadEntity
import com.communicator.data.core.encrypted.SafetyNumberEntity
import com.communicator.data.core.model.VerificationState

/**
 * End-to-end encrypted communication transport.
 *
 * Uses audited/established FOSS cryptographic libraries/protocols.
 * Does not invent cryptography. Supports identity keys, device keys,
 * contact verification, and local key storage via Android Keystore.
 */
interface EncryptedTransport : CommunicationTransport {
    /** Returns the cryptographic identities available for this transport. */
    suspend fun getIdentities(): List<EncryptedIdentity>

    /** Generates a new identity key pair. */
    suspend fun generateIdentity(): Result<EncryptedIdentity>

    /** Returns the verification state for a contact. */
    suspend fun getVerificationState(contactId: String): Result<VerificationState>

    /** Verifies a contact via QR code. */
    suspend fun verifyViaQr(contactId: String, qrData: String): Result<Unit>

    /** Verifies a contact via safety number comparison. */
    suspend fun verifyViaSafetyNumber(contactId: String, number: String): Result<Boolean>

    /** Sends an encrypted message. */
    suspend fun sendEncryptedMessage(message: EncryptedMessage): Result<String>

    /** Receives encrypted messages. */
    fun observeEncryptedMessages(): kotlinx.coroutines.flow.Flow<EncryptedMessage>

    /** Starts an encrypted voice call. */
    suspend fun startEncryptedVoiceCall(contactId: String, address: String): CommunicationTransport.CallResult

    /** Starts an encrypted video call. */
    suspend fun startEncryptedVideoCall(contactId: String, address: String): CommunicationTransport.CallResult

    /** Generates a stable identity fingerprint for verification. */
    suspend fun generateIdentityFingerprint(identityKeyId: String, displayName: String): Result<IdentityFingerprint>

    /** Gets the current verification state for a peer. */
    suspend fun getVerificationState(identityKeyId: String, peerIdentityKeyId: String): Result<VerificationState>

    /** Initializes Trust-On-First-Use (TOFU) for a peer identity. */
    suspend fun initializeTofu(identityKeyId: String, peerIdentityKeyId: String, peerFingerprint: String): Result<IdentityVerificationEntity>

    /** Verifies a contact via QR code. */
    suspend fun verifyViaQr(identityKeyId: String, peerIdentityKeyId: String, qrPayload: QrVerificationPayloadEntity): Result<Unit>

    /** Verifies a contact via safety number comparison. */
    suspend fun verifyViaSafetyNumber(identityKeyId: String, peerIdentityKeyId: String, number: String): Result<Boolean>

    /** Detects if a peer's identity has changed. */
    suspend fun detectIdentityChange(identityKeyId: String, peerIdentityKeyId: String, currentPeerFingerprint: String): Result<Boolean>

    /** Revokes trust for a peer identity. */
    suspend fun revokeVerification(identityKeyId: String, peerIdentityKeyId: String): Result<Unit>

    /** Generates a QR verification payload for this identity. */
    suspend fun generateQrVerificationPayload(identityKeyId: String, displayName: String, expiresInMs: Long): Result<QrVerificationPayloadEntity>

    /** Parses and validates a QR verification payload. */
    suspend fun parseQrVerificationPayload(qrData: String): Result<QrVerificationPayloadEntity>

    /** Gets the safety number for an identity pair. */
    suspend fun getSafetyNumber(identityKeyId: String, peerIdentityKeyId: String): Result<String>

    /** Gets the epoch authenticator for a group. */
    suspend fun getEpochAuthenticator(groupId: String): Result<String>

    data class EncryptedIdentity(
        val identityKeyId: String,
        val identityKey: String, // Base64 public key
        val deviceKeys: List<String>, // Base64 device public keys
        val protocol: CryptoProtocol,
        val createdAt: Long,
        val isVerified: Boolean = false
    )

    data class EncryptedMessage(
        val messageId: String,
        val senderId: String,
        val recipientId: String,
        val encryptedContent: String,
        val attachmentMetadata: List<Attachment> = emptyList(),
        val encryptionState: EncryptionState,
        val protocol: CryptoProtocol,
        val timestamp: Long
    )

    enum class CryptoProtocol {
        SIGNAL,
        MLS,
        OMEMO,
        CUSTOM
    }

    enum class EncryptionState {
        NONE,
        ENCRYPTED,
        ENCRYPTED_VERIFIED,
        FAILED
    }

}

/**
 * Capabilities this transport can genuinely provide today.
 *
 * Only MLS messaging is claimed, plus offline delivery via the durable queue.
 * Voice, video and carrier capabilities are deliberately NOT advertised: no
 * audio or media path exists here, so claiming them would make capability
 * discovery report something that cannot be exercised.
 *
 * This constant was referenced by EncryptedTransportImpl but never declared,
 * which is why :communication:encrypted:compileDebugKotlin failed with
 * "Unresolved reference 'EncryptedCapabilities'".
 */
val EncryptedCapabilities: TransportCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.END_TO_END_ENCRYPTION,
        // Messages are queued durably and retried, so the remote peer need not
        // be online at send time.
        com.communicator.data.core.model.Capability.OFFLINE
    )
)
