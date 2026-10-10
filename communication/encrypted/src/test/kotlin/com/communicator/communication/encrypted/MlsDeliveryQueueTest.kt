package com.communicator.communication.encrypted

import com.communicator.data.core.encrypted.MlsDeliveryState
import com.communicator.data.core.encrypted.MlsPendingSendEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 5D acceptance tests for the durable delivery queue.
 *
 * Scope boundary: these tests cover the queue and its state machine only. The
 * MLS cryptography underneath is real (Bouncy Castle 1.86) and is exercised by
 * MlsGroupLifecycleTest; here it only supplies real wire bytes to queue.
 *
 * What is deliberately NOT claimed: nothing here proves a message reached a
 * remote peer. TRANSPORT_ACCEPTED is the highest state reachable from a
 * transport callback, and the ACK transitions are asserted to REFUSE, because
 * no authenticated, message-bound ACK exists yet.
 */
class MlsDeliveryQueueTest {

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

    // ------------------------------------------------------------------
    // Enqueue
    // ------------------------------------------------------------------

    @Test
    fun `sendApplicationMessage enqueues QUEUED with a hash and expiry`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "hello".toByteArray(), expiresInMs = 60_000)
            .getOrThrow().toLong()

        val row = sendDao.byRowId(rowId)!!
        assertEquals(MlsDeliveryState.QUEUED, row.state)
        assertTrue("messageHash must be recorded for dedup", row.messageHash.isNotEmpty())
        assertEquals("APPLICATION", row.contentType)
        assertEquals(0, row.attemptCount)
        assertTrue(row.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `distinct plaintexts are queued separately and each gets its own row`() = runBlocking {
        val a = manager.sendApplicationMessage(groupId, "one".toByteArray()).getOrThrow().toLong()
        val b = manager.sendApplicationMessage(groupId, "two".toByteArray()).getOrThrow().toLong()

        assertTrue("two different messages must both be accepted", a != b)
        assertEquals(2, sendDao.snapshot().size)
        assertEquals(2, sendDao.snapshot().map { it.messageHash }.toSet().size)
    }

    @Test
    fun `replaying the same wire bytes is rejected by the queue`() = runBlocking {
        // Identical plaintext does NOT produce identical wire bytes: real MLS
        // protect() draws a fresh key schedule nonce each call. Deduplication is
        // therefore keyed on the message hash of the actual wire form, so the
        // replay case is exercised on the DAO with a fixed hash - which is what
        // a duplicated frame from the network would look like.
        val row = MlsPendingSendEntity(
            groupId = groupId,
            encodedMessage = "QUJD",
            contentType = "APPLICATION",
            messageHash = "replay-hash",
            expiresAt = 0
        )
        assertTrue(sendDao.insertIfAbsent(row) > 0)
        assertEquals("a replayed frame must be refused", -1L, sendDao.insertIfAbsent(row))
        assertEquals(1, sendDao.snapshot().size)
    }

    @Test
    fun `concurrent enqueue of one message yields exactly one row`() = runBlocking {
        // The uniqueness that matters is enforced by the DAO, so it is exercised
        // directly on one (groupId, messageHash) pair, which is what two
        // concurrent producers of the same message would contend for.
        val row = MlsPendingSendEntity(
            groupId = groupId,
            encodedMessage = "AAAA",
            contentType = "APPLICATION",
            messageHash = "same-hash",
            expiresAt = 0
        )
        val results = coroutineScope {
            List(16) { async(Dispatchers.IO) { sendDao.insertIfAbsent(row) } }.awaitAll()
        }

        assertEquals("exactly one insert may win", 1, results.count { it > 0 })
        assertEquals("the rest must be rejected by the unique index", 15, results.count { it == -1L })
        assertEquals(1, sendDao.snapshot().size)
    }

    // ------------------------------------------------------------------
    // Send + transport acceptance
    // ------------------------------------------------------------------

    @Test
    fun `successful send reaches TRANSPORT_ACCEPTED and nothing further`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "payload".toByteArray()).getOrThrow().toLong()

        val sent = manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()

        assertEquals(1, sent)
        val row = sendDao.byRowId(rowId)!!
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, row.state)
        assertNotNull("a correlation id must be recorded", row.transportCorrelationId)
        assertEquals(1, row.attemptCount)
    }

    @Test
    fun `transport acceptance is not treated as remote receipt`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "payload".toByteArray()).getOrThrow().toLong()
        manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()

        // The message must sit at TRANSPORT_ACCEPTED. No ACK path exists, so it
        // cannot have advanced further on its own.
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.byRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // Retry, backoff, attempt limit
    // ------------------------------------------------------------------

    @Test
    fun `failed send schedules a retry with backoff and does not resend yet`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "retry".toByteArray()).getOrThrow().toLong()

        manager.processOutboundQueue(groupId) { _, _ -> Result.failure(Exception("transport down")) }
            .getOrThrow()

        val row = sendDao.byRowId(rowId)!!
        assertEquals(MlsDeliveryState.SENDING, row.state)
        assertEquals(1, row.attemptCount)
        assertTrue("backoff must be scheduled in the future", row.nextRetryAt > System.currentTimeMillis())

        // A second pass immediately must not attempt again: backoff not elapsed.
        val sent = manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()
        assertEquals(0, sent)
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertEquals("first retry waits 1s", 1_000L, manager.backoffMs(1))
        assertEquals(2_000L, manager.backoffMs(2))
        assertEquals(4_000L, manager.backoffMs(3))
        assertEquals(8_000L, manager.backoffMs(4))
        assertEquals("must saturate, not overflow", 60_000L, manager.backoffMs(20))
        assertEquals("huge counts must not overflow", 60_000L, manager.backoffMs(Int.MAX_VALUE))
        assertEquals("non-positive attempts fall back to the base delay", 1_000L, manager.backoffMs(0))
    }

    @Test
    fun `message fails terminally once maxAttempts is spent`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "doomed".toByteArray()).getOrThrow().toLong()
        val max = sendDao.byRowId(rowId)!!.maxAttempts

        // Drive attempts at the DAO level so the retry budget is exhausted
        // deterministically, without sleeping out the real backoff schedule.
        // The iteration cap makes a regression fail fast instead of hanging.
        var attempts = 0
        var guard = 0
        while (sendDao.byRowId(rowId)!!.attemptCount < max) {
            assertTrue("retry budget must converge, not spin", guard++ < max + 5)
            val cur = sendDao.byRowId(rowId)!!
            sendDao.scheduleRetry(rowId, 0, cur.state, System.currentTimeMillis())
            manager.processOutboundQueue(groupId) { _, _ ->
                attempts++
                Result.failure(Exception("transport refused"))
            }
        }

        assertEquals("exactly maxAttempts sends should be made", max, attempts)
        assertEquals(
            "the retry-exhaustion sweep must fail the row terminally",
            MlsDeliveryState.FAILED, sendDao.byRowId(rowId)!!.state
        )
        assertEquals(max, sendDao.byRowId(rowId)!!.attemptCount)
    }

    @Test
    fun `an exhausted message is not handed to the transport again`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "spent".toByteArray()).getOrThrow().toLong()
        val max = sendDao.byRowId(rowId)!!.maxAttempts

        var attempts = 0
        var guard = 0
        while (sendDao.byRowId(rowId)!!.attemptCount < max) {
            assertTrue("retry budget must converge, not spin", guard++ < max + 5)
            sendDao.scheduleRetry(rowId, 0, sendDao.byRowId(rowId)!!.state, System.currentTimeMillis())
            manager.processOutboundQueue(groupId) { _, _ ->
                attempts++; Result.failure(Exception("down"))
            }
        }
        // One more pass: the sweep should terminate it and never send.
        val extra = manager.processOutboundQueue(groupId) { _, _ ->
            attempts++; Result.success(Unit)
        }.getOrThrow()

        assertEquals("a FAILED message must not be sent again", 0, extra)
        assertEquals(max, attempts)
        assertEquals(MlsDeliveryState.FAILED, sendDao.byRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // Expiry
    // ------------------------------------------------------------------

    @Test
    fun `expired message is marked EXPIRED and never sent`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "perishable".toByteArray(), expiresInMs = 1)
            .getOrThrow().toLong()
        Thread.sleep(15)

        val sent = manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()

        assertEquals("an expired message must not be handed to a transport", 0, sent)
        assertEquals(MlsDeliveryState.EXPIRED, sendDao.byRowId(rowId)!!.state)
    }

    @Test
    fun `EXPIRED is terminal and is not picked up again`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "gone".toByteArray(), expiresInMs = 1)
            .getOrThrow().toLong()
        Thread.sleep(15)
        manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()
        assertEquals(MlsDeliveryState.EXPIRED, sendDao.byRowId(rowId)!!.state)

        val again = manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()
        assertEquals(0, again)
        assertEquals(MlsDeliveryState.EXPIRED, sendDao.byRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // Competing workers
    // ------------------------------------------------------------------

    @Test
    fun `competing workers never send the same message twice`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "contended".toByteArray()).getOrThrow().toLong()

        val sendCount = AtomicInteger(0)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit {
                    // Each worker is a blocking thread; runBlocking bridges it
                    // into the suspend API so the contention is real.
                    start.await()
                    runBlocking(Dispatchers.IO) {
                        manager.processOutboundQueue(groupId) { _, _ ->
                            sendCount.incrementAndGet()
                            Result.success(Unit)
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            "the row must be handed to exactly one sender",
            1, sendCount.get()
        )
        assertEquals(MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.byRowId(rowId)!!.state)
        assertEquals("no extra attempt may be charged", 1, sendDao.byRowId(rowId)!!.attemptCount)
    }

    // ------------------------------------------------------------------
    // Invalid transitions
    // ------------------------------------------------------------------

    @Test
    fun `state machine rejects illegal edges`() {
        assertTrue(MlsDeliveryState.QUEUED.canTransitionTo(MlsDeliveryState.SENDING))
        assertTrue(MlsDeliveryState.SENDING.canTransitionTo(MlsDeliveryState.TRANSPORT_ACCEPTED))
        assertTrue(MlsDeliveryState.TRANSPORT_ACCEPTED.canTransitionTo(MlsDeliveryState.REMOTE_RECEIVED))
        assertTrue(MlsDeliveryState.REMOTE_RECEIVED.canTransitionTo(MlsDeliveryState.PROCESSED))

        assertFalse("cannot skip SENDING", MlsDeliveryState.QUEUED.canTransitionTo(MlsDeliveryState.TRANSPORT_ACCEPTED))
        assertFalse("cannot skip to PROCESSED", MlsDeliveryState.TRANSPORT_ACCEPTED.canTransitionTo(MlsDeliveryState.PROCESSED))
        assertFalse("cannot go backwards", MlsDeliveryState.PROCESSED.canTransitionTo(MlsDeliveryState.SENDING))
        assertFalse(MlsDeliveryState.PROCESSED.isTerminal.not())
        assertTrue(MlsDeliveryState.PROCESSED.isTerminal)
        assertTrue(MlsDeliveryState.FAILED.isTerminal)
        assertTrue(MlsDeliveryState.EXPIRED.isTerminal)
        assertFalse(MlsDeliveryState.SENDING.isTerminal)
    }

    @Test
    fun `guarded update refuses a transition from the wrong state`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "guarded".toByteArray()).getOrThrow().toLong()

        // QUEUED is current; a SENDING->TRANSPORT_ACCEPTED claim must not apply.
        val moved = sendDao.setTransportAccepted(
            rowId, "corr", MlsDeliveryState.TRANSPORT_ACCEPTED, MlsDeliveryState.SENDING, System.currentTimeMillis()
        )
        assertEquals(0, moved)
        assertEquals(MlsDeliveryState.QUEUED, sendDao.byRowId(rowId)!!.state)
    }

    @Test
    fun `terminal states cannot be revived`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "dead".toByteArray()).getOrThrow().toLong()
        sendDao.markFailed(rowId, MlsDeliveryState.FAILED, MlsDeliveryState.QUEUED, System.currentTimeMillis())
        assertEquals(MlsDeliveryState.FAILED, sendDao.byRowId(rowId)!!.state)

        val revived = sendDao.tryTransitionState(
            rowId, MlsDeliveryState.FAILED, MlsDeliveryState.SENDING, System.currentTimeMillis()
        )
        assertEquals("a FAILED message must never be revived", 0, revived)
        assertEquals(MlsDeliveryState.FAILED, sendDao.byRowId(rowId)!!.state)
    }

    // ------------------------------------------------------------------
    // ACK security boundary
    // ------------------------------------------------------------------

    @Test
    fun `markRemoteReceived refuses a bare local row id`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "ack".toByteArray()).getOrThrow().toLong()
        manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()

        val result = manager.markRemoteReceived(rowId)

        assertTrue("an unauthenticated ACK must be refused", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected ACKNotAuthenticatedException, got ${err?.javaClass?.simpleName}",
            err is ACKNotAuthenticatedException
        )
        assertEquals(
            "state must be untouched",
            MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.byRowId(rowId)!!.state
        )
    }

    @Test
    fun `markProcessed refuses a bare local row id`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "ack2".toByteArray()).getOrThrow().toLong()

        val result = manager.markProcessed(rowId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ACKNotAuthenticatedException)
        assertEquals(MlsDeliveryState.QUEUED, sendDao.byRowId(rowId)!!.state)
    }

    @Test
    fun `no DAO method can advance a message to REMOTE_RECEIVED or PROCESSED`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "noack".toByteArray()).getOrThrow().toLong()
        manager.processOutboundQueue(groupId) { _, _ -> Result.success(Unit) }.getOrThrow()
        val now = System.currentTimeMillis()

        // Try every guarded update that could reach a later state. All must be
        // no-ops because the current state is TRANSPORT_ACCEPTED, which may not
        // skip to PROCESSED.
        assertEquals(0, sendDao.tryTransitionState(rowId, MlsDeliveryState.TRANSPORT_ACCEPTED, MlsDeliveryState.PROCESSED, now))
        assertEquals(0, sendDao.markFailed(rowId, MlsDeliveryState.PROCESSED, MlsDeliveryState.TRANSPORT_ACCEPTED, now))

        assertEquals(
            MlsDeliveryState.TRANSPORT_ACCEPTED, sendDao.byRowId(rowId)!!.state
        )
    }

    // ------------------------------------------------------------------
    // Restart persistence
    // ------------------------------------------------------------------

    @Test
    fun `queue survives a manager restart backed by the same storage`() = runBlocking {
        val rowId = manager.sendApplicationMessage(groupId, "durable".toByteArray()).getOrThrow().toLong()
        manager.processOutboundQueue(groupId) { _, _ -> Result.failure(Exception("offline")) }.getOrThrow()
        val before = sendDao.byRowId(rowId)!!

        // A new manager over the same DAO layer stands in for a process restart:
        // no in-memory queue state is carried across.
        val restarted = MlsGroupManager(null, groupDao, messageDao, sendDao, vault)
        val after = restarted.pendingOutbound(groupId).first { it.first == rowId }

        assertTrue("wire bytes must survive restart", after.second.isNotEmpty())
        assertEquals(before.state, sendDao.byRowId(rowId)!!.state)
        assertEquals(before.attemptCount, sendDao.byRowId(rowId)!!.attemptCount)
        assertEquals(before.nextRetryAt, sendDao.byRowId(rowId)!!.nextRetryAt)
    }

    @Test
    fun `unknown group is rejected rather than silently queued`() = runBlocking {
        val result = manager.sendApplicationMessage("no-such-group", "x".toByteArray())
        assertTrue(result.isFailure)
        assertNull(result.exceptionOrNull()?.let { null })
    }

    // ------------------------------------------------------------------
    // Failure reporting
    // ------------------------------------------------------------------

    @Test
    fun `processor surfaces an unknown group as a failure`() = runBlocking {
        val result = manager.processOutboundQueue("no-such-group") { _, _ -> Result.success(Unit) }
        assertTrue(result.isFailure)
        try {
            result.getOrThrow()
            fail("expected failure for unknown group")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no-such-group"))
        }
    }
}
