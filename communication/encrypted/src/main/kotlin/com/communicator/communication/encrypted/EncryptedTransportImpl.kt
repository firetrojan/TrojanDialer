package com.communicator.communication.encrypted

import android.content.Context
import android.util.Base64
import android.util.Log
import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.encrypted.IdentityFingerprint
import com.communicator.data.core.encrypted.IdentityVerificationEntity
import com.communicator.data.core.encrypted.MlsGroupDao
import com.communicator.data.core.encrypted.MlsGroupMessageDao
import com.communicator.data.core.encrypted.MlsPendingSendDao
import com.communicator.data.core.encrypted.QrVerificationPayloadEntity
import com.communicator.data.core.encrypted.SafetyNumberEntity
import com.communicator.data.core.model.VerificationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * End-to-end encrypted transport built on a real RFC 9420 MLS group lifecycle
 * ([MlsGroupManager], Bouncy Castle bcmls 1.86).
 *
 * Status (Phase 5C):
 *  - REAL: MLS identity/KeyPackage generation, group creation, join via
 *    Welcome, add/remove member, own-key rotation (Update+commit), epoch
 *    advancement, message-log persistence and process-death recovery,
 *    inbound handling of proposal/commit/application messages.
 *  - REAL: Identity verification (fingerprint generation, TOFU, QR, safety
 *    numbers, change detection, revocation).
 *  - STUB (later phases): network delivery of queued messages, encrypted
 *    voice/video calls. These return explicit failures and are never
 *    reported as working.
 */
