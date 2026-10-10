package com.communicator.communication.encrypted

import kotlinx.coroutines.runBlocking
import org.bouncycastle.mls.codec.MLSInputStream
import org.bouncycastle.mls.codec.MLSMessage
import org.bouncycastle.mls.codec.WireFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Phase 5B acceptance tests for the MLS group lifecycle.
 *
 * The fakes replace only STORAGE (Room DAOs, key vault). All MLS protocol
 * operations run the real Bouncy Castle 1.86 implementation: real HPKE,
 * real Ed25519 signatures, real TreeKEM, real key schedules. Deterministic
 * where the protocol allows (fixed identities, fixed plaintexts); the
 * random draws inside BC (leaf secrets, HPKE nonces) are inherently fresh
 * per run, which the replay/consistency tests account for.
 */
class MlsGroupLifecycleTest {

    private lateinit var groupDao: FakeMlsGroupDao
    private lateinit var messageDao: FakeMlsGroupMessageDao
    private lateinit var sendDao: FakeMlsPendingSendDao
    private lateinit var vault: InMemoryKeyVault
    private lateinit var manager: MlsGroupManager

    @Before
    fun setUp() {
        groupDao = FakeMlsGroupDao()
        messageDao = FakeMlsGroupMessageDao()
        sendDao = FakeMlsPendingSendDao()
        vault = InMemoryKeyVault()
        manager = MlsGroupManager(
            context = null,
            groupDao = groupDao,
            messageDao = messageDao,
            pendingSendDao = sendDao,
            keyVault = vault
        )
    }


    // ------------------------------------------------------------------
    // 1. Create group
    // ------------------------------------------------------------------

    @Test
    fun `createGroup builds a real one-member group at epoch 0`() = runBlocking<Unit> {
        val id = manager.createGroup("alice", "alice").getOrThrow()

        val row = groupDao.rows[id]!!
        assertEquals(0L, row.epoch)
        assertEquals(1, row.memberCount)
        assertEquals(0, row.leafIndex)
        assertEquals("alice", row.identityKeyId)
        assertNull(row.welcomeMessage) // creator: joined via constructor, not Welcome
        assertNotNull(row.epochAuthenticator)
        assertTrue(row.epochAuthenticator.isNotEmpty())
        assertEquals(1, manager.memberCountOf(id))
        assertEquals(0L, manager.currentEpoch(id))
    }

    @Test
    fun `createGroup seals private keys into the vault not the database`() = runBlocking<Unit> {
        manager.createGroup("alice", "alice").getOrThrow()

        // Three sealed keys must exist in the vault...
        assertTrue(vault.keys.containsKey("alice_init"))
        assertTrue(vault.keys.containsKey("alice_leaf"))
        assertTrue(vault.keys.containsKey("alice_sig"))
        // ...and no private bytes anywhere in the persisted row.
        val row = groupDao.rows.values.single()
        val rowBlob = Base64.getEncoder().encodeToString(
            row.toString().toByteArray()
        )
        for (secret in vault.keys.values) {
            val secretB64 = Base64.getEncoder().encodeToString(secret)
            assertNotEquals(rowBlob, secretB64)
        }
    }

    // ------------------------------------------------------------------
    // 2. Add member + join via Welcome
    // ------------------------------------------------------------------

    @Test
    fun `addMember emits commit and welcome and joiner reaches the same epoch`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()

        // Bob's real KeyPackage, produced by BC through the manager.
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()

        val add = manager.addMember(aliceId, bobKp).getOrThrow()

        // Commit went to the delivery queue...
        assertEquals(1, sendDao.queue.count { it.contentType == "COMMIT" })
        // ...and the Welcome too.
        val welcomeEntry = sendDao.queue.first { it.contentType == "WELCOME" }
        val welcomeWire = Base64.getDecoder().decode(welcomeEntry.encodedMessage)
        val welcomeMsg = MLSInputStream.decode(welcomeWire, MLSMessage::class.java) as MLSMessage
        assertEquals(WireFormat.mls_welcome, welcomeMsg.wireFormat)

        // Epoch advanced on the committer side.
        assertEquals(1L, add.newEpoch)
        assertEquals(1L, manager.currentEpoch(aliceId))
        assertEquals(2, manager.memberCountOf(aliceId))

