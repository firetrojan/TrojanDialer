package com.communicator.communication.encrypted

import com.communicator.data.core.encrypted.MlsDeliveryState
import com.communicator.data.core.encrypted.MlsGroupDao
import com.communicator.data.core.encrypted.MlsGroupEntity
import com.communicator.data.core.encrypted.MlsGroupMessageDao
import com.communicator.data.core.encrypted.MlsGroupMessageEntity
import com.communicator.data.core.encrypted.MlsPendingSendDao
import com.communicator.data.core.encrypted.MlsPendingSendEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory fakes for the Room DAOs. These simulate ONLY the storage layer;
 * every MLS operation exercised in the tests runs the real Bouncy Castle
 * 1.86 protocol code (real signing, real HPKE, real TreeKEM, real key
 * schedules). The fakes preserve exactly the DAO contracts MlsGroupManager
 * depends on: hash dedup, strict sequence ordering, and the guarded
 * stateVersion advance.
 */

class FakeMlsGroupDao : MlsGroupDao {
    val rows = ConcurrentHashMap<String, MlsGroupEntity>()

    override suspend fun get(groupId: String): MlsGroupEntity? = rows[groupId]

    override suspend fun getAll(): List<MlsGroupEntity> = rows.values.sortedByDescending { it.updatedAt }

    private val allFlow = MutableStateFlow<List<MlsGroupEntity>>(emptyList())

    override fun observeAll(): Flow<List<MlsGroupEntity>> = allFlow

    override suspend fun getByIdentity(identityKeyId: String): List<MlsGroupEntity> =
        rows.values.filter { it.identityKeyId == identityKeyId }.sortedByDescending { it.updatedAt }

    override suspend fun insert(group: MlsGroupEntity) {
        check(rows.putIfAbsent(group.groupId, group) == null) { "group already exists" }
    }

    override suspend fun advance(
        groupId: String,
        epoch: Long,
        leafIndex: Int,
        memberCount: Int,
        epochAuthenticator: String,
        publicTreeSnapshot: String?,
        messageLogSize: Int,
        updatedAt: Long,
        observedStateVersion: Long
    ): Int {
        val current = rows[groupId] ?: return 0
        if (current.stateVersion > observedStateVersion) return 0
        rows[groupId] = current.copy(
            epoch = epoch,
            leafIndex = leafIndex,
            memberCount = memberCount,
            epochAuthenticator = epochAuthenticator,
            publicTreeSnapshot = publicTreeSnapshot,
            messageLogSize = messageLogSize,
            stateVersion = current.stateVersion + 1,
            updatedAt = updatedAt
        )
        return 1
    }

    override suspend fun delete(groupId: String) {
        rows.remove(groupId)
    }
}

class FakeMlsGroupMessageDao : MlsGroupMessageDao {
    private val log = ConcurrentHashMap<String, MutableList<MlsGroupMessageEntity>>()
    private val seqCounter = ConcurrentHashMap<String, AtomicLong>()

    private fun listFor(groupId: String): MutableList<MlsGroupMessageEntity> =
        log.computeIfAbsent(groupId) { ArrayList() }

    override suspend fun logForGroup(groupId: String): List<MlsGroupMessageEntity> =
        ArrayList(listFor(groupId)).sortedBy { it.sequence }

    override suspend fun lastSequence(groupId: String): Long? =
        listFor(groupId).maxOfOrNull { it.sequence }

    override suspend fun countByHash(groupId: String, hash: String): Int =
        listFor(groupId).count { it.messageHash == hash }

    override suspend fun insert(message: MlsGroupMessageEntity) {
        listFor(message.groupId).add(message)
    }

    override suspend fun deleteForGroup(groupId: String) {
        log.remove(groupId)
        seqCounter.remove(groupId)
    }

    override suspend fun appendUnique(
        groupId: String,
        epoch: Long,
        contentType: String,
        messageHash: String,
        encodedMessage: String,
        originatedLocally: Boolean
    ): Long {
        val list = listFor(groupId)
        synchronized(list) {
            if (list.any { it.messageHash == messageHash }) return -1L
            val next = seqCounter.computeIfAbsent(groupId) { AtomicLong(0) }.getAndIncrement()
            list.add(
                MlsGroupMessageEntity(
                    groupId = groupId,
                    sequence = next,
                    epoch = epoch,
                    contentType = contentType,
                    messageHash = messageHash,
                    encodedMessage = encodedMessage,
                    originatedLocally = originatedLocally
                )
            )
            return next
        }
    }
}

/**
 * In-memory stand-in for the Room pending-send queue.
 *
 * This models the database contract the real DAO relies on, and nothing else:
 *
 *  - the UNIQUE (groupId, messageHash) index, so [insertIfAbsent] returns -1 on
 *    a duplicate instead of raising, exactly as OnConflictStrategy.IGNORE does;
 *  - guarded UPDATEs, so a transition only lands when the row is currently in
 *    the expected state, returning the affected-row count like Room does.
 *
 * Every method is synchronized on this instance so competing-worker tests see
 * the same atomicity SQLite gives for free.
 */
class FakeMlsPendingSendDao : MlsPendingSendDao {
    private val rows = LinkedHashMap<Long, MlsPendingSendEntity>()
    private val ids = AtomicLong(1)

    /**
     * Current queue contents. Exposed under the name the existing tests use,
     * backed by a defensive copy so assertions cannot mutate stored rows.
     */
    val queue: List<MlsPendingSendEntity> get() = synchronized(this) { rows.values.toList() }

    fun snapshot(): List<MlsPendingSendEntity> = queue

    fun byRowId(rowId: Long): MlsPendingSendEntity? = synchronized(this) { rows[rowId] }

