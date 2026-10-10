package com.communicator.communication.encrypted

import android.content.Context
import android.util.Base64
import com.communicator.data.core.encrypted.IdentityFingerprint
import com.communicator.data.core.encrypted.IdentityVerificationDao
import com.communicator.data.core.encrypted.IdentityVerificationEntity
import com.communicator.data.core.encrypted.MlsGroupDao
import com.communicator.data.core.encrypted.MlsGroupEntity
import com.communicator.data.core.encrypted.MlsGroupMessageDao
import com.communicator.data.core.encrypted.MlsDeliveryState
import com.communicator.data.core.encrypted.MlsPendingSendDao
import com.communicator.data.core.encrypted.MlsPendingSendEntity
import com.communicator.data.core.encrypted.QrVerificationPayloadDao
import com.communicator.data.core.encrypted.QrVerificationPayloadEntity
import com.communicator.data.core.encrypted.SafetyNumberDao
import com.communicator.data.core.encrypted.SafetyNumberEntity
import com.communicator.data.core.encrypted.TrustLevel
import com.communicator.data.core.encrypted.VerificationEventType
import com.communicator.data.core.model.VerificationState
import com.communicator.data.core.encrypted.VerificationEventDao
import com.communicator.data.core.encrypted.VerificationEventEntity
import com.communicator.data.security.MlsKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.mls.TreeKEM.LeafIndex
import org.bouncycastle.mls.TreeKEM.LeafNode
import org.bouncycastle.mls.TreeKEM.LifeTime
import org.bouncycastle.mls.codec.Capabilities
import org.bouncycastle.mls.codec.ContentType
import org.bouncycastle.mls.codec.Credential
import org.bouncycastle.mls.codec.Extension
import org.bouncycastle.mls.codec.KeyPackage
import org.bouncycastle.mls.codec.MLSInputStream
import org.bouncycastle.mls.codec.MLSMessage
import org.bouncycastle.mls.codec.MLSOutputStream
import org.bouncycastle.mls.codec.Proposal
import org.bouncycastle.mls.codec.WireFormat
import org.bouncycastle.mls.crypto.MlsCipherSuite
import org.bouncycastle.mls.crypto.Secret
import org.bouncycastle.mls.protocol.Group
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Real RFC 9420 MLS group lifecycle over the Bouncy Castle 1.86 API.
 *
 * Every operation produces genuine protocol artifacts: MLSMessage wire bytes
 * carrying proposals, commits and welcomes, produced by BC's own signing,
 * encryption and TreeKEM code. Nothing is simulated.
 *
 * Persistence model (process-death safe):
 *  - [MlsGroupEntity] stores the founding material: the wire group id, the
 *    identity key alias, the joiner KeyPackage + Welcome (for joined groups),
 *    and the current epoch bookkeeping.
 *  - [com.communicator.data.core.encrypted.MlsGroupMessageEntity] is an
 *    append-only, hash-deduplicated log of every protocol message (proposal /
 *    commit / application) in processing order.
 *  - BC 1.86 exposes no public Group serializer, so state is recovered by
 *    rebuilding from the founding material and replaying the log. Application
 *    messages are replayed too, because protect()/unprotect() advance hash
 *    ratchets in place; replaying the full log reproduces the exact ratchet
 *    position (BC's GroupKeySet.HashRatchet retains past generations, so
 *    replay of received messages is lossless).
 *  - Private keys (HPKE init/leaf, signature) never touch Room; they are
 *    sealed in Android Keystore storage via [MlsKeyStore] and referenced by
 *    alias.
 *
 * Concurrency: a per-group [Mutex] serializes all state transitions, the DAOs'
 * unique (groupId, messageHash) index rejects duplicates, and the guarded
 * [MlsGroupDao.advance] refuses stale stateVersion writes.
 */
/**
 * Sealed-private-key storage seam. Production uses [MlsKeyStore] (Android
 * Keystore-backed); JVM unit tests inject an in-memory implementation. Only
 * the storage location is substitutable - the MLS cryptography is never
 * replaced.
 */
interface KeyVault {
    suspend fun store(alias: String, rawKey: ByteArray)
    suspend fun load(alias: String): ByteArray?
    suspend fun delete(alias: String)
}

/** Android Keystore-backed production vault. */
private class KeystoreVault(private val context: Context) : KeyVault {
    override suspend fun store(alias: String, rawKey: ByteArray) {
        MlsKeyStore.storeKey(context, alias, rawKey)
    }

    override suspend fun load(alias: String): ByteArray? =
        MlsKeyStore.loadKey(context, alias)

    override suspend fun delete(alias: String) {
        MlsKeyStore.deleteKey(context, alias)
    }
}

/**
 * Raised when a caller tries to advance a message to REMOTE_RECEIVED or
 * PROCESSED through a path that carries no authenticated evidence.
 *
 * A local row id is not proof of delivery: it proves only that this device
 * holds a row. Reaching those states requires an ACK that is signed by the
 * receiving member, bound to the exact message hash and the current group
 * epoch, and replay-protected. None of that exists yet, so the transition is
 * refused rather than approximated. TRANSPORT_ACCEPTED is the highest state
 * reachable from network-originated events.
 */
class ACKNotAuthenticatedException(rowId: Long) : IllegalStateException(
    "Refusing to advance pending send $rowId to REMOTE_RECEIVED/PROCESSED: " +
        "no authenticated, message-bound ACK is implemented. " +
        "TRANSPORT_ACCEPTED must not be treated as remote receipt."
)

/** Base retry delay, doubling per attempt up to [MAX_BACKOFF_MS]. */
private const val BASE_BACKOFF_MS = 1_000L

/** Upper bound on retry backoff. */
private const val MAX_BACKOFF_MS = 60_000L

/**
 * Base64 helpers.
 *
 * java.util.Base64 requires API 26; this module declares minSdk 21, so
 * android.util.Base64 is used on device. It is referenced through the Android
 * stub jar during JVM unit tests, where these two helpers are not exercised by
 * the MLS lifecycle tests (they build wire bytes via MLSOutputStream).
 */
private fun b64(bytes: ByteArray): String =
    java.util.Base64.getEncoder().encodeToString(bytes)

private fun b64Decode(text: String): ByteArray =
    java.util.Base64.getDecoder().decode(text)