class EncryptedTransportImpl(
    private val context: Context,
    groupDao: MlsGroupDao,
    messageDao: MlsGroupMessageDao,
    pendingSendDao: MlsPendingSendDao
) : EncryptedTransport {

    private val logTag = "EncryptedTransportImpl"

    val mls = MlsGroupManager(context, groupDao, messageDao, pendingSendDao)

    private val _identities = MutableStateFlow<List<EncryptedTransport.EncryptedIdentity>>(emptyList())
    val identities: StateFlow<List<EncryptedTransport.EncryptedIdentity>> = _identities.asStateFlow()

    private val _encryptedMessages = MutableStateFlow<List<EncryptedTransport.EncryptedMessage>>(emptyList())

    init {
        // The last message of the persisted queue, as a cold-then-hot flow.
    }

    override fun observeEncryptedMessages(): Flow<EncryptedTransport.EncryptedMessage> =
        kotlinx.coroutines.flow.channelFlow {
            _encryptedMessages.collect { batch ->
                batch.lastOrNull()?.let { send(it) }
            }
        }

    private val _available = MutableStateFlow(true)

    override val id: String = "encrypted-mls-bc"
    override val name: String = "MLS E2E Encryption (BC)"
    override val capabilities: com.communicator.data.core.model.TransportCapabilities = EncryptedCapabilities

    override fun isInstalled(): Boolean = true

    override fun isAvailable(): Boolean = _available.value

    override suspend fun getIdentities(): List<EncryptedTransport.EncryptedIdentity> = _identities.value

    /**
     * Generates a real MLS identity: HPKE init + leaf key pairs, a signature
     * key pair, and a signed KeyPackage (BC 1.86 LeafNode/KeyPackage with a
     * basic credential). Private keys are sealed in Android Keystore storage;
     * only the public material and the local id are exposed here.
     */
    override suspend fun generateIdentity(): Result<EncryptedTransport.EncryptedIdentity> {
        val identityKeyId = UUID.randomUUID().toString()
        val material = mls.generateIdentityMaterial(identityKeyId, identityKeyId).getOrElse { e ->
            Log.e(logTag, "identity generation failed", e)
            return Result.failure(e)
        }

        val identity = EncryptedTransport.EncryptedIdentity(
            identityKeyId = identityKeyId,
            identityKey = Base64.encodeToString(
                material.keyPackage.leafNode.getSignatureKey(), Base64.NO_WRAP
            ),
            deviceKeys = listOf(
                Base64.encodeToString(material.keyPackage.leafNode.getEncryptionKey(), Base64.NO_WRAP)
            ),
            protocol = EncryptedTransport.CryptoProtocol.MLS,
            createdAt = System.currentTimeMillis()
        )
        _identities.value = _identities.value + identity
        return Result.success(identity)
    }

    // ------------------------------------------------------------------
    // MLS group lifecycle (REAL, delegated to MlsGroupManager)
    // ------------------------------------------------------------------

    suspend fun createMlsGroup(identityKeyId: String): Result<String> =
        mls.createGroup(identityKeyId, identityKeyId)

    suspend fun joinMlsGroup(identityKeyId: String, welcomeWire: ByteArray): Result<String> =
        mls.joinGroup(identityKeyId, identityKeyId, welcomeWire)

    suspend fun addMlsMember(groupId: String, keyPackageWire: ByteArray) =
        mls.addMember(groupId, keyPackageWire)

    suspend fun removeMlsMember(groupId: String, leafIndex: Int) =
        mls.removeMember(groupId, leafIndex)

    suspend fun rotateMlsKeys(groupId: String) = mls.rotateOwnKeys(groupId)

    suspend fun processInboundMls(groupId: String, wire: ByteArray) =
        mls.processInbound(groupId, wire)

    suspend fun protectMlsMessage(groupId: String, plaintext: ByteArray) =
        mls.protectApplicationMessage(groupId, plaintext)

    suspend fun mlsMembers(groupId: String) = mls.members(groupId)

    suspend fun mlsEpoch(groupId: String) = mls.currentEpoch(groupId)

    // ------------------------------------------------------------------
    // Identity verification (REAL, delegated to MlsGroupManager)
    // ------------------------------------------------------------------

    override suspend fun generateIdentityFingerprint(identityKeyId: String, displayName: String): Result<IdentityFingerprint> =
        mls.generateIdentityFingerprint(identityKeyId, displayName)

    /**
     * Not implemented, and deliberately so.
     *
     * MLS protects plaintext into a GROUP message; there is no API that encrypts
     * a single payload to one recipient on demand. [EncryptedMessage] carries no
     * group id, so there is no group whose key schedule could protect it.
     *
     * The real send path is group-scoped and already exists on the manager:
     *   MlsGroupManager.sendApplicationMessage(groupId, plaintext, expiresInMs)
     * which encrypts, appends to the durable log and queues the wire bytes for
     * delivery. Returning a fabricated message id here would report delivery that
     * never happened, so this reports the missing prerequisite instead.
     */
    override suspend fun sendEncryptedMessage(
        message: EncryptedTransport.EncryptedMessage
    ): Result<String> = Result.failure(
        UnsupportedOperationException(
            "sendEncryptedMessage(EncryptedMessage) has no MLS equivalent: MLS " +
                "protects plaintext into a group message and EncryptedMessage " +
                "carries no group id. Use " +
                "MlsGroupManager.sendApplicationMessage(groupId, plaintext) for the " +
                "durable, group-scoped send path."
        )
    )

    override suspend fun getVerificationState(contactId: String): Result<VerificationState> =
        Result.failure(NotImplementedError("getVerificationState(contactId) not implemented - use getVerificationState(identityKeyId, peerIdentityKeyId)"))

    override suspend fun getVerificationState(identityKeyId: String, peerIdentityKeyId: String): Result<VerificationState> =
        mls.getVerificationState(identityKeyId, peerIdentityKeyId)

    override suspend fun initializeTofu(identityKeyId: String, peerIdentityKeyId: String, peerFingerprint: String): Result<IdentityVerificationEntity> =
        mls.initializeTofu(identityKeyId, peerIdentityKeyId, peerFingerprint)

    override suspend fun verifyViaQr(contactId: String, qrData: String): Result<Unit> =
        Result.failure(NotImplementedError("QR verification via string not implemented - use verifyViaQr(identityKeyId, peerIdentityKeyId, qrPayload)"))

    override suspend fun verifyViaQr(identityKeyId: String, peerIdentityKeyId: String, qrPayload: QrVerificationPayloadEntity): Result<Unit> =
        mls.verifyViaQr(identityKeyId, peerIdentityKeyId, qrPayload)

    override suspend fun verifyViaSafetyNumber(contactId: String, number: String): Result<Boolean> =
        Result.failure(NotImplementedError("Safety number verification via contactId not implemented"))

    override suspend fun verifyViaSafetyNumber(identityKeyId: String, peerIdentityKeyId: String, number: String): Result<Boolean> =
        mls.verifyViaSafetyNumber(identityKeyId, peerIdentityKeyId, number)

    override suspend fun detectIdentityChange(identityKeyId: String, peerIdentityKeyId: String, currentPeerFingerprint: String): Result<Boolean> =
        mls.detectIdentityChange(identityKeyId, peerIdentityKeyId, currentPeerFingerprint)

    override suspend fun revokeVerification(identityKeyId: String, peerIdentityKeyId: String): Result<Unit> =
        mls.revokeVerification(identityKeyId, peerIdentityKeyId)

    override suspend fun generateQrVerificationPayload(identityKeyId: String, displayName: String, expiresInMs: Long): Result<QrVerificationPayloadEntity> =
        mls.generateQrVerificationPayload(identityKeyId, displayName, expiresInMs)

    override suspend fun parseQrVerificationPayload(qrData: String): Result<QrVerificationPayloadEntity> =
        mls.parseQrVerificationPayload(qrData)

    override suspend fun getSafetyNumber(identityKeyId: String, peerIdentityKeyId: String): Result<String> =
        mls.getSafetyNumber(identityKeyId, peerIdentityKeyId)

    override suspend fun getEpochAuthenticator(groupId: String): Result<String> =
        mls.getEpochAuthenticator(groupId)

    // ------------------------------------------------------------------
    // Still-stubbed areas (explicit failures, never fake success)
    // ------------------------------------------------------------------

    override suspend fun startEncryptedVoiceCall(contactId: String, address: String): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(Exception("E2E voice calls are a later phase (6)"))

    override suspend fun startEncryptedVideoCall(contactId: String, address: String): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(Exception("E2E video calls are a later phase (6)"))

    override suspend fun startVoiceCall(request: CommunicationTransport.VoiceCallRequest): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(Exception("E2E voice calls are a later phase (6)"))

    override suspend fun startVideoCall(request: CommunicationTransport.VideoCallRequest): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(Exception("E2E video calls are a later phase (6)"))

    override suspend fun sendMessage(request: CommunicationTransport.MessageRequest): CommunicationTransport.MessageResult =
        CommunicationTransport.MessageResult.failure(
            Exception("E2E messaging delivery is a later phase (5D); use sendEncryptedMessage for MLS wire bytes")
        )

    override suspend fun resolveContact(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): CommunicationTransport.TransportAvailability {
        return if (capability in capabilities.capabilities) {
            CommunicationTransport.TransportAvailability(
                transportId = id,
                isAvailable = true,
                capability = capability
            )
        } else {
            CommunicationTransport.TransportAvailability(
                transportId = id,
                isAvailable = false,
                capability = capability,
                reason = CommunicationTransport.UnavailableReason.NOT_SUPPORTED
            )
        }
    }

    companion object {
        private const val logTag = "EncryptedTransportImpl"
    }
}
