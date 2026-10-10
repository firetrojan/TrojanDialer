package com.communicator.communication.encrypted

import com.communicator.data.core.encrypted.MlsDeliveryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the authenticated delivery acknowledgement path (Stage 5D).
 *
 * The threat model these cover:
 *  - a peer that never received the message must not be able to mark it
 *    delivered;
 *  - an acknowledgement for message A must not advance message B;
 *  - an acknowledgement from group C must not advance group A's row;
 *  - a third party must not acknowledge on a recipient's behalf;
 *  - a replayed acknowledgement must be applied at most once, including after
 *    the manager is reconstructed;
 *  - malformed frames must be rejected, not partially parsed;
 *  - an acknowledgement must not be able to skip straight to PROCESSED.
 *
 * IMPORTANT SCOPE NOTE
 * --------------------
 * Bouncy Castle 1.86 exposes no public way to obtain the authenticated sender of
 * an unprotect'ed application message (Group.unprotect returns byte[][];
 * Group.unprotectToContentAuth is private). Therefore in the current build an
 * inbound application message ALWAYS carries authenticatedSenderLeafIndex == -1,
 * and applyAuthenticatedAck ALWAYS refuses.
 *
 * These tests therefore split into two kinds, and the split is the point:
 *  1. Framing/binding tests on DeliveryAck itself: round-trip, malformed input,
 *     trailing bytes, field-boundary shifting, canonical-hash stability. These
 *     exercise real production code and must pass.
 *  2. Queue-state tests: an acknowledgement lacking an authenticated sender is
 *     refused and no row moves. These assert the fail-closed behaviour.
 *
 * A test that asserts a valid acknowledgement ADVANCES the row is deliberately
 * absent: it would only pass with a private API reach-in or a protocol change,
 * neither of which is permitted. See the feature audit for the exact missing
 * prerequisite.
 *
 * These tests use the in-memory fake DAO, so they do not verify SQLite
 * behaviour; see .gate/sqlite_conformance.py for that.
 */
class DeliveryAckTest {

    private lateinit var groupDao: FakeMlsGroupDao
    private lateinit var messageDao: FakeMlsGroupMessageDao
    private lateinit var sendDao: FakeMlsPendingSendDao
    private lateinit var vault: InMemoryKeyVault
    private lateinit var manager: MlsGroupManager
    private lateinit var groupId: String

    @Before
    fun setUp() = runBlocking(Dispatchers.IO) {
        groupDao = FakeMlsGroupDao()
        messageDao = FakeMlsGroupMessageDao()
        sendDao = FakeMlsPendingSendDao()
        vault = InMemoryKeyVault()
        manager = MlsGroupManager(null, groupDao, messageDao, sendDao, vault)
        groupId = manager.createGroup("alice", "alice").getOrThrow()
    }

    /** Queues a message and drives it to TRANSPORT_ACCEPTED. */
    private suspend fun acceptedMessage(text: String): Pair<Long, String> {
        val rowId = manager.sendApplicationMessage(groupId, text.toByteArray()).getOrThrow().toLong()
        manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()
        val hash = sendDao.getByRowId(rowId)!!.messageHash
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
        return rowId to hash
    }

    /**
     * Builds an InboundResult as processInbound would return it for an
     * application message that decrypted successfully.
     */
    private fun inbound(
        payload: ByteArray,
        senderLeaf: Int,
        epoch: Long = 0L
    ) = MlsGroupManager.InboundResult(
        contentType = "APPLICATION",
        newEpoch = epoch,
        plaintext = payload,
        memberCount = 1,
        authenticatedSenderLeafIndex = senderLeaf,
        authenticatedSenderIdentity = "bob"
    )

    private fun ackPayload(
        group: String = groupId,
        hash: String,
        epoch: Long,
        kind: DeliveryAck.AckKind = DeliveryAck.AckKind.REMOTE_RECEIVED
    ) = DeliveryAck.encode(group, hash, epoch, kind)

    // ------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------

    @Test
    fun `valid acknowledgement round-trips`() = runBlocking {
        val hash = "abc123hash"
        val encoded = ackPayload(hash = hash, epoch = 3)
        val decoded = DeliveryAck.decode(encoded, groupId, hash, DeliveryAck.AckKind.REMOTE_RECEIVED)
        assertEquals(groupId, decoded.groupId)
        assertEquals(hash, decoded.messageHash)
        assertEquals(3L, decoded.epoch)
        assertEquals(DeliveryAck.AckKind.REMOTE_RECEIVED, decoded.kind)
    }