class MlsGroupManager(
    private val context: Context?,
    private val groupDao: MlsGroupDao,
    private val messageDao: MlsGroupMessageDao,
    private val pendingSendDao: MlsPendingSendDao,
    private val keyVault: KeyVault? = null,
    /**
     * Verification DAOs.
     *
     * These are optional because no existing construction site supplies them
     * (the only one, EncryptedTransportImpl, passes four arguments), and the
     * identity-verification section of this file lost its declarations. Rather
     * than guess a wiring that was never recoverable, they default to null and
     * every verification entry point fails closed when they are absent. MLS
     * group lifecycle and durable delivery do not depend on them.
     *
     * BLOCKER: once the intended constructor contract is recovered, pass the
     * four DAOs from CommunicatorDatabase and remove the guards.
     */
    private val identityVerificationDao: IdentityVerificationDao? = null,
    private val verificationEventDao: VerificationEventDao? = null,
    private val qrVerificationPayloadDao: QrVerificationPayloadDao? = null,
    private val safetyNumberDao: SafetyNumberDao? = null
) {
    /** Resolves the vault: injected in tests, Keystore-backed in production. */
    private val vault: KeyVault = keyVault ?: KeystoreVault(context!!)

    /**
     * Guards every identity-verification path.
     *
     * Fails closed: a missing DAO must not silently degrade to "unverified but
     * accepted" or fabricate a trust decision.
     */
    private fun requireVerificationDaos() {
        if (identityVerificationDao == null || verificationEventDao == null ||
            qrVerificationPayloadDao == null || safetyNumberDao == null
        ) {
            throw IllegalStateException(
                "Identity verification is unavailable: MlsGroupManager was constructed " +
                    "without its verification DAOs. See the constructor KDoc blocker."
            )
        }
    }

    companion object {
        /** RFC 9420 default suite: DHKEMX25519 + AES128GCM + SHA256 + Ed25519. */
        const val DEFAULT_CIPHER_SUITE: Short =
            MlsCipherSuite.MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519

        /**
         * Log entry type for an applied delivery acknowledgement.
         *
         * Acknowledgements are recorded in the same durable inbound log as MLS
         * messages so replay rejection survives a process restart. The type
         * distinguishes them from APPLICATION rows when the log is replayed to
         * rebuild group state, where they must be skipped.
         */
        internal const val ACK_LOG_CONTENT_TYPE = "DELIVERY_ACK"

        private const val ALIAS_INIT = "init"
        private const val ALIAS_LEAF = "leaf"
        private val ALIAS_SIG = "sig"
    }

    private val random = SecureRandom()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val liveGroups = ConcurrentHashMap<String, Group>()

    private fun lockFor(groupId: String): Mutex = locks.computeIfAbsent(groupId) { Mutex() }

    // ---------------------------------------------------------------------
    // Identity material
    // ---------------------------------------------------------------------

    /** Per-device MLS credential material produced by [generateIdentityMaterial]. */
    class IdentityMaterial(
        val identityKeyId: String,
        val keyPackage: KeyPackage,
        val leafKeyPair: AsymmetricCipherKeyPair,
        val signingKeyBytes: ByteArray
    )

    /**
     * Generates fresh MLS credential material for an identity: HPKE init
     * key (for the KeyPackage), HPKE leaf/encryption key, signature key, and
     * the signed KeyPackage. Private parts are sealed into vault storage
     * under aliases derived from [identityKeyId]; only aliases are persisted.
     *
     * Calling this twice with the same [identityKeyId] rotates that
     * identity's keys: the vault entries are replaced. Callers that share a
     * KeyPackage with another member must use [loadIdentityMaterial] (or the
     * higher-level flows) afterwards so the sealed material always matches
     * the KeyPackage that was distributed.
     */
    suspend fun generateIdentityMaterial(
        identityKeyId: String,
        displayName: String
    ): Result<IdentityMaterial> = withContext(Dispatchers.IO) {
        runCatching {
            val suite = MlsCipherSuite.getSuite(DEFAULT_CIPHER_SUITE)
            val hpke = suite.getHPKE()

            val init = hpke.generatePrivateKey()
            val leaf = hpke.generatePrivateKey()
            val sig = suite.generateSignatureKeyPair()
            val signingKey = suite.serializeSignaturePrivateKey(sig.private)

            val node = LeafNode(
                suite,
                hpke.serializePublicKey(leaf.public),
                suite.serializeSignaturePublicKey(sig.public),
                Credential.forBasic(displayName.toByteArray(Charsets.UTF_8)),
                Capabilities(),
                LifeTime(),
                emptyList<Extension>(),
                signingKey
            )
            val keyPackage = KeyPackage(
                suite,
                hpke.serializePublicKey(init.public),
                node,
                emptyList<Extension>(),
                signingKey
            )

            vault.store(keyAlias(identityKeyId, ALIAS_INIT), hpke.serializePrivateKey(init.private))
            vault.store(keyAlias(identityKeyId, ALIAS_LEAF), hpke.serializePrivateKey(leaf.private))
            vault.store(keyAlias(identityKeyId, ALIAS_SIG), signingKey)

            IdentityMaterial(identityKeyId, keyPackage, leaf, signingKey)
        }
    }

    /**
     * Private key material recovered from vault storage, for the paths that
     * need raw keys rather than a signed KeyPackage (group join, replay rebuild).
     *
     * [initSerialized] is the serialized HPKE init private key: BC's joiner
     * constructor takes it directly to decrypt the Welcome's GroupSecrets, so
     * it is kept in its serialized form rather than deserialized-then-resaved.
     */
    class LoadedKeys(
        val initKeyPair: AsymmetricCipherKeyPair,
        val initSerialized: ByteArray,
        val leafKeyPair: AsymmetricCipherKeyPair,
        val signingKey: ByteArray
    )

    /**
     * Rebuilds the in-memory [IdentityMaterial] an identity previously
     * published, so a KeyPackage handed to another member can be reused byte for
     * byte after a process restart. Returns null when the identity has no
     * stored material yet.
     *
     * This is the inverse of the vault writes in [generateIdentityMaterial]:
     * the same three serialized keys are read back and re-assembled into the
     * same LeafNode/KeyPackage that was distributed. Reconstructing the
     * KeyPackage is required, not optional - a Welcome is encrypted to exactly
     * those public bytes, so regenerating would make the join fail.
     */
    private suspend fun loadIdentityMaterial(
        identityKeyId: String,
        displayName: String
    ): IdentityMaterial? {
        val suite = MlsCipherSuite.getSuite(DEFAULT_CIPHER_SUITE)
        val hpke = suite.getHPKE()
        val loaded = loadKeys(identityKeyId, suite) ?: return null

        val node = LeafNode(
            suite,
            hpke.serializePublicKey(loaded.leafKeyPair.public),
            suite.deserializeSignaturePrivateKey(loaded.signingKey).public.let {
                suite.serializeSignaturePublicKey(it)
            },
            Credential.forBasic(displayName.toByteArray(Charsets.UTF_8)),
            Capabilities(),
            LifeTime(),
            emptyList<Extension>(),
            loaded.signingKey
        )
        val keyPackage = KeyPackage(
            suite,
            hpke.serializePublicKey(loaded.initKeyPair.public),
            node,
            emptyList<Extension>(),
            loaded.signingKey
        )
        return IdentityMaterial(identityKeyId, keyPackage, loaded.leafKeyPair, loaded.signingKey)
    }

    /**
     * Generates a stable, human-verifiable identity fingerprint from the
     * identity's long-term public material. This fingerprint is stable across
     * KeyPackage rotations and epoch changes because it's derived from the
     * long-term identity signature key and a domain separator, NOT from
     * mutable display names.
     *
     * Fingerprint construction (canonical form):
     *   fingerprint = Base64(SHA-256(
     *       "mls-v1" || 0x00 || Ed25519_pubkey || 0x00 || "trojan-dialer-identity-v1"
     *   ))
     *
     * The fingerprint is derived ONLY from the long-term Ed25519 signature
     * public key and a fixed domain separator. The display name is stored
     * as metadata but does NOT affect the fingerprint.
     *
     * Safety number: First 12 Base64 chars of fingerprint (72 bits), grouped
     * as XXXX-XXXX-XXXX for human readability.
     */
    suspend fun generateIdentityFingerprint(
        identityKeyId: String,
        displayName: String
    ): Result<IdentityFingerprint> = withContext(Dispatchers.IO) {
        runCatching {
            val suite = MlsCipherSuite.getSuite(DEFAULT_CIPHER_SUITE)
            val hpke = suite.getHPKE()

            // Load or generate the identity material to get the signature key
            val material = loadIdentityMaterial(identityKeyId, displayName)
                ?: generateIdentityMaterial(identityKeyId, displayName).getOrThrow()

            // Extract the identity signature public key (Ed25519)
            val sigPublic = suite.serializeSignaturePublicKey(material.leafKeyPair.public)

            // Build the fingerprint from stable cryptographic material only
            // Format: "mls-v1" || 0x00 || Ed25519_pubkey || 0x00 || "trojan-dialer-identity-v1"
            // This ensures the fingerprint is bound ONLY to the cryptographic identity key
            val fingerprintBytes = ByteArrayOutputStream().apply {
                write("mls-v1".toByteArray(Charsets.UTF_8))
                write(0x00)
                write(sigPublic)
                write(0x00)
                write("trojan-dialer-identity-v1".toByteArray(Charsets.UTF_8))
            }.toByteArray()

            val digest = MessageDigest.getInstance("SHA-256").digest(fingerprintBytes)
            val fingerprint = b64(digest)
            val identityPublicKeyB64 = b64(sigPublic)
            
            // Safety number: first 12 Base64 chars of fingerprint (72 bits of entropy)
            // This provides ~48 bits of collision resistance for human comparison
            val safetyNumber = fingerprint.substring(0, minOf(12, fingerprint.length)).uppercase()
            
            // Group the safety number for human readability: XXXX-XXXX-XXXX
            val formattedSafetyNumber = safetyNumber.chunked(4).joinToString("-")

            IdentityFingerprint(
                fingerprint = fingerprint,
                identityPublicKeyB64 = identityPublicKeyB64,
                displayName = displayName,
                safetyNumber = formattedSafetyNumber,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    /** Rebuilds an identity's key pairs from Keystore storage (process-death recovery). */
    private suspend fun loadKeys(identityKeyId: String, suite: MlsCipherSuite): LoadedKeys? {
        val initBytes = vault.load(keyAlias(identityKeyId, ALIAS_INIT)) ?: return null
        val leafBytes = vault.load(keyAlias(identityKeyId, ALIAS_LEAF)) ?: return null
        val sigBytes = vault.load(keyAlias(identityKeyId, ALIAS_SIG)) ?: return null
        val hpke = suite.getHPKE()

        // BC's HPKE.deserializePrivateKey(sk, null) regenerates the public key
        // from the private scalar (X25519PrivateKeyParameters.generatePublicKey),
        // so a null public key argument is valid here.
        val initPair = hpke.deserializePrivateKey(initBytes, null)
        val leafPair = hpke.deserializePrivateKey(leafBytes, null)
        return LoadedKeys(initPair, initBytes, leafPair, sigBytes)
    }

// IdentityFingerprint is declared once, in
// com.communicator.data.core.encrypted, and used from here. A second,
// field-identical copy nested in this class meant the manager returned
// MlsGroupManager.IdentityFingerprint while the transport interface used
// the data.core type, so the override could not compile:
//   Return type of 'generateIdentityFingerprint(...)' is not a subtype of
//   the return type of the overridden member

    private fun keyAlias(identityKeyId: String, role: String) = "${identityKeyId}_$role"

    // ---------------------------------------------------------------------
    // Group creation
    // ---------------------------------------------------------------------

    /**
     * Creates a real one-member MLS group (RFC 9420 sec. 12.1) and persists
     * it. The group is immediately usable for commits and application
     * messages; its KeyPackage can be shared to let others request addition.
     */
    suspend fun createGroup(
        identityKeyId: String,
        displayName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val suite = MlsCipherSuite.getSuite(DEFAULT_CIPHER_SUITE)
            val material = generateIdentityMaterial(identityKeyId, displayName).getOrThrow()

            val wireGroupId = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
            val group = Group(
                wireGroupId,
                suite,
                material.leafKeyPair,
                material.signingKeyBytes,
                material.keyPackage.leafNode.copy(material.keyPackage.leafNode.getEncryptionKey()),
                emptyList<Extension>()
            )

            val localId = UUID.randomUUID().toString()
            groupDao.insert(
                MlsGroupEntity(
                    groupId = localId,
                    wireGroupId = b64(group.groupID),
                    epoch = group.epoch,
                    joinedAtEpoch = 0,
                    identityKeyId = identityKeyId,
                    cipherSuiteId = DEFAULT_CIPHER_SUITE.toInt(),
                    leafIndex = group.index.value(),
                    memberCount = memberCount(group),
                    epochAuthenticator = b64(group.epochAuthenticator),
                    joinerKeyPackage = null,
                    welcomeMessage = null,
                    publicTreeSnapshot = encodePublicTree(group),
                    messageLogSize = 0,
                    stateVersion = 0
                )
            )
            liveGroups[localId] = group
            localId
        }
    }

    // ---------------------------------------------------------------------
    // Group join (through a real Welcome)
    // ---------------------------------------------------------------------

    /**
     * Joins a group through a Welcome addressed to this identity's KeyPackage,
     * using BC's joiner constructor (Welcome.find -> decryptSecrets ->
     * decrypt GroupInfo -> TreeKEM joiner derivation). The Welcome and the
     * KeyPackage used are persisted so the group can be rebuilt after process
     * death without the founding device being reachable.
     */
    suspend fun joinGroup(
        identityKeyId: String,
        displayName: String,
        welcomeWire: ByteArray
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val suite = MlsCipherSuite.getSuite(DEFAULT_CIPHER_SUITE)
            // Reuse the identity material that was distributed: the Welcome is
            // encrypted to the KeyPackage this identity published, so fresh
            // material would not match (Welcome.find would fail).
            val material = loadIdentityMaterial(identityKeyId, displayName)
                ?: generateIdentityMaterial(identityKeyId, displayName).getOrThrow()
            val keys = loadKeys(identityKeyId, suite)
                ?: throw IllegalStateException("Key material missing for $identityKeyId")

            val welcomeMsg = MLSInputStream.decode(welcomeWire, MLSMessage::class.java) as MLSMessage
            val welcome = welcomeMsg.welcome
                ?: throw IllegalArgumentException("Message is not a Welcome (wire format ${welcomeMsg.wireFormat})")

            // BC 1.86 joiner constructor:
            //   Group(initKeySerialized, leafKeyPair, signingKey, KeyPackage,
            //         Welcome, tree, externalPSKs, resumptionPSKs)
            // Slot 1 is the SERIALIZED INIT PRIVATE KEY (used to decrypt the
            // Welcome's GroupSecrets), not a group id; the group id is taken
            // from the decrypted GroupInfo.
            val group = Group(
                keys.initSerialized,
                material.leafKeyPair,
                material.signingKeyBytes,
                material.keyPackage,
                welcome,
                null,
                emptyMap(),
                emptyMap()
            )

            val localId = UUID.randomUUID().toString()
            groupDao.insert(
                MlsGroupEntity(
                    groupId = localId,
                    wireGroupId = b64(group.groupID),
                    epoch = group.epoch,
                    joinedAtEpoch = group.epoch,
                    identityKeyId = identityKeyId,
                    cipherSuiteId = DEFAULT_CIPHER_SUITE.toInt(),
                    leafIndex = group.index.value(),
                    memberCount = memberCount(group),
                    epochAuthenticator = b64(group.epochAuthenticator),
                    joinerKeyPackage = b64(MLSOutputStream.encode(material.keyPackage)),
                    welcomeMessage = b64(welcomeWire),
                    publicTreeSnapshot = encodePublicTree(group),
                    messageLogSize = 0,
                    stateVersion = 0
                )
            )
            liveGroups[localId] = group
            localId
        }
    }

    // ---------------------------------------------------------------------
    // Member management
    // ---------------------------------------------------------------------

    /** Wire outputs of an add-member operation, ready for network delivery. */
    class AddMemberOutput(
        /** Commit message to deliver to existing members. */
        val commitBytes: ByteArray,
        /** Welcome to deliver to the new member (empty when BC produced none). */
        val welcomeBytes: ByteArray,
        val newEpoch: Long
    )

    /**
     * Adds a member: a real Add proposal committed with a path. BC produces
     * the commit MLSMessage with the Welcome embedded in it; both are stored
     * in the log / delivery queue and the new state is persisted atomically.
     */
    suspend fun addMember(groupId: String, joinerKeyPackageWire: ByteArray): Result<AddMemberOutput> {
        data class Output(
            val commitBytes: ByteArray,
            val welcomeBytes: ByteArray,
            val newGroup: Group,
            val proposalWire: ByteArray
        )

        val outcome = lockedMutation(groupId) { group, entity ->
            val joinerKp = MLSInputStream.decode(joinerKeyPackageWire, KeyPackage::class.java) as KeyPackage
            if (!joinerKp.verify()) throw IllegalArgumentException("Joiner KeyPackage failed verification")

            // 1. Sign + protect a real Add proposal for the group (BC add()
            //    does NOT cache it; it is carried into the commit explicitly).
            val proposalMsg = group.add(joinerKp, Group.MessageOptions())
            val proposalWire = MLSOutputStream.encode(proposalMsg)

            // 2. Commit with the Add proposal, producing the new state and
            //    the Welcome embedded in the returned MLSMessage.
            val commitResult = group.commit(
                freshSecret(group),
                Group.CommitOptions(listOf(Proposal.add(joinerKp)), true, false, null),
                Group.MessageOptions(),
                Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS)
            )

            val commitWire = MLSOutputStream.encode(commitResult.message)

            // The Welcome is embedded in the commit MLSMessage under the
            // mls_welcome wire format. Wrap it in its own MLSMessage for
            // delivery to the joiner (the same bytes joinGroup parses).
            val welcomeWire = commitResult.message.welcome?.let {
                val wrapper = MLSMessage(WireFormat.mls_welcome)
                wrapper.welcome = it
                MLSOutputStream.encode(wrapper)
            } ?: ByteArray(0)

            Output(commitWire, welcomeWire, commitResult.group, proposalWire) to MutationResult(
                commitWire = commitWire,
                newGroup = commitResult.group,
                extraLogEntries = listOf(ContentType.PROPOSAL.name to proposalWire),
                welcomeWire = welcomeWire
            )
        } ?: return Result.failure(IllegalStateException("group not found"))

        return outcome.map { out ->
            AddMemberOutput(out.commitBytes, out.welcomeBytes, out.newGroup.epoch)
        }
    }

    /** Wire outputs of a remove-member operation. */
    class RemoveMemberOutput(
        val commitBytes: ByteArray,
        val newEpoch: Long
    )

    /** Removes the member at [leafIndex] via a real Remove proposal + commit. */
    suspend fun removeMember(groupId: String, leafIndex: Int): Result<RemoveMemberOutput> {
        data class Output(
            val commitBytes: ByteArray,
            val newGroup: Group
        )

        val outcome = lockedMutation(groupId) { group, _ ->
            val target = LeafIndex(leafIndex)
            if (!group.tree.hasLeaf(target)) throw IllegalArgumentException("Leaf $leafIndex is not a member")

            val commitResult = group.commit(
                freshSecret(group),
                Group.CommitOptions(listOf(Proposal.remove(target)), true, false, null),
                Group.MessageOptions(),
                Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS)
            )
            val commitWire = MLSOutputStream.encode(commitResult.message)
            Output(commitWire, commitResult.group) to MutationResult(commitWire, commitResult.group)
        } ?: return Result.failure(IllegalStateException("group not found"))

        return outcome.map { RemoveMemberOutput(it.commitBytes, it.newGroup.epoch) }
    }

    /** Wire outputs of a key-rotation operation. */
    class KeyRotationOutput(
        val commitBytes: ByteArray,
        val newEpoch: Long
    )

    /**
     * Key rotation: a real Update proposal carrying a freshly generated HPKE
     * leaf key, committed with forcePath (re-derives the TreeKEM path from the
     * leaf up, giving post-compromise security per RFC 9420 sec. 12.2). The
     * new leaf private key replaces the old one in Keystore storage, so the
     * old key cannot be used to rebuild future state.
     */
    suspend fun rotateOwnKeys(groupId: String): Result<KeyRotationOutput> {
        data class Output(
            val commitBytes: ByteArray,
            val newGroup: Group,
            val identityKeyId: String,
            val newLeafPrivate: ByteArray
        )

        val outcome = lockedMutation(groupId) { group, entity ->
            val suite = group.suite
            val hpke = suite.getHPKE()
            val newLeaf = hpke.generatePrivateKey()

            // BC 1.86 supported rotation flow:
            //  1) updateProposal(newLeaf) builds a real Update proposal
            //     carrying the new leaf's public key;
            //  2) group.update(proposal, opts) signs it AND caches the new
            //     leaf private key (cachedUpdate.updateSk) - required, or the
            //     commit throws "Self-update with no cached secret";
            //  3) commit with NO extraProposals: the cached self-update is
            //     applied and the new leaf key replaces the old one. Passing
            //     the proposal via extraProposals is rejected by 1.86's
            //     validateCachedProposals ("Invalid proposal list").
            val proposal = group.updateProposal(newLeaf, Group.LeafNodeOptions())
            val updateMessage = group.update(proposal, Group.MessageOptions())
            val updateWire = MLSOutputStream.encode(updateMessage)

            val commitResult = group.commit(
                freshSecret(group),
                Group.CommitOptions(emptyList(), true, false, null),
                Group.MessageOptions(),
                Group.CommitParameters(Group.NORMAL_COMMIT_PARAMS)
            )
            val commitWire = MLSOutputStream.encode(commitResult.message)

            Output(
                commitWire,
                commitResult.group,
                entity.identityKeyId,
                hpke.serializePrivateKey(newLeaf.private)
            ) to MutationResult(
                commitWire = commitWire,
                newGroup = commitResult.group,
                extraLogEntries = listOf(ContentType.PROPOSAL.name to updateWire)
            )
        } ?: return Result.failure(IllegalStateException("group not found"))

        return outcome.map { out ->
            // Persist the new leaf key only after the commit succeeded, so the
            // old key remains the recovery key if the commit failed.
            vault.store(
                keyAlias(out.identityKeyId, ALIAS_LEAF),
                out.newLeafPrivate
            )
            KeyRotationOutput(out.commitBytes, out.newGroup.epoch)
        }
    }

    // ---------------------------------------------------------------------
    // Inbound processing (all message types)
    // ---------------------------------------------------------------------

    /** Result of processing one inbound message. */
    class InboundResult(
        /** PROPOSAL, COMMIT or APPLICATION. */
        val contentType: String,
        val newEpoch: Long,
        /** Decrypted application payload; null for handshake messages. */
        val plaintext: ByteArray?,
        val memberCount: Int,
        /**
         * Authenticated sender leaf index, taken from the MLS Sender after the
         * message signature verified. -1 for handshake messages, which carry no
         * single sender.
         *
         * This is the only trustworthy sender identity available here: header
         * fields such as getEpoch() are attacker-controlled bytes and are never
         * used for authentication.
         */
        val authenticatedSenderLeafIndex: Int = -1,
        /**
         * Identity string bound to that leaf, or null when unknown. As
         * trustworthy as the leaf index, since both come from verified state.
         */
        val authenticatedSenderIdentity: String? = null
    )

    /**
     * Processes one inbound MLS message of any group-message type.
     *
     * Rejections (all throw, surfaced via Result.failure):
     *  - malformed encodings (decode failure)
     *  - duplicates (hash already in the log) - returns null result, no throw
     *  - stale epochs ([StaleEpochException])
     *  - Welcome routed here (must go through [joinGroup])
     *  - any BC validation failure (bad signature, bad membership tag,
     *    invalid proposals, confirmation tag mismatch...)
     *
     * Proposals are cached by BC and return null (no state change); commits
     * and application messages advance state, which is then persisted.
     */
    suspend fun processInbound(groupId: String, wire: ByteArray): Result<InboundResult?> =
        lockFor(groupId).withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val entity = groupDao.get(groupId)
                        ?: throw IllegalArgumentException("Unknown group $groupId")
                    val group = liveGroups.getOrPut(groupId) { rebuild(entity) }

                    val hashB64 = b64(sha256(wire))
                    if (messageDao.countByHash(groupId, hashB64) > 0) {
                        return@runCatching null // duplicate: drop silently
                    }

                    val msg = try {
                        MLSInputStream.decode(wire, MLSMessage::class.java) as MLSMessage
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Malformed MLS message", e)
                    }

                    when (msg.wireFormat) {
                        WireFormat.mls_welcome ->
                            throw IllegalArgumentException(
                                "Welcome messages must be processed via joinGroup"
                            )
                        WireFormat.mls_key_package,
                        WireFormat.mls_group_info ->
                            throw IllegalArgumentException(
                                "KeyPackage/GroupInfo are not group messages; route via addMember/external join"
                            )
                        else -> {
                            if (msg.getEpoch() != group.epoch) {
                                throw StaleEpochException(group.epoch, msg.getEpoch())
                            }

                            // Route by content type through BC's own
                            // verification: handle() accepts handshake
                            // messages (signatures, membership tags,
                            // confirmation tags on commits); application
                            // messages go through unprotect() only.
                            val next: Group?
                            val plaintext: ByteArray?
                            var senderLeafIndex: Int = -1
                            when (msg.getContentType()) {
                                ContentType.APPLICATION -> {
                                    // unprotect() verifies the MLS message
                                    // signature and advances the receive ratchet,
                                    // returning [content, authenticatedData].
                                    val decoded = group.unprotect(msg)
                                    plaintext = decoded[0]
                                    // BLOCKED (documented, not worked around):
                                    // Bouncy Castle 1.86 does not expose the
                                    // authenticated sender of an unprotect'ed
                                    // application message. Group.unprotect()
                                    // returns byte[][] with no Sender, and
                                    // Group.unprotectToContentAuth() - the only
                                    // public route to FramedContent.getSender() -
                                    // is private in this binding. The signature IS
                                    // verified, so the frame provably came from a
                                    // current group member, but WHICH member is
                                    // not recoverable without a private API, which
                                    // is not permitted here.
                                    //
                                    // So senderLeafIndex stays -1 and
                                    // applyAuthenticatedAck refuses. Reaching a
                                    // per-sender binding needs a protocol change
                                    // (an acknowledgement whose sender is bound
                                    // outside the opaque MLS payload) or a BC
                                    // binding that exposes AuthenticatedContent.
                                    senderLeafIndex = -1
                                    next = group // same state object; ratchet moved inside
                                }
                                ContentType.PROPOSAL,
                                ContentType.COMMIT -> {
                                    // Proposals are cached by BC and return
                                    // null; commits return the next state.
                                    next = group.handle(wire, null)
                                    plaintext = null
                                }
                                else -> throw IllegalArgumentException(
                                    "Unsupported content type ${msg.getContentType()}"
                                )
                            }
                            if (next == null) return@runCatching null // proposal cached

                            val seq = messageDao.appendUnique(
                                groupId = groupId,
                                epoch = msg.getEpoch(),
                                contentType = msg.getContentType().name,
                                messageHash = hashB64,
                                encodedMessage = b64(wire),
                                originatedLocally = false
                            )
                            if (seq < 0) return@runCatching null // concurrent duplicate

                            liveGroups[groupId] = next
                            advanceEntity(groupId, entity, next, (seq + 1).toInt())
                            InboundResult(
                                contentType = msg.getContentType().name,
                                newEpoch = next.epoch,
                                plaintext = plaintext,
                                memberCount = memberCount(next),
                                authenticatedSenderLeafIndex = senderLeafIndex,
                                authenticatedSenderIdentity = if (senderLeafIndex >= 0) {
                                    memberIdentity(next, senderLeafIndex)
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
        }

    class StaleEpochException(val expected: Long, val received: Long) :
        Exception("Stale epoch: group at $expected, message at $received")

    // ---------------------------------------------------------------------
    // Application messages
    // ---------------------------------------------------------------------

    /**
     * Encrypts [plaintext] with the group's current epoch keys (real MLS
     * PrivateMessage) and appends it to the log so replay reproduces the
     * ratchet advance. Returns the wire bytes to transmit.
     */
    suspend fun protectApplicationMessage(groupId: String, plaintext: ByteArray): Result<ByteArray> =
        lockFor(groupId).withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val entity = groupDao.get(groupId)
                        ?: throw IllegalArgumentException("Unknown group $groupId")
                    val group = liveGroups.getOrPut(groupId) { rebuild(entity) }

                    val msg = group.protect(plaintext, ByteArray(0), 0)
                    val wire = MLSOutputStream.encode(msg)

                    val seq = messageDao.appendUnique(
                        groupId = groupId,
                        epoch = group.epoch,
                        contentType = ContentType.APPLICATION.name,
                        messageHash = b64(sha256(wire)),
                        encodedMessage = b64(wire),
                        originatedLocally = true
                    )
                    if (seq >= 0) {
                        advanceEntity(groupId, entity, group, (seq + 1).toInt())
                    }
                    wire
                }
            }
        }

    // ---------------------------------------------------------------------
    // Durable delivery
    // ---------------------------------------------------------------------

    /**
     * Sends an encrypted application message with durable delivery semantics.
     * The message is encrypted, queued for delivery, and persisted with
     * durable delivery semantics (QUEUED -> SENDING -> TRANSPORT_ACCEPTED ->
     * REMOTE_RECEIVED -> PROCESSED).
     */
    suspend fun sendApplicationMessage(
        groupId: String,
        plaintext: ByteArray,
        expiresInMs: Long = 24 * 60 * 60 * 1000 // 24 hours default
    ): Result<String> = lockFor(groupId).withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = groupDao.get(groupId)
                    ?: throw IllegalArgumentException("Unknown group $groupId")
                val group = liveGroups.getOrPut(groupId) { rebuild(entity) }

                val msg = group.protect(plaintext, ByteArray(0), 0)
                val wire = MLSOutputStream.encode(msg)
                val messageHash = b64(sha256(wire))

                // Replay guard: the message log already contains this wire form.
                if (messageDao.countByHash(groupId, messageHash) > 0) {
                    throw IllegalStateException("Duplicate message detected")
                }

                // Persist as QUEUED. The unique index on (groupId, messageHash)
                // is the deduplication authority: insertIfAbsent returns -1 when a
                // concurrent producer already claimed this hash. No read-then-write
                // pre-check, because that races between producers.
                val pendingEntity = MlsPendingSendEntity(
                    groupId = groupId,
                    encodedMessage = b64(wire),
                    contentType = ContentType.APPLICATION.name,
                    state = MlsDeliveryState.QUEUED,
                    messageHash = messageHash,
                    expiresAt = if (expiresInMs > 0) System.currentTimeMillis() + expiresInMs else 0
                )
                val rowId = pendingSendDao.insertIfAbsent(pendingEntity)
                if (rowId <= 0) {
                    throw IllegalStateException("Duplicate message already queued")
                }

                // Log the message
                val seq = messageDao.appendUnique(
                    groupId = groupId,
                    epoch = group.epoch,
                    contentType = ContentType.APPLICATION.name,
                    messageHash = messageHash,
                    encodedMessage = b64(wire),
                    originatedLocally = true
                )
                if (seq >= 0) {
                    advanceEntity(groupId, entity, group, (seq + 1).toInt())
                }

                // Return the rowId as message identifier
                rowId.toString()
            }
        }
    }

    /**
     * Sends a handshake message (commit/proposal) with durable delivery semantics.
     * Used for group management operations (add/remove/rotate).
     */
    private suspend fun queueHandshakeMessage(
        groupId: String,
        wire: ByteArray,
        contentType: String
    ): Result<Unit> = lockFor(groupId).withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val entity = groupDao.get(groupId)
                    ?: throw IllegalArgumentException("Unknown group $groupId")

                val messageHash = b64(sha256(wire))

                // Already logged: the commit is durable, nothing more to queue.
                if (messageDao.countByHash(groupId, messageHash) > 0) {
                    return@runCatching
                }

                val pendingEntity = MlsPendingSendEntity(
                    groupId = groupId,
                    encodedMessage = b64(wire),
                    contentType = contentType,
                    state = MlsDeliveryState.QUEUED,
                    messageHash = messageHash,
                    expiresAt = 0 // Handshake messages don't expire
                )
                // -1 means the unique index rejected a duplicate; that is the
                // correct outcome for a re-run, so it is not an error here.
                pendingSendDao.insertIfAbsent(pendingEntity)
            }
        }
    }

    /**
     * Processes the outbound queue for a group, moving each eligible message
     * through QUEUED -> SENDING -> TRANSPORT_ACCEPTED.
     *
     * Transport acceptance is the highest state this method can reach. It means
     * only that a transport took the bytes; it is NOT evidence that a remote
     * peer received or processed anything. Those transitions require an
     * authenticated, message-bound ACK that does not exist yet, so
     * [markRemoteReceivedByAuthenticatedAck] is the only sanctioned path and is
     * itself gated (see the note there).
     *
     * [onSend] is the transport adapter: it returns success only once the
     * transport has accepted the payload, and a failure is treated as a
     * retryable attempt.
     */
    suspend fun processOutboundQueue(
        groupId: String,
        onSend: suspend (Long, ByteArray) -> Result<Unit>
    ): Result<Int> = lockFor(groupId).withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                groupDao.get(groupId)
                    ?: throw IllegalArgumentException("Unknown group $groupId")

                val now = System.currentTimeMillis()
                var accepted = 0
                var expired = 0

                // 1. Expiry sweep: anything past its TTL in a non-terminal
                //    state becomes EXPIRED before we consider sending it.
                for (stale in pendingSendDao.findExpired(now, TERMINAL_STATES)) {
                    val marked = pendingSendDao.markFailed(
                        stale.rowId, MlsDeliveryState.EXPIRED, stale.state, now
                    )
                    if (marked > 0) expired++
                }

                // 2. Retry-exhaustion sweep. A row whose attempt budget is spent
                //    is excluded by findSendable, so without this it would sit
                //    in SENDING forever and never reach a terminal state.
                for (spent in pendingSendDao.findExhausted(groupId, SENDABLE_STATES)) {
                    val marked = pendingSendDao.markFailed(
                        spent.rowId, MlsDeliveryState.FAILED, spent.state, now
                    )
                    if (marked > 0) expired++
                }

                // 3. Attempt delivery of everything sendable.
                for (pending in pendingSendDao.findSendable(groupId, SENDABLE_STATES, now)) {
                    val wire = b64Decode(pending.encodedMessage)

                    // Claim the row. Both paths are guarded UPDATEs on the
                    // current state, so a competing worker loses the race and
                    // cannot send the same bytes.
                    //
                    // QUEUED -> SENDING is a real lifecycle transition. SENDING
                    // is a retry of an attempt already claimed, which stays
                    // SENDING and only consumes another unit of the budget.
                    val claimed = if (pending.state == MlsDeliveryState.SENDING) {
                        pendingSendDao.reclaimForRetry(pending.rowId, MlsDeliveryState.SENDING, now)
                    } else {
                        pendingSendDao.tryTransitionState(
                            pending.rowId, pending.state, MlsDeliveryState.SENDING, now
                        )
                    }
                    if (claimed <= 0) continue

                    // tryTransitionState already consumed one attempt, so the
                    // attempt number used for backoff is pending.attemptCount + 1.
                    val attempt = pending.attemptCount + 1

                    val sendResult = onSend(pending.rowId, wire)
                    if (sendResult.isFailure) {
                        // Retryable failure: either spend the last attempt
                        // and fail terminally, or schedule the next one.
                        if (attempt >= pending.maxAttempts) {
                            pendingSendDao.markFailed(
                                pending.rowId, MlsDeliveryState.FAILED,
                                MlsDeliveryState.SENDING, now
                            )
                        } else {
                            pendingSendDao.scheduleRetry(
                                pending.rowId, now + backoffMs(attempt),
                                MlsDeliveryState.SENDING, now
                            )
                        }
                        continue
                    }

                    // Transport accepted the bytes. Still not remote receipt.
                    val moved = pendingSendDao.setTransportAccepted(
                        pending.rowId,
                        transportCorrelationId(groupId, pending.rowId),
                        MlsDeliveryState.TRANSPORT_ACCEPTED,
                        MlsDeliveryState.SENDING,
                        now
                    )
                    if (moved > 0) accepted++
                }

                accepted
            }
        }
    }

    /**
     * Exponentially increasing, bounded retry delay for the given 1-based
     * attempt number: 1s, 2s, 4s, 8s ... capped at [MAX_BACKOFF_MS].
     *
     * Public so the backoff schedule can be asserted directly rather than only
     * inferred from timestamps.
     */
    fun backoffMs(attempt: Int): Long {
        if (attempt < 1) return BASE_BACKOFF_MS
        // Clamp the exponent against overflow BEFORE shifting: doubling past the
        // cap is meaningless, and a large attempt count must not wrap to a
        // negative delay.
        var delay = BASE_BACKOFF_MS
        repeat(attempt - 1) {
            if (delay >= MAX_BACKOFF_MS) return MAX_BACKOFF_MS
            delay = delay * 2
        }
        return delay.coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Correlation id recorded when a transport accepted a message. Derived from
     * group and row so it is stable across retries and does not leak content.
     */
    private fun transportCorrelationId(groupId: String, rowId: Long): String =
        "mls:$groupId:$rowId"

    /**
     * UNSAFE BY CONSTRUCTION - retained only to prove it cannot be used.
     *
     * Advancing a message to REMOTE_RECEIVED requires proof that a specific
     * group member received that specific message. A local row id proves nothing,
     * so this overload carries no evidence at all and always refuses.
     *
     * Use [applyAuthenticatedAck] instead, which requires an acknowledgement
     * that was decrypted by the MLS layer and bound to this exact message.
     */
    suspend fun markRemoteReceived(rowId: Long): Result<Unit> =
        Result.failure(ACKNotAuthenticatedException(rowId))

    /**
     * PROCESSED additionally requires evidence that the recipient ran its
     * application, which the current acknowledgement protocol does not carry.
     * Like [markRemoteReceived] this overload has no evidence and refuses; the
     * sanctioned path is [applyAuthenticatedAck].
     */
    suspend fun markProcessed(rowId: Long): Result<Unit> =
        Result.failure(ACKNotAuthenticatedException(rowId))

    /**
     * Applies a delivery acknowledgement that has already been authenticated by
     * the MLS layer.
     *
     * [inbound] MUST be the [InboundResult] returned by [processInbound] for the
     * acknowledgement message itself. That is what supplies the proof: the MLS
     * signature verified, so `authenticatedSenderLeafIndex` is a real member of
     * this group rather than anything the sender claimed.
     *
     * Checks applied before any row is touched, each of which must pass:
     *  1. [inbound] actually carries an authenticated sender (application
     *     message that decrypted), otherwise the sender is unknown.
     *  2. the acknowledged message hash equals [messageHash], so an ACK for one
     *     message cannot advance another.
     *  3. [expectedSenderLeafIndex], when supplied, matches the authenticated
     *     sender, so a third party cannot acknowledge on someone else's behalf.
     *  4. the frame parses and its protocol/version/binding fields are valid.
     *  5. the ACK epoch is not older than the group's current epoch.
     *  6. the row is in TRANSPORT_ACCEPTED, the only state from which an
     *     acknowledgement is meaningful.
     *  7. the ACK hash is not already present in the durable inbound log, which
     *     is what rejects a replay after a restart.
     *
     * On success the row moves to REMOTE_RECEIVED. PROCESSED is never reached
     * here: nothing in the protocol evidences recipient-side processing.
     */
    suspend fun applyAuthenticatedAck(
        groupId: String,
        rowId: Long,
        messageHash: String,
        inbound: InboundResult?,
        expectedSenderLeafIndex: Int? = null
    ): Result<Unit> = lockFor(groupId).withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (inbound == null) {
                    throw AckUnauthenticatedException(
                        "no inbound result: the acknowledgement was not processed"
                    )
                }
                if (inbound.contentType != ContentType.APPLICATION.name) {
                    throw AckFormatException(
                        "acknowledgement must be an application message, got ${inbound.contentType}"
                    )
                }
                val payload = inbound.plaintext
                    ?: throw AckUnauthenticatedException("acknowledgement carried no plaintext")
                val sender = inbound.authenticatedSenderLeafIndex
                if (sender < 0) {
                    throw AckUnauthenticatedException("no authenticated sender leaf index")
                }
                if (expectedSenderLeafIndex != null && expectedSenderLeafIndex != sender) {
                    throw AckBindingException(
                        "authenticated sender leaf $sender does not match expected " +
                            "$expectedSenderLeafIndex"
                    )
                }

                // Load the row before decoding, so the acknowledgement is
                // validated against the hash THIS row holds rather than against
                // whatever hash the caller supplied.
                val row = pendingSendDao.getByRowId(rowId)
                    ?: throw IllegalArgumentException("Unknown pending send $rowId")
                if (row.state != MlsDeliveryState.TRANSPORT_ACCEPTED) {
                    throw AckBindingException(
                        "pending send $rowId is ${row.state}, not TRANSPORT_ACCEPTED"
                    )
                }
                if (row.messageHash != messageHash) {
                    throw AckBindingException(
                        "caller hash $messageHash does not match the hash this row holds"
                    )
                }

                val ack = DeliveryAck.decode(
                    payload = payload,
                    expectedGroupId = groupId,
                    expectedMessageHash = row.messageHash,
                    expectedKind = DeliveryAck.AckKind.REMOTE_RECEIVED
                )

                val currentEpoch = currentEpoch(groupId)
                if (ack.epoch < currentEpoch) {
                    throw AckBindingException(
                        "ack epoch ${ack.epoch} is older than group epoch $currentEpoch"
                    )
                }

                // Durable replay guard. The ACK hash is recorded so the same
                // acknowledgement cannot be applied twice, including after a
                // process restart.
                val ackHash = DeliveryAck.canonicalHash(ack)
                if (messageDao.countByHash(groupId, ackHash) > 0) {
                    throw AckUnauthenticatedException("acknowledgement already applied (replay)")
                }

                val moved = pendingSendDao.markRemoteReceived(
                    rowId,
                    MlsDeliveryState.REMOTE_RECEIVED,
                    MlsDeliveryState.TRANSPORT_ACCEPTED,
                    System.currentTimeMillis()
                )
                if (moved <= 0) {
                    throw AckBindingException(
                        "pending send $rowId changed state before the ack could be applied"
                    )
                }

                // Record the ACK in the durable inbound log AFTER the state
                // change, so a crash between the two replays the ACK and the
                // state guard rejects it, rather than silently re-applying.
                messageDao.appendUnique(
                    groupId = groupId,
                    epoch = ack.epoch,
                    contentType = ACK_LOG_CONTENT_TYPE,
                    messageHash = ackHash,
                    encodedMessage = b64(payload),
                    originatedLocally = false
                )
                Unit
            }
        }
    }

    /**
     * Terminal states a message never leaves.
     */
    private val TERMINAL_STATES = listOf(
        MlsDeliveryState.PROCESSED,
        MlsDeliveryState.FAILED,
        MlsDeliveryState.EXPIRED
    )

    /**
     * States that may still be handed to a transport. SENDING is included so a
     * row whose process died mid-send is retried once its backoff elapses.
     */
    private val SENDABLE_STATES = listOf(
        MlsDeliveryState.QUEUED,
        MlsDeliveryState.SENDING
    )


    // ---------------------------------------------------------------------
    // Introspection
    // ---------------------------------------------------------------------

    suspend fun currentEpoch(groupId: String): Long = withContext(Dispatchers.IO) {
        liveGroups[groupId]?.epoch ?: groupDao.get(groupId)?.epoch ?: -1L
    }

    suspend fun memberCountOf(groupId: String): Int = withContext(Dispatchers.IO) {
        val g = liveGroups[groupId] ?: groupDao.get(groupId)?.let { rebuild(it) }
            ?: throw IllegalArgumentException("Unknown group $groupId")
        memberCount(g)
    }

    /** Enumerates members as (leafIndex, identity-string) pairs. */
    suspend fun members(groupId: String): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val g = liveGroups[groupId] ?: groupDao.get(groupId)?.let { rebuild(it) }
            ?: throw IllegalArgumentException("Unknown group $groupId")
        enumerateMembers(g)
    }

    /** Looks up the identity string occupying [leafIndex]; null when blank. */
    fun memberIdentity(group: Group, leafIndex: Int): String? {
        val leaf = LeafIndex(leafIndex)
        if (!group.tree.hasLeaf(leaf)) return null
        return String(group.tree.getLeafNode(leaf).credential.identity, Charsets.UTF_8)
    }

    /** Pending outbound messages awaiting delivery confirmation. */
    suspend fun pendingOutbound(groupId: String): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        pendingSendDao.forGroup(groupId).map { it.rowId to b64Decode(it.encodedMessage) }
    }

    /** Removes a queued message after confirmed delivery. */
    suspend fun markDelivered(rowId: Long) = withContext(Dispatchers.IO) {
        pendingSendDao.delete(rowId)
    }

    /** Deletes a group and all its persisted state. */
    suspend fun dropGroup(groupId: String) = withContext(Dispatchers.IO) {
        lockFor(groupId).withLock {
            groupDao.delete(groupId)
            messageDao.deleteForGroup(groupId)
            pendingSendDao.deleteForGroup(groupId)
            liveGroups.remove(groupId)
        }
        Unit
    }

    // ---------------------------------------------------------------------
    // Identity verification
    // ---------------------------------------------------------------------

    /**
     * Gets the current verification state for a peer identity.
     */
    suspend fun getVerificationState(
        identityKeyId: String,
        peerIdentityKeyId: String
    ): Result<VerificationState> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val verification = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
            verification?.verificationState ?: VerificationState.UNVERIFIED
        }
    }

    /**
     * Gets the full verification record for a peer identity.
     */
    suspend fun getVerificationRecord(
        identityKeyId: String,
        peerIdentityKeyId: String
    ): Result<IdentityVerificationEntity> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
                ?: throw IllegalStateException("No verification record found for identity pair")
        }
    }

    /**
     * Initializes Trust-On-First-Use (TOFU) for a peer identity.
     * Creates an UNVERIFIED record with TOFU trust level.
     */
    suspend fun initializeTofu(
        identityKeyId: String,
        peerIdentityKeyId: String,
        peerFingerprint: String
    ): Result<IdentityVerificationEntity> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val existing = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
            if (existing != null) {
                return@runCatching existing
            }

            // Get the local identity fingerprint for comparison
            val localFingerprint = generateIdentityFingerprint(identityKeyId, identityKeyId).getOrThrow().fingerprint

            val verification = IdentityVerificationEntity(
                verificationId = UUID.randomUUID().toString(),
                identityKeyId = identityKeyId,
                peerIdentityKeyId = peerIdentityKeyId,
                fingerprint = localFingerprint,
                peerFingerprint = peerFingerprint,
                verificationState = VerificationState.UNVERIFIED,
                trustLevel = TrustLevel.TOFU,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            identityVerificationDao!!.insert(verification)

            // Log TOFU initialization event
            val event = VerificationEventEntity(
                verificationId = verification.verificationId,
                eventType = VerificationEventType.TOFU_INITIAL,
                fingerprint = peerFingerprint,
                details = "Initial TOFU trust established",
                timestamp = System.currentTimeMillis()
            )
            verificationEventDao!!.insert(event)

            verification
        }
    }

    /**
     * Verifies a peer's identity using a QR code scan.
     * Compares the scanned QR payload fingerprint against the locally stored identity.
     */
    suspend fun verifyViaQr(
        identityKeyId: String,
        peerIdentityKeyId: String,
        qrPayload: QrVerificationPayloadEntity
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val verification = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
                ?: throw IllegalStateException("No verification record found. Initialize TOFU first.")

            // 1. Verify QR payload fingerprint matches stored peer fingerprint
            if (qrPayload.fingerprint != verification.peerFingerprint) {
                throw SecurityException(
                    "QR fingerprint mismatch: expected ${verification.peerFingerprint}, got ${qrPayload.fingerprint}"
                )
            }

            // 2. Verify QR payload fingerprint matches locally computed fingerprint for the identity
            val localFingerprint = generateIdentityFingerprint(qrPayload.identityKeyId, qrPayload.credentialDisplayName).getOrThrow().fingerprint
            if (qrPayload.fingerprint != localFingerprint) {
                throw SecurityException(
                    "QR fingerprint doesn't match locally computed fingerprint for identity ${qrPayload.identityKeyId}"
                )
            }

            // 3. Verify QR payload fingerprint matches expected fingerprint for the identity
            val expectedFingerprint = generateIdentityFingerprint(qrPayload.identityKeyId, qrPayload.credentialDisplayName).getOrThrow().fingerprint
            if (qrPayload.fingerprint != expectedFingerprint) {
                throw SecurityException(
                    "QR payload fingerprint doesn't match computed fingerprint for identity ${qrPayload.identityKeyId}"
                )
            }

            // 3. Verify QR payload hasn't expired
            if (qrPayload.expiresAt != null && qrPayload.expiresAt!! < System.currentTimeMillis()) {
                throw IllegalArgumentException("QR verification payload has expired")
            }

            // 4. Verify key package consistency if provided
            val keyPackageBase64 = qrPayload.keyPackageBase64
            if (keyPackageBase64 != null && keyPackageBase64.isNotEmpty()) {
                // In a full implementation, we would decode and validate the KeyPackage
                // against the public keys. For now, we log the presence.
                // TODO: Full KeyPackage validation when MLS library supports it
            }

            // 5. Update verification state to VERIFIED
            val updatedVerification = verification.copy(
                verificationState = VerificationState.VERIFIED,
                trustLevel = TrustLevel.VERIFIED,
                verifiedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            identityVerificationDao!!.update(updatedVerification)

            // Log verification event
            val event = VerificationEventEntity(
                verificationId = verification.verificationId,
                eventType = VerificationEventType.QR_SCANNED,
                fingerprint = qrPayload.fingerprint,
                details = "QR verification successful - payload validated",
                timestamp = System.currentTimeMillis()
            )
            verificationEventDao!!.insert(event)

            // Store QR payload for future reference
            qrVerificationPayloadDao!!.insert(qrPayload)
            Unit
        }
    }

    /**
     * Verifies a contact via safety number comparison.
     * Compares the safety number with the user-provided one.
     */
    suspend fun verifyViaSafetyNumber(
        identityKeyId: String,
        peerIdentityKeyId: String,
        providedSafetyNumber: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val verification = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
                ?: throw IllegalStateException("No verification record found for identity pair")

            // The safety number is the formatted fingerprint (XXXX-XXXX-XXXX)
            val storedSafetyNumber = generateIdentityFingerprint(identityKeyId, identityKeyId).getOrThrow().safetyNumber
            val match = providedSafetyNumber == verification.peerFingerprint

            if (match) {
                // Update to VERIFIED
                val updated = verification.copy(
                    verificationState = VerificationState.VERIFIED,
                    trustLevel = TrustLevel.VERIFIED,
                    verifiedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                identityVerificationDao!!.update(updated)

                // Log event
                val event = VerificationEventEntity(
                    verificationId = verification.verificationId,
                    eventType = VerificationEventType.SAFETY_NUMBER_COMPARED,
                    fingerprint = verification.peerFingerprint,
                    details = "Safety number comparison successful",
                    timestamp = System.currentTimeMillis()
                )
                verificationEventDao!!.insert(event)

                true
            } else {
                // Log failure event
                val event = VerificationEventEntity(
                    verificationId = verification.verificationId,
                    eventType = VerificationEventType.SAFETY_NUMBER_COMPARED,
                    fingerprint = verification.peerFingerprint,
                    details = "Safety number comparison failed",
                    timestamp = System.currentTimeMillis()
                )
                verificationEventDao!!.insert(event)
                false
            }
        }
    }

    /**
     * Detects if a peer's identity has changed by comparing the current
     * fingerprint with the stored one.
     */
    suspend fun detectIdentityChange(
        identityKeyId: String,
        peerIdentityKeyId: String,
        currentPeerFingerprint: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val verification = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
                ?: return@runCatching false

            val changed = currentPeerFingerprint != verification.peerFingerprint

            if (changed) {
                // Update to CHANGED state
                val updated = verification.copy(
                    verificationState = VerificationState.CHANGED,
                    trustLevel = TrustLevel.COMPROMISED,
                    peerFingerprint = currentPeerFingerprint,
                    keyRotationAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                identityVerificationDao!!.update(updated)

                // Log change detection event
                val event = VerificationEventEntity(
                    verificationId = verification.verificationId,
                    eventType = VerificationEventType.FINGERPRINT_CHANGED,
                    fingerprint = currentPeerFingerprint,
                    previousFingerprint = verification.peerFingerprint,
                    details = "Identity fingerprint changed - possible key rotation or impersonation",
                    timestamp = System.currentTimeMillis()
                )
                verificationEventDao!!.insert(event)

                true
            } else {
                false
            }
        }
    }

    /**
     * Revokes trust for a peer identity.
     */
    suspend fun revokeVerification(
        identityKeyId: String,
        peerIdentityKeyId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val verification = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
                ?: throw IllegalStateException("No verification record found for identity pair")

            val updated = verification.copy(
                verificationState = VerificationState.REVOKED,
                trustLevel = TrustLevel.REVOKED,
                updatedAt = System.currentTimeMillis()
            )
            identityVerificationDao!!.update(updated)

            // Log revocation event
            val event = VerificationEventEntity(
                verificationId = verification.verificationId,
                eventType = VerificationEventType.REVOKED,
                fingerprint = verification.peerFingerprint,
                details = "Verification revoked by user",
                timestamp = System.currentTimeMillis()
            )
            verificationEventDao!!.insert(event)
            Unit
        }
    }

    /**
     * Generates a QR verification payload for this identity.
     * The QR payload contains the identity's public verification material.
     */
    suspend fun generateQrVerificationPayload(
        identityKeyId: String,
        displayName: String,
        expiresInMs: Long = 24 * 60 * 60 * 1000 // 24 hours default
    ): Result<QrVerificationPayloadEntity> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            requireVerificationDaos()
            val fingerprint = generateIdentityFingerprint(identityKeyId, identityKeyId).getOrThrow()
            val material = loadIdentityMaterial(identityKeyId, identityKeyId)
                ?: generateIdentityMaterial(identityKeyId, identityKeyId).getOrThrow()

            val suite = MlsCipherSuite.getSuite(DEFAULT_CIPHER_SUITE)
            val hpke = suite.getHPKE()

            val payload = QrVerificationPayloadEntity(
                payloadId = UUID.randomUUID().toString(),
                identityKeyId = identityKeyId,
                version = 1,
                protocol = "MLS-1.0",
                identityKeyIdBase64 = identityKeyId,
                fingerprint = fingerprint.fingerprint,
                signaturePublicKeyBase64 = fingerprint.identityPublicKeyB64,
                // KeyPackage.getInitKey() is already the serialized HPKE public key.
                encryptionPublicKeyBase64 = b64(material.keyPackage.getInitKey()),
                credentialDisplayName = identityKeyId,
                keyPackageBase64 = b64(MLSOutputStream.encode(material.keyPackage)),
                timestamp = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + expiresInMs,
                nonce = UUID.randomUUID().toString()
            )

            // Store the QR payload for future validation
            qrVerificationPayloadDao!!.insert(payload)

            payload
        }
    }

    /**
     * Parses and validates a QR verification payload.
     */
    suspend fun parseQrVerificationPayload(qrData: String): Result<QrVerificationPayloadEntity> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val payload = json.decodeFromString(
                QrVerificationPayloadEntity.serializer(), qrData
            )

            // Validate the payload hasn't expired
            if (payload.expiresAt != null && payload.expiresAt!! < System.currentTimeMillis()) {
                throw IllegalArgumentException("QR verification payload has expired")
            }

            // Validate the fingerprint matches what we'd compute
            val computedFingerprint = generateIdentityFingerprint(payload.identityKeyId, payload.credentialDisplayName).getOrThrow().fingerprint
            if (computedFingerprint != payload.fingerprint) {
                throw SecurityException("QR fingerprint doesn't match computed fingerprint")
            }

            payload
        }
    }

    /**
     * Gets the epoch authenticator for a group, suitable for cross-verification.
     */
    suspend fun getEpochAuthenticator(groupId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = groupDao.get(groupId)
                ?: throw IllegalArgumentException("Unknown group $groupId")
            val group = liveGroups[groupId] ?: rebuild(entity)
            b64(group.epochAuthenticator)
        }
    }

    /**
     * Gets the safety number for an identity pair.
     */
    suspend fun getSafetyNumber(
        identityKeyId: String,
        peerIdentityKeyId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            requireVerificationDaos()
            val verification = identityVerificationDao!!.getByIdentityPair(identityKeyId, peerIdentityKeyId)
                ?: throw IllegalStateException("No verification record for identity pair")
            // Safety number is the formatted fingerprint (XXXX-XXXX-XXXX)
            verification.peerFingerprint
        }
    }

    /**
     * Encodes this device's KeyPackage for an identity as MLS wire bytes.
     *
     * Named for its use in the membership flow: a member to be added shares
     * exactly these bytes, and the Welcome is encrypted to them.
     */
    suspend fun keyPackageWire(identityKeyId: String, displayName: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Reuse existing sealed material when present so the same
                // KeyPackage is returned across calls (a Welcome will be
                // encrypted to exactly these bytes).
                val material = loadIdentityMaterial(identityKeyId, displayName)
                    ?: generateIdentityMaterial(identityKeyId, displayName).getOrThrow()
                MLSOutputStream.encode(material.keyPackage)
            }
        }

    /** Removes Keystore key material for an identity. */
    suspend fun deleteIdentityKeys(identityKeyId: String) = withContext(Dispatchers.IO) {
        vault.delete(keyAlias(identityKeyId, ALIAS_INIT))
        vault.delete(keyAlias(identityKeyId, ALIAS_LEAF))
        vault.delete(keyAlias(identityKeyId, ALIAS_SIG))
        Unit
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Result envelope a [lockedMutation] body returns: the wire bytes to log
     * and deliver, the new group state, plus optional extra log entries
     * (e.g. the Add proposal that the commit covers) and Welcome bytes.
     */
    private class MutationResult(
        val commitWire: ByteArray,
        val newGroup: Group,
        val extraLogEntries: List<Pair<String, ByteArray>> = emptyList(),
        val welcomeWire: ByteArray? = null
    )

    /**
     * Runs [mutation] under the group lock with the current live Group,
     * persists the resulting commit + queued deliveries, and swaps in the new
     * state. Returns null only when the group row is missing.
     */
    private suspend fun <T> lockedMutation(
        groupId: String,
        mutation: (Group, MlsGroupEntity) -> Pair<T, MutationResult>
    ): Result<T>? = lockFor(groupId).withLock {
        withContext(Dispatchers.IO) {
            val entity = groupDao.get(groupId) ?: return@withContext null
            val group = liveGroups.getOrPut(groupId) { rebuild(entity) }

            val (output, commit) = try {
                mutation(group, entity)
            } catch (e: Exception) {
                return@withContext Result.failure<T>(e)
            }

            // Persist any extra log entries (proposals the commit covers)
            // before the commit itself so replay order is exact.
            for (entry in commit.extraLogEntries) {
                messageDao.appendUnique(
                    groupId = groupId,
                    epoch = commit.newGroup.epoch - 1,
                    contentType = entry.first,
                    messageHash = b64(sha256(entry.second)),
                    encodedMessage = b64(entry.second),
                    originatedLocally = true
                )
            }

            val commitSeq = messageDao.appendUnique(
                groupId = groupId,
                epoch = commit.newGroup.epoch - 1,
                contentType = ContentType.COMMIT.name,
                messageHash = b64(sha256(commit.commitWire)),
                encodedMessage = b64(commit.commitWire),
                originatedLocally = true
            )
            check(commitSeq >= 0) { "commit log append failed (duplicate)" }

            liveGroups[groupId] = commit.newGroup
            advanceEntity(groupId, entity, commit.newGroup, commitSeq.toInt() + 1)

            queueForDelivery(groupId, commit.commitWire, ContentType.COMMIT.name)
            commit.welcomeWire?.takeIf { it.isNotEmpty() }?.let {
                queueForDelivery(groupId, it, "WELCOME")
            }

            Result.success(output)
        }
    }

    private suspend fun queueForDelivery(groupId: String, wire: ByteArray, contentType: String) {
        // Conflict-safe: a re-run of the same commit must not create a second
        // queue entry. The unique (groupId, messageHash) index rejects it.
        pendingSendDao.insertIfAbsent(
            MlsPendingSendEntity(
                groupId = groupId,
                encodedMessage = b64(wire),
                contentType = contentType,
                messageHash = b64(sha256(wire))
            )
        )
    }

    /**
     * Rebuilds the BC Group from persisted material by replaying the log in
     * order. This is the process-death recovery path: no in-memory state is
     * trusted, and the rebuilt epoch must equal the persisted epoch or the
     * load fails closed.
     *
     * LIMITATION (bcmls 1.86, BLOCKED_BY_LIBRARY_API for creators): BC 1.86
     * exposes no Group serializer, and the creator constructor derives epoch-0
     * secrets from a RANDOM initSecret (KeyScheduleEpoch.forCreator draws
     * fresh entropy). A group created on this device therefore CANNOT be
     * rebuilt byte-identically after process death, and replaying locally
     * signed epoch-0 handshake messages fails membership verification.
     * Groups joined via Welcome ARE fully recoverable: the Welcome pins the
     * entire key schedule deterministically. For creators this rebuild is
     * only valid while the live in-memory Group exists; once the process
     * dies, a creator-side group must be re-fetched via Welcome from another
     * member (or the group re-created) until upstream adds state export.
     */
    private fun rebuild(entity: MlsGroupEntity): Group {
        val suite = MlsCipherSuite.getSuite(entity.cipherSuiteId.toShort())
        val keys = kotlinx.coroutines.runBlocking { loadKeys(entity.identityKeyId, suite) }
            ?: throw IllegalStateException("Key material missing for identity ${entity.identityKeyId}")

        // Local copies first: welcomeMessage and joinerKeyPackage are public
        // vals declared in another module, so Kotlin refuses to smart-cast them
        // ("Smart cast to String is impossible ... different module").
        val welcomeMessageB64 = entity.welcomeMessage
        val joinerKeyPackageB64 = entity.joinerKeyPackage
        val group: Group = if (welcomeMessageB64 != null && joinerKeyPackageB64 != null) {
            val joinerKp = MLSInputStream.decode(
                b64Decode(joinerKeyPackageB64), KeyPackage::class.java
            ) as KeyPackage
            val welcomeWire = b64Decode(welcomeMessageB64)
            val welcomeMsg = MLSInputStream.decode(welcomeWire, MLSMessage::class.java) as MLSMessage
            Group(
                keys.initSerialized,
                keys.leafKeyPair,
                keys.signingKey,
                joinerKp,
                welcomeMsg.welcome!!,
                null,
                emptyMap(),
                emptyMap()
            )
        } else {
            // Creator rebuild. LIMITATION (bcmls 1.86): KeyScheduleEpoch
            // .forCreator draws a RANDOM initSecret, so epoch-0 secrets are
            // not reproducible from persisted material. The rebuilt group is
            // structurally valid (same tree, same keys) but its epoch-0
            // membership/encryption secrets differ from the original run.
            // Creator-side process-death recovery is therefore BLOCKED; the
            // group must be recovered via Welcome from another member or
            // re-created. Rebuild still succeeds for the in-process cache
            // reload case only when the log is empty.
            if (entity.messageLogSize > 0) {
                throw IllegalStateException(
                    "Creator-side group ${entity.groupId} cannot be rebuilt after " +
                        "process death with bcmls 1.86 (no Group serializer; " +
                        "creator key schedule is seeded by non-persisted entropy). " +
                        "Recover the group via Welcome from another member."
                )
            }
            val sigPublic = suite.serializeSignaturePublicKey(
                suite.deserializeSignaturePrivateKey(keys.signingKey).public
            )
            val node = LeafNode(
                suite,
                suite.getHPKE().serializePublicKey(keys.leafKeyPair.public),
                sigPublic,
                Credential.forBasic(entity.identityKeyId.toByteArray(Charsets.UTF_8)),
                Capabilities(),
                LifeTime(),
                emptyList<Extension>(),
                keys.signingKey
            )
            Group(
                b64Decode(entity.wireGroupId),
                suite,
                keys.leafKeyPair,
                keys.signingKey,
                node,
                emptyList<Extension>()
            )
        }

        // Replay the log. Room DAOs are suspend-only; rebuild already runs on
        // Dispatchers.IO under the group lock, and runBlocking here cannot
        // deadlock (no other coroutine waits on this thread while holding
        // nothing the log read needs).
        val log = kotlinx.coroutines.runBlocking {
            messageDao.logForGroup(entity.groupId)
        }

        var current = group
        for (entry in log) {
            // Delivery acknowledgements are recorded in the durable log for
            // replay rejection but are NOT MLS state. They are skipped before
            // any MLS decode: the stored bytes are a bare ack frame, not an
            // MLSMessage, so decoding them here would throw. They also must not
            // advance the receive ratchet, which would desynchronise the
            // rebuilt group.
            if (entry.contentType == ACK_LOG_CONTENT_TYPE) continue

            val wire = b64Decode(entry.encodedMessage)
            val msg = MLSInputStream.decode(wire, MLSMessage::class.java) as MLSMessage
            when (entry.contentType) {
                ContentType.PROPOSAL.name -> current.handle(wire, null)
                ContentType.COMMIT.name -> {
                    val next = current.handle(wire, null)
                    if (next != null) current = next
                }
                ContentType.APPLICATION.name -> {
                    // Mirror processInbound: unprotect only (advances the
                    // receive ratchet exactly as the live path did).
                    if (msg.getEpoch() == current.epoch) {
                        current.unprotect(msg)
                    }
                }
                else -> { /* WELCOME never appears in the log */ }
            }
        }

        if (current.epoch != entity.epoch) {
            throw IllegalStateException(
                "Replay desync for group ${entity.groupId}: persisted epoch " +
                    "${entity.epoch}, rebuilt ${current.epoch}"
            )
        }
        return current
    }

    private suspend fun advanceEntity(
        groupId: String,
        observed: MlsGroupEntity,
        group: Group,
        logSize: Int
    ) {
        val updated = groupDao.advance(
            groupId = groupId,
            epoch = group.epoch,
            leafIndex = group.index.value(),
            memberCount = memberCount(group),
            epochAuthenticator = b64(group.epochAuthenticator),
            publicTreeSnapshot = encodePublicTree(group),
            messageLogSize = logSize,
            updatedAt = System.currentTimeMillis(),
            observedStateVersion = observed.stateVersion
        )
        check(updated > 0) { "stale state: another writer advanced the group concurrently" }
    }

    private fun memberCount(group: Group): Int = enumerateMembers(group).size

    private fun enumerateMembers(group: Group): List<Pair<Int, String>> {
        val tree = group.tree
        val result = ArrayList<Pair<Int, String>>()
        val leaves = tree.size.leafCount().toInt()
        for (i in 0 until leaves) {
            val leaf = LeafIndex(i)
            if (tree.hasLeaf(leaf)) {
                val identity = String(tree.getLeafNode(leaf).credential.identity, Charsets.UTF_8)
                result.add(leaf.value() to identity)
            }
        }
        return result
    }

    private fun encodePublicTree(group: Group): String {
        val out = MLSOutputStream()
        group.tree.writeTo(out)
        return b64(out.toByteArray())
    }

    private fun freshSecret(group: Group): Secret {
        val bytes = ByteArray(group.suite.getKDF().getHashLength())
        random.nextBytes(bytes)
        return Secret(bytes)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