    override suspend fun forGroup(groupId: String): List<MlsPendingSendEntity> =
        synchronized(this) { rows.values.filter { it.groupId == groupId }.sortedBy { it.rowId } }

    /**
     * Enforces the unique index atomically with the insert, which is what makes
     * concurrent duplicate detection safe. A read-then-write pre-check would not.
     */
    override suspend fun insertIfAbsent(send: MlsPendingSendEntity): Long = synchronized(this) {
        val clash = rows.values.any { it.groupId == send.groupId && it.messageHash == send.messageHash }
        if (clash) return -1L
        val id = ids.getAndIncrement()
        rows[id] = send.copy(rowId = id)
        id
    }

    override suspend fun findByHash(messageHash: String): MlsPendingSendEntity? =
        synchronized(this) { rows.values.firstOrNull { it.messageHash == messageHash } }

    override suspend fun forGroupAndState(groupId: String, state: MlsDeliveryState) =
        synchronized(this) {
            rows.values.filter { it.groupId == groupId && it.state == state }.sortedBy { it.createdAt }
        }

    override suspend fun findSendable(
        groupId: String,
        sendableStates: List<MlsDeliveryState>,
        now: Long
    ): List<MlsPendingSendEntity> = synchronized(this) {
        rows.values
            .filter {
                it.groupId == groupId &&
                    it.state in sendableStates &&
                    (it.expiresAt == 0L || it.expiresAt > now) &&
                    (it.nextRetryAt == 0L || it.nextRetryAt <= now) &&
                    it.attemptCount < it.maxAttempts
            }
            .sortedBy { it.createdAt }
    }

    override suspend fun reclaimForRetry(
        rowId: Long,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int = synchronized(this) {
        val cur = rows[rowId] ?: return@synchronized 0
        if (cur.state != expectedState) return@synchronized 0
        if (!cur.state.canReclaimForRetry()) return@synchronized 0
        rows[rowId] = cur.copy(updatedAt = now, attemptCount = cur.attemptCount + 1)
        1
    }

    override suspend fun findExhausted(
        groupId: String,
        sendableStates: List<MlsDeliveryState>
    ): List<MlsPendingSendEntity> = synchronized(this) {
        rows.values
            .filter { it.groupId == groupId && it.state in sendableStates && it.attemptCount >= it.maxAttempts }
            .sortedBy { it.createdAt }
    }

    override suspend fun findExpired(now: Long, terminalStates: List<MlsDeliveryState>) =
        synchronized(this) {
            rows.values.filter { it.expiresAt > 0 && it.expiresAt <= now && it.state !in terminalStates }
        }

    override suspend fun getByRowId(rowId: Long): MlsPendingSendEntity? =
        synchronized(this) { rows[rowId] }

    /**
     * Mirrors the production SQL: an acknowledgement only lands on a message a
     * transport already accepted, and never overwrites the correlation id.
     */
    override suspend fun markRemoteReceived(
        rowId: Long,
        newState: MlsDeliveryState,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int = synchronized(this) {
        val cur = rows[rowId] ?: return@synchronized 0
        if (cur.state != expectedState) return@synchronized 0
        if (!cur.state.canTransitionTo(newState)) return@synchronized 0
        rows[rowId] = cur.copy(state = newState, updatedAt = now)
        1
    }

    override suspend fun tryTransitionState(
        rowId: Long,
        expectedState: MlsDeliveryState,
        newState: MlsDeliveryState,
        now: Long
    ): Int = synchronized(this) {
        val cur = rows[rowId] ?: return@synchronized 0
        // Illegal edges are refused here as well as in SQL: a caller cannot
        // walk a message backwards even if it builds the call by hand.
        if (cur.state != expectedState) return@synchronized 0
        if (!cur.state.canTransitionTo(newState)) return@synchronized 0
        rows[rowId] = cur.copy(state = newState, updatedAt = now, attemptCount = cur.attemptCount + 1)
        1
    }

    override suspend fun setTransportAccepted(
        rowId: Long,
        correlationId: String,
        newState: MlsDeliveryState,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int = synchronized(this) {
        val cur = rows[rowId] ?: return@synchronized 0
        if (cur.state != expectedState) return@synchronized 0
        if (!cur.state.canTransitionTo(newState)) return@synchronized 0
        rows[rowId] = cur.copy(state = newState, transportCorrelationId = correlationId, updatedAt = now)
        1
    }

    override suspend fun markFailed(
        rowId: Long,
        newState: MlsDeliveryState,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int = synchronized(this) {
        val cur = rows[rowId] ?: return@synchronized 0
        if (cur.state != expectedState) return@synchronized 0
        if (!cur.state.canTransitionTo(newState)) return@synchronized 0
        rows[rowId] = cur.copy(state = newState, updatedAt = now)
        1
    }

    override suspend fun scheduleRetry(
        rowId: Long,
        nextRetryAt: Long,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int = synchronized(this) {
        val cur = rows[rowId] ?: return@synchronized 0
        if (cur.state != expectedState) return@synchronized 0
        rows[rowId] = cur.copy(nextRetryAt = nextRetryAt, updatedAt = now)
        1
    }

    override suspend fun delete(rowId: Long) {
        synchronized(this) { rows.remove(rowId) }
    }

    override suspend fun deleteForGroup(groupId: String) {
        synchronized(this) { rows.entries.removeIf { it.value.groupId == groupId } }
    }
}

/** JVM-test key vault: plain in-memory bytes, identical seam API. */
class InMemoryKeyVault : KeyVault {
    val keys = ConcurrentHashMap<String, ByteArray>()

    override suspend fun store(alias: String, rawKey: ByteArray) {
        keys[alias] = rawKey
    }

    override suspend fun load(alias: String): ByteArray? = keys[alias]

    override suspend fun delete(alias: String) {
        keys.remove(alias)
    }
}