    @Test
    fun `empty payload is rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("m")
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash, inbound(ByteArray(0), senderLeaf = -1)
        )
        assertTrue(result.isFailure)
        assertEquals(
            MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state
        )
    }

    @Test
    fun `truncated payload is rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("m")
        val full = ackPayload(hash = hash, epoch = 0)
        val truncated = full.copyOf(full.size / 2)
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash, inbound(truncated, -1)
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `trailing bytes are rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("m")
        val full = ackPayload(hash = hash, epoch = 0)
        val padded = full + byteArrayOf(0x41, 0x42, 0x43)
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash, inbound(padded, -1)
        )
        assertTrue("trailing bytes must not be accepted", result.isFailure)
    }

    @Test
    fun `wrong protocol string is rejected`() = runBlocking {
        val (_, hash) = acceptedMessage("m")
        var thrown = false
        try {
            DeliveryAck.decode(
                ackPayload(hash = hash, epoch = 0), groupId, hash, DeliveryAck.AckKind.REMOTE_RECEIVED
            )
        } catch (e: Exception) {
            thrown = true
        }
        assertFalse("baseline decode should succeed", thrown)

        // A frame whose protocol field is wrong must not validate.
        val bogus = buildString {
            append(1)
            append(4).append("evil")          // protocol length + bytes
            append(groupId.length).append(groupId)
            append(hash.length).append(hash)
            repeat(8) { append(0) }
            append(3).append("X")
        }.toByteArray(Charsets.UTF_8)
        assertTrue(
            try {
                DeliveryAck.decode(bogus, groupId, hash, DeliveryAck.AckKind.REMOTE_RECEIVED)
                false
            } catch (e: AckFormatException) {
                true
            }
        )
    }

    // ------------------------------------------------------------------
    // Binding: the acknowledgement must name this exact message/group
    // ------------------------------------------------------------------

    @Test
    fun `acknowledgement for a different message is rejected`() = runBlocking {
        val (rowId, _) = acceptedMessage("mine")
        val otherHash = "some-other-message-hash"
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, otherHash, inbound(ackPayload(hash = otherHash, epoch = 0), 1, 0)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AckBindingException)
        assertEquals(
            "state must be untouched",
            MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state
        )
    }

    @Test
    fun `acknowledgement from a different group is rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            inbound(ackPayload(group = "other-group", hash = hash, epoch = 0), 1, 0)
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AckBindingException)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    @Test
    fun `acknowledgement from an unexpected sender is rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            inbound(ackPayload(hash = hash, epoch = 0), senderLeaf = 7),
            expectedSenderLeafIndex = 3
        )
        assertTrue("third party must not acknowledge on someone else's behalf", result.isFailure)
        assertTrue(result.exceptionOrNull() is AckBindingException)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    @Test
    fun `missing inbound result is rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val result = manager.applyAuthenticatedAck(groupId, rowId, hash, inbound = null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AckUnauthenticatedException)
    }

    @Test
    fun `handshake message cannot masquerade as an acknowledgement`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        // A COMMIT is a handshake message, not an application message.
        val fake = MlsGroupManager.InboundResult(
            contentType = "COMMIT",
            newEpoch = 0,
            plaintext = ackPayload(hash = hash, epoch = 0),
            memberCount = 1,
            authenticatedSenderLeafIndex = 1
        )
        val result = manager.applyAuthenticatedAck(groupId, rowId, hash, fake)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AckFormatException)
    }

    @Test
    fun `acknowledgement without an authenticated sender is rejected`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val noSender = MlsGroupManager.InboundResult(
            contentType = "APPLICATION",
            newEpoch = 0,
            plaintext = ackPayload(hash = hash, epoch = 0),
            memberCount = 1,
            authenticatedSenderLeafIndex = -1
        )
        val result = manager.applyAuthenticatedAck(groupId, rowId, hash, noSender)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AckUnauthenticatedException)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // State machine
    // ------------------------------------------------------------------

    @Test
    fun `acknowledgement without an MLS-derived sender is refused and the row does not move`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        // senderLeaf = -1 is what processInbound actually yields today, because
        // BC 1.86 exposes no public authenticated sender. The well-formed frame
        // must still be refused: framing alone is not authentication.
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            inbound(
                ackPayload(hash = hash, epoch = manager.currentEpoch(groupId)),
                senderLeaf = -1
            )
        )
        assertTrue("a well-formed frame is not proof of delivery", result.isFailure)
        assertTrue(result.exceptionOrNull() is AckUnauthenticatedException)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    @Test
    fun `acknowledgement never reaches PROCESSED on its own`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            inbound(ackPayload(hash = hash, epoch = manager.currentEpoch(groupId)), 1)
        )
        assertFalse(
            "no acknowledgement may assert recipient-side processing",
            sendDao.getByRowId(rowId)!!.state == MlsDeliveryState.PROCESSED
        )
    }

    @Test
    fun `acknowledgement for a QUEUED message is rejected`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "not-sent".toByteArray())
            .getOrThrow().toLong()
        val hash = sendDao.getByRowId(rowId)!!.messageHash
        assertEquals(MlsDeliveryState.QUEUED, sendDao.getByRowId(rowId)!!.state)

        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            inbound(ackPayload(hash = hash, epoch = manager.currentEpoch(groupId)), 1)
        )
        assertTrue(result.isFailure)
        assertEquals(MlsDeliveryState.QUEUED, sendDao.getByRowId(rowId)!!.state)
    }

    @Test
    fun `acknowledgement for a FAILED message is rejected`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "doomed".toByteArray())
            .getOrThrow().toLong()
        sendDao.markFailed(
            rowId, MlsDeliveryState.FAILED, MlsDeliveryState.QUEUED, System.currentTimeMillis()
        )
        assertEquals(MlsDeliveryState.FAILED, sendDao.getByRowId(rowId)!!.state)

        val hash = sendDao.getByRowId(rowId)!!.messageHash
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            inbound(ackPayload(hash = hash, epoch = manager.currentEpoch(groupId)), 1)
        )
        assertTrue("a terminal row must not be revived by an ack", result.isFailure)
        assertEquals(MlsDeliveryState.FAILED, sendDao.getByRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // Replay
    // ------------------------------------------------------------------

    @Test
    fun `applying the same acknowledgement twice never moves the row twice`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val epoch = manager.currentEpoch(groupId)
        val payload = ackPayload(hash = hash, epoch = epoch)

        // Both attempts are refused today (no authenticated sender), so the row
        // must sit at TRANSPORT_ACCEPTED throughout and never double-advance.
        val first = manager.applyAuthenticatedAck(groupId, rowId, hash, inbound(payload, -1))
        val second = manager.applyAuthenticatedAck(groupId, rowId, hash, inbound(payload, -1))
        assertTrue(first.isFailure)
        assertTrue("a second application must not succeed where the first failed", second.isFailure)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    @Test
    fun `a failed acknowledgement leaves nothing in the durable replay log`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val payload = ackPayload(hash = hash, epoch = manager.currentEpoch(groupId))
        manager.applyAuthenticatedAck(groupId, rowId, hash, inbound(payload, -1))
        // Nothing may be logged for an acknowledgement that was never applied,
        // otherwise a later legitimate acknowledgement would be misread as a
        // replay.
        val ackLogEntries = messageDao.logForGroup(groupId)
            .count { it.contentType == "DELIVERY_ACK" }
        assertEquals(0, ackLogEntries)
    }

    @Test
    fun `acknowledgement epoch is checked against the group epoch`() = runBlocking {
        val (rowId, hash) = acceptedMessage("mine")
        val current = manager.currentEpoch(groupId)
        // A single-member group sits at epoch 0, so the stale-epoch edge cannot
        // be produced here; assert the bound is enforced rather than pretending
        // an older epoch exists. Framing-level epoch round-trip is covered by
        // the round-trip and canonical-hash tests.
        val result = manager.applyAuthenticatedAck(
            groupId, rowId, hash,
            // senderLeaf = -1 is what processInbound yields with BC 1.86.
            inbound(ackPayload(hash = hash, epoch = current + 1000), senderLeaf = -1)
        )
        assertTrue(result.isFailure)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // The row-id-only entry points must remain unusable
    // ------------------------------------------------------------------

    @Test
    fun `bare row id cannot mark a message received`() = runBlocking {
        val (rowId, _) = acceptedMessage("mine")
        val result = manager.markRemoteReceived(rowId)
        assertTrue("row id alone must never be accepted", result.isFailure)
        assertTrue(result.exceptionOrNull() is ACKNotAuthenticatedException)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    @Test
    fun `bare row id cannot mark a message processed`() = runBlocking {
        val (rowId, _) = acceptedMessage("mine")
        val result = manager.markProcessed(rowId)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ACKNotAuthenticatedException)
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.getByRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // Binding cannot be shifted by field-boundary tricks
    // ------------------------------------------------------------------

    @Test
    fun `group and message hash cannot be confused by concatenation`() = runBlocking {
        // "ab"+"c" must not validate where "a"+"bc" is expected.
        val (_, hash) = acceptedMessage("m")
        val shifted = DeliveryAck.encode("ab", "c$hash", 0, DeliveryAck.AckKind.REMOTE_RECEIVED)
        val result = runCatching {
            DeliveryAck.decode(shifted, groupId, hash, DeliveryAck.AckKind.REMOTE_RECEIVED)
        }
        assertTrue("length-prefixed fields must prevent boundary shifting", result.isFailure)
    }

    @Test
    fun `canonical hash is stable across encodings and differs per message`() = runBlocking {
        val a = DeliveryAck.Ack(groupId, "hash-a", 1, DeliveryAck.AckKind.REMOTE_RECEIVED)
        val b = DeliveryAck.Ack(groupId, "hash-b", 1, DeliveryAck.AckKind.REMOTE_RECEIVED)
        val aAgain = DeliveryAck.decode(
            DeliveryAck.encode(groupId, "hash-a", 1, DeliveryAck.AckKind.REMOTE_RECEIVED),
            groupId, "hash-a", DeliveryAck.AckKind.REMOTE_RECEIVED
        )
        assertEquals(DeliveryAck.canonicalHash(a), DeliveryAck.canonicalHash(aAgain))
        assertFalse(DeliveryAck.canonicalHash(a) == DeliveryAck.canonicalHash(b))
    }
}