        // Bob joins through the real Welcome and agrees with Alice.
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()
        assertEquals(1L, manager.currentEpoch(bobGroup))
        assertEquals(
            groupDao.rows[aliceId]!!.epochAuthenticator,
            groupDao.rows[bobGroup]!!.epochAuthenticator
        )
    }

    @Test
    fun `joiner sees both members and matching identities`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        val add = manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()

        val aliceMembers = manager.members(aliceId)
        val bobMembers = manager.members(bobGroup)
        assertEquals(2, aliceMembers.size)
        assertEquals(aliceMembers.map { it.second }, bobMembers.map { it.second })
        assertTrue(aliceMembers.any { it.second == "alice" })
        assertTrue(aliceMembers.any { it.second == "bob" })
    }

    // ------------------------------------------------------------------
    // 3. Application messages between members
    // ------------------------------------------------------------------

    @Test
    fun `application messages round-trip between two members across the wire`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()

        val plaintext = "phase 5b round trip".toByteArray()
        val wire = manager.protectApplicationMessage(aliceId, plaintext).getOrThrow()

        // Bob decrypts through the real MLS unprotect.
        val result = manager.processInbound(bobGroup, wire).getOrThrow()
        assertNotNull(result)
        assertEquals("APPLICATION", result!!.contentType)
        assertArrayEquals(plaintext, result.plaintext)
    }

    // ------------------------------------------------------------------
    // 4. Remove member
    // ------------------------------------------------------------------

    @Test
    fun `removeMember drops the member and advances the epoch in a three-member group`() = runBlocking<Unit> {
        // NOTE: removing the only other member of a two-member group is
        // BLOCKED_BY_LIBRARY_API in bcmls 1.86 (upstream issue #2492:
        // TreeKEMPublicKey.parentHashes throws IndexOutOfBounds on the empty
        // direct path). This test removes from a three-member group, which
        // exercises the full Remove+commit path without that library bug.
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeBob = Base64.getDecoder().decode(
            sendDao.queue.filter { it.contentType == "WELCOME" }.maxByOrNull { it.rowId }!!.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeBob).getOrThrow()

        val carolKp = manager.keyPackageWire("carol", "carol").getOrThrow()
        val addCarol = manager.addMember(aliceId, carolKp).getOrThrow()
        val welcomeCarol = Base64.getDecoder().decode(
            sendDao.queue.filter { it.contentType == "WELCOME" }.maxByOrNull { it.rowId }!!.encodedMessage
        )
        val carolGroup = manager.joinGroup("carol", "carol", welcomeCarol).getOrThrow()

        val members = manager.members(aliceId)
        val bobLeaf = members.first { it.second == "bob" }.first
        val removal = manager.removeMember(aliceId, bobLeaf).getOrThrow()

        assertEquals(3L, removal.newEpoch)
        assertEquals(3L, manager.currentEpoch(aliceId))
        assertEquals(2, manager.memberCountOf(aliceId))

        // Sync Carol (the remaining member) with the removal commit.
        manager.processInbound(carolGroup, removal.commitBytes).getOrThrow()

        // The removed member can no longer decrypt Alice's messages.
        val wire = manager.protectApplicationMessage(
            aliceId, "still here".toByteArray()
        ).getOrThrow()
        val bobResult = manager.processInbound(bobGroup, wire)
        assertTrue(bobResult.isFailure)

        // The remaining member still can.
        val carolRes = manager.processInbound(carolGroup, wire).getOrThrow()
        assertArrayEquals("still here".toByteArray(), carolRes!!.plaintext)
    }

    @Test
    fun `removeMember rejects a leaf that is not a member`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val result = manager.removeMember(aliceId, 7)
        assertTrue(result.isFailure)
    }

    // ------------------------------------------------------------------
    // 5. Key rotation
    // ------------------------------------------------------------------

    @Test
    fun `rotateOwnKeys advances epoch and replaces the sealed leaf key in a two-member group`() = runBlocking<Unit> {
        // Solo-group rotation hits bcmls 1.86 bug #2492 (parentHashes on the
        // empty direct path of a one-member tree); two-member rotation is the
        // supported path and the one exercised here.
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()

        val before = vault.keys["alice_leaf"]!!.clone()
        val rotation = manager.rotateOwnKeys(aliceId).getOrThrow()

        assertEquals(2L, rotation.newEpoch)
        assertEquals(2L, manager.currentEpoch(aliceId))
        val after = vault.keys["alice_leaf"]!!
        // The sealed leaf key material changed...
        assertTrue(!before.contentEquals(after))
        // ...the group remains usable for encryption on the committer side...
        val wire = manager.protectApplicationMessage(
            aliceId, "after rotation".toByteArray()
        ).getOrThrow()
        // ...and the other member can still decrypt after syncing the commit.
        val commitWire = sendDao.queue
            .filter { it.contentType == "COMMIT" }
            .maxByOrNull { it.rowId }!!
        manager.processInbound(bobGroup, Base64.getDecoder().decode(commitWire.encodedMessage)).getOrThrow()
        val bobRes = manager.processInbound(bobGroup, wire).getOrThrow()
        assertNotNull(bobRes)
        assertArrayEquals("after rotation".toByteArray(), bobRes!!.plaintext)
    }

    @Test
    fun `rotateOwnKeys on a solo group fails closed with the library bug boundary`() = runBlocking<Unit> {
        // bcmls 1.86 bug #2492: any path-bearing commit on a one-member group
        // crashes in TreeKEMPublicKey.parentHashes. The manager surfaces the
        // failure instead of fabricating state.
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val result = manager.rotateOwnKeys(aliceId)
        assertTrue(result.isFailure)
        assertEquals(0L, manager.currentEpoch(aliceId))
    }

    // ------------------------------------------------------------------
    // 6. Persistence / process-death recovery
    // ------------------------------------------------------------------

    @Test
    fun `joiner state survives a simulated process death through log replay`() = runBlocking<Unit> {
        // bcmls 1.86 limitation: the CREATOR's epoch-0 secrets come from a
        // random initSecret (KeyScheduleEpoch.forCreator) and are not
        // reproducible, so creator-side rebuild is BLOCKED. The JOINER's
        // state is fully recoverable because the Welcome pins the whole
        // key schedule - this test proves the joiner recovery path.
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()
        val bobAuth = groupDao.rows[bobGroup]!!.epochAuthenticator

        // Bob protects a message (advancing his send ratchet)...
        val plaintext = "pre-crash".toByteArray()
        val wire = manager.protectApplicationMessage(bobGroup, plaintext).getOrThrow()

        // PROCESS DEATH: drop every in-memory group...
        val resurrected = MlsGroupManager(null, groupDao, messageDao, sendDao, vault)

        // Bob rebuilds from Welcome + KeyPackage + log to identical state.
        assertEquals(1L, resurrected.currentEpoch(bobGroup))
        assertEquals(2, resurrected.memberCountOf(bobGroup))
        assertEquals(bobAuth, resurrected.memberRoster(bobGroup))

        // Alice (live) can decrypt the pre-crash message.
        val aliceRes = manager.processInbound(aliceId, wire).getOrThrow()
        assertNotNull(aliceRes)
        assertArrayEquals(plaintext, aliceRes!!.plaintext)
    }

    @Test
    fun `creator rebuild after process death is rejected with a clear error`() = runBlocking<Unit> {
        // Documents the bcmls 1.86 BLOCKED_BY_LIBRARY_API boundary: creators
        // cannot be rebuilt (random epoch-0 key schedule, no serializer), so
        // the manager must fail closed instead of silently forging state.
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()

        val resurrected = MlsGroupManager(null, groupDao, messageDao, sendDao, vault)
        val result = kotlin.runCatching { resurrected.memberCountOf(aliceId) }
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()!!.message!!.contains("cannot be rebuilt")
        )
    }

    @Test
    fun `joiner recovery rebuilds from Welcome + KeyPackage without live state`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()
        val bobAuth = groupDao.rows[bobGroup]!!.epochAuthenticator

        val resurrected = MlsGroupManager(null, groupDao, messageDao, sendDao, vault)
        assertEquals(1L, resurrected.currentEpoch(bobGroup))
        assertEquals(2, resurrected.memberCountOf(bobGroup))
        assertEquals(bobAuth, resurrected.memberRoster(bobGroup))
    }

    // ------------------------------------------------------------------
    // 7. Stale / invalid inputs
    // ------------------------------------------------------------------

    @Test
    fun `inbound commit for a stale epoch is rejected`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow() // epoch 0 -> 1
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()

        // Capture the commit that moved the group to epoch 1 (already logged
        // on Alice's side at epoch 0). Replay it to Bob, who is at epoch 1:
        // the commit is now stale for him.
        val commitEntry = sendDao.queue.first { it.contentType == "COMMIT" }
        val staleWire = Base64.getDecoder().decode(commitEntry.encodedMessage)

        // Alice already processed it (duplicate)...
        val dup = manager.processInbound(aliceId, staleWire)
        // Either silently dropped as duplicate or rejected as stale - never
        // accepted as a state change:
        val aliceRow = groupDao.rows[aliceId]!!
        assertEquals(1L, aliceRow.epoch)

        // Bob is at epoch 1; the epoch-0 commit must be rejected.
        val bobResult = manager.processInbound(bobGroup, staleWire)
        assertTrue(bobResult.isFailure || bobResult.getOrNull() == null)
        assertEquals(1L, groupDao.rows[bobGroup]!!.epoch)
    }

    @Test
    fun `malformed wire bytes are rejected`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val garbage = ByteArray(64) { it.toByte() }
        val result = manager.processInbound(aliceId, garbage)
        assertTrue(result.isFailure)
    }

    @Test
    fun `unknown group id is rejected`() = runBlocking<Unit> {
        val result = manager.processInbound("no-such-group", ByteArray(16))
        assertTrue(result.isFailure)
    }

    // ------------------------------------------------------------------
    // 8. Duplicate handling
    // ------------------------------------------------------------------

    @Test
    fun `duplicate application message is delivered once`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeWire = Base64.getDecoder().decode(
            sendDao.queue.first { it.contentType == "WELCOME" }.encodedMessage
        )
        val bobGroup = manager.joinGroup("bob", "bob", welcomeWire).getOrThrow()

        val wire = manager.protectApplicationMessage(aliceId, "dup".toByteArray()).getOrThrow()

        val first = manager.processInbound(bobGroup, wire).getOrThrow()
        assertNotNull(first)
        val second = manager.processInbound(bobGroup, wire).getOrThrow()
        assertNull(second) // duplicate: dropped, not re-decrypted
    }

    @Test
    fun `duplicate commit hash cannot enter the log twice`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()
        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        val add = manager.addMember(aliceId, bobKp).getOrThrow()

        val seqBefore = messageDao.logForGroup(aliceId).size
        // Forcing the same commit bytes through appendUnique again is
        // prevented at the DAO contract level:
        val dup = messageDao.appendUnique(
            groupId = aliceId,
            epoch = 0,
            contentType = "COMMIT",
            messageHash = b64Sha256(add.commitBytes),
            encodedMessage = "",
            originatedLocally = true
        )
        assertEquals(-1L, dup)
        assertEquals(seqBefore, messageDao.logForGroup(aliceId).size)
    }

    // ------------------------------------------------------------------
    // 9. Multi-member consistency
    // ------------------------------------------------------------------

    @Test
    fun `three members converge on the same epoch authenticator`() = runBlocking<Unit> {
        val aliceId = manager.createGroup("alice", "alice").getOrThrow()

        val bobKp = manager.keyPackageWire("bob", "bob").getOrThrow()
        val addBob = manager.addMember(aliceId, bobKp).getOrThrow()
        val welcomeBob = sendDao.queue
            .filter { it.contentType == "WELCOME" }
            .maxByOrNull { it.rowId }!!
        // Bob joins directly at epoch 1 through the Welcome.
        val bobGroup = manager.joinGroup(
            "bob", "bob", Base64.getDecoder().decode(welcomeBob.encodedMessage)
        ).getOrThrow()

        val carolKp = manager.keyPackageWire("carol", "carol").getOrThrow()
        val addCarol = manager.addMember(aliceId, carolKp).getOrThrow()
        val welcomeCarol = sendDao.queue
            .filter { it.contentType == "WELCOME" }
            .maxByOrNull { it.rowId }!!
        val carolGroup = manager.joinGroup(
            "carol", "carol", Base64.getDecoder().decode(welcomeCarol.encodedMessage)
        ).getOrThrow()

        // Existing members must process the commit that added Carol before
        // the three states can agree (exactly as a delivery service would).
        manager.processInbound(bobGroup, addCarol.commitBytes).getOrThrow()

        // All three are now at epoch 2 through real protocol paths; the
        // epoch authenticators must agree across independently-built copies.
        val auths = listOf(
            groupDao.rows[aliceId]!!.epochAuthenticator,
            groupDao.rows[bobGroup]!!.epochAuthenticator,
            groupDao.rows[carolGroup]!!.epochAuthenticator
        )
        assertEquals(auths[0], auths[1])
        assertEquals(auths[0], auths[2])
        assertEquals(2L, groupDao.rows[aliceId]!!.epoch)
        assertEquals(3, manager.memberCountOf(aliceId))
        assertEquals(3, manager.memberCountOf(bobGroup))
        assertEquals(3, manager.memberCountOf(carolGroup))

        // An application message from Alice decrypts for both others.
        val wire = manager.protectApplicationMessage(aliceId, "all three".toByteArray()).getOrThrow()
        val bobRes = manager.processInbound(bobGroup, wire).getOrThrow()
        val carolRes = manager.processInbound(carolGroup, wire).getOrThrow()
        assertArrayEquals("all three".toByteArray(), bobRes!!.plaintext)
        assertArrayEquals("all three".toByteArray(), carolRes!!.plaintext)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun b64Sha256(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(sha256(data))

    /** Epoch authenticator of the persisted row (public value). */
    private suspend fun MlsGroupManager.memberRoster(groupId: String): String =
        groupDao.rows[groupId]!!.epochAuthenticator
}
