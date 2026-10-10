package com.communicator.data.core.encrypted

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MlsGroupDao {
    @Query("SELECT * FROM mls_groups WHERE groupId = :groupId")
    suspend fun get(groupId: String): MlsGroupEntity?

    @Query("SELECT * FROM mls_groups ORDER BY updatedAt DESC")
    suspend fun getAll(): List<MlsGroupEntity>

    @Query("SELECT * FROM mls_groups ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MlsGroupEntity>>

    @Query("SELECT * FROM mls_groups WHERE identityKeyId = :identityKeyId ORDER BY updatedAt DESC")
    suspend fun getByIdentity(identityKeyId: String): List<MlsGroupEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(group: MlsGroupEntity)

    /**
     * Guarded update: refuses to write when the stored stateVersion has moved
     * past the version the writer observed (stale-epoch protection).
     */
    @Query(
        "UPDATE mls_groups SET epoch = :epoch, leafIndex = :leafIndex, memberCount = :memberCount, " +
            "epochAuthenticator = :epochAuthenticator, publicTreeSnapshot = :publicTreeSnapshot, " +
            "messageLogSize = :messageLogSize, stateVersion = stateVersion + 1, updatedAt = :updatedAt " +
            "WHERE groupId = :groupId AND stateVersion <= :observedStateVersion"
    )
    suspend fun advance(
        groupId: String,
        epoch: Long,
        leafIndex: Int,
        memberCount: Int,
        epochAuthenticator: String,
        publicTreeSnapshot: String?,
        messageLogSize: Int,
        updatedAt: Long,
        observedStateVersion: Long
    ): Int

    @Query("DELETE FROM mls_groups WHERE groupId = :groupId")
    suspend fun delete(groupId: String)
}

@Dao
interface MlsGroupMessageDao {
    @Query("SELECT * FROM mls_group_messages WHERE groupId = :groupId ORDER BY sequence ASC")
    suspend fun logForGroup(groupId: String): List<MlsGroupMessageEntity>

    @Query("SELECT MAX(sequence) FROM mls_group_messages WHERE groupId = :groupId")
    suspend fun lastSequence(groupId: String): Long?

    @Query("SELECT COUNT(*) FROM mls_group_messages WHERE groupId = :groupId AND messageHash = :hash")
    suspend fun countByHash(groupId: String, hash: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MlsGroupMessageEntity)

    @Query("DELETE FROM mls_group_messages WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    /**
     * Appends a message if its hash is not already present (dedup) and returns
     * the rowId, or -1 when a duplicate was rejected. Sequencing and dedup are
     * one transaction so concurrent appends cannot interleave.
     */
    @Transaction
    suspend fun appendUnique(
        groupId: String,
        epoch: Long,
        contentType: String,
        messageHash: String,
        encodedMessage: String,
        originatedLocally: Boolean
    ): Long {
        if (countByHash(groupId, messageHash) > 0) {
            return -1L
        }
        val next = (lastSequence(groupId) ?: -1L) + 1L
        val entity = MlsGroupMessageEntity(
            groupId = groupId,
            sequence = next,
            epoch = epoch,
            contentType = contentType,
            messageHash = messageHash,
            encodedMessage = encodedMessage,
            originatedLocally = originatedLocally
        )
        insert(entity)
        return next
    }
}

@Dao
interface MlsPendingSendDao {
    @Query("SELECT * FROM mls_pending_sends WHERE groupId = :groupId ORDER BY rowId ASC")
    suspend fun forGroup(groupId: String): List<MlsPendingSendEntity>

    /**
     * Conflict-safe insert. The unique index on (groupId, messageHash) is the
     * authority for deduplication: when a duplicate is offered, SQLite rejects
     * the write and Room returns -1 instead of aborting the transaction. The
     * caller must check the return value; do NOT pre-check with [findByHash],
     * because check-then-insert races across concurrent producers.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(send: MlsPendingSendEntity): Long

    @Query("SELECT * FROM mls_pending_sends WHERE messageHash = :messageHash LIMIT 1")
    suspend fun findByHash(messageHash: String): MlsPendingSendEntity?

    /**
     * Loads one pending send by its primary key.
     *
     * The acknowledgement path needs the row's current state before applying a
     * guarded transition, so the read and the write cannot be two independent
     * unconstrained calls: the transition below re-checks the state in its WHERE
     * clause and the caller must verify the affected-row count.
     */
    @Query("SELECT * FROM mls_pending_sends WHERE rowId = :rowId")
    suspend fun getByRowId(rowId: Long): MlsPendingSendEntity?

    @Query("SELECT * FROM mls_pending_sends WHERE groupId = :groupId AND state = :state ORDER BY createdAt ASC")
    suspend fun forGroupAndState(groupId: String, state: MlsDeliveryState): List<MlsPendingSendEntity>

    /**
     * Pending sends eligible for a send attempt right now:
     *  - not in a terminal state,
     *  - not expired (expiresAt = 0 means no expiry),
     *  - scheduled time reached (nextRetryAt = 0 means immediately),
     *  - retry budget not exhausted (attemptCount < maxAttempts).
     */
    @Query(
        "SELECT * FROM mls_pending_sends " +
            "WHERE groupId = :groupId " +
            "AND state IN (:sendableStates) " +
            "AND (expiresAt = 0 OR expiresAt > :now) " +
            "AND (nextRetryAt = 0 OR nextRetryAt <= :now) " +
            "AND attemptCount < maxAttempts " +
            "ORDER BY createdAt ASC"
    )
    suspend fun findSendable(
        groupId: String,
        sendableStates: List<MlsDeliveryState>,
        now: Long
    ): List<MlsPendingSendEntity>

    /**
     * Claims a backed-off SENDING row for another transport attempt, consuming
     * one unit of retry budget.
     *
     * Separate from [tryTransitionState] because a retry is SENDING -> SENDING,
     * which is not a lifecycle transition and [tryTransitionState] refuses it.
     * The guarded WHERE clause still provides mutual exclusion: only one worker
     * can move the row off SENDING per attempt, so two workers cannot send the
     * same message concurrently. The row stays SENDING, so a crash mid-attempt
     * leaves it recoverable after its backoff elapses.
     */
    @Query(
        "UPDATE mls_pending_sends SET attemptCount = attemptCount + 1, updatedAt = :now " +
            "WHERE rowId = :rowId AND state = :expectedState"
    )
    suspend fun reclaimForRetry(
        rowId: Long,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int

    /**
     * Messages whose retry budget is spent: still non-terminal, but
     * attemptCount has reached maxAttempts.
     *
     * These are excluded by [findSendable], so the queue processor sweeps them
     * into FAILED. Without this a permanently failing message would remain in
     * SENDING forever and never reach a terminal state.
     */
    @Query(
        "SELECT * FROM mls_pending_sends " +
            "WHERE groupId = :groupId AND state IN (:sendableStates) AND attemptCount >= maxAttempts"
    )
    suspend fun findExhausted(
        groupId: String,
        sendableStates: List<MlsDeliveryState>
    ): List<MlsPendingSendEntity>

    /**
     * Messages whose TTL has elapsed and that are still in a non-terminal
     * state. Used by the expiry sweep.
     */
    @Query(
        "SELECT * FROM mls_pending_sends " +
            "WHERE expiresAt > 0 AND expiresAt <= :now AND state NOT IN (:terminalStates)"
    )
    suspend fun findExpired(now: Long, terminalStates: List<MlsDeliveryState>): List<MlsPendingSendEntity>

    /**
     * Atomically claims a message for a send attempt by moving it from
     * [expectedState] to SENDING and consuming one unit of retry budget.
     *
     * The WHERE clause on state is the mutual-exclusion primitive: two workers
     * racing on the same rowId see exactly one non-zero return, so a message
     * cannot be handed to two concurrent senders. Returns the number of rows
     * updated (1 = claimed, 0 = lost the race or wrong state).
     */
    @Query(
        "UPDATE mls_pending_sends SET state = :newState, attemptCount = attemptCount + 1, updatedAt = :now " +
            "WHERE rowId = :rowId AND state = :expectedState"
    )
    suspend fun tryTransitionState(
        rowId: Long,
        expectedState: MlsDeliveryState,
        newState: MlsDeliveryState,
        now: Long
    ): Int

    /**
     * Records that a transport accepted the bytes (SENT semantics only).
     * Guarded on SENDING so it cannot resurrect a FAILED or EXPIRED row.
     */
    @Query(
        "UPDATE mls_pending_sends SET state = :newState, transportCorrelationId = :correlationId, updatedAt = :now " +
            "WHERE rowId = :rowId AND state = :expectedState"
    )
    suspend fun setTransportAccepted(
        rowId: Long,
        correlationId: String,
        newState: MlsDeliveryState,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int

    /**
     * Records that an authenticated recipient acknowledged receipt.
     *
     * Separate from [setTransportAccepted] because the two are different facts:
     * transport acceptance records a correlation id, an acknowledgement must not
     * overwrite it. Guarded on the expected state, so an acknowledgement can only
     * land on a message a transport actually accepted, and never on a terminal
     * row.
     */
    @Query(
        "UPDATE mls_pending_sends SET state = :newState, updatedAt = :now " +
            "WHERE rowId = :rowId AND state = :expectedState"
    )
    suspend fun markRemoteReceived(
        rowId: Long,
        newState: MlsDeliveryState,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int

    /**
     * Terminal failure from SENDING (retry budget exhausted) or expiry.
     * Guarded on the expected non-terminal state.
     */
    @Query(
        "UPDATE mls_pending_sends SET state = :newState, updatedAt = :now " +
            "WHERE rowId = :rowId AND state = :expectedState"
    )
    suspend fun markFailed(
        rowId: Long,
        newState: MlsDeliveryState,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int

    /**
     * Schedules the next attempt with a caller-computed backoff. Does not
     * change state or attemptCount: [tryTransitionState] already consumed the
     * attempt. Guarded on SENDING so a failed or expired row cannot be
     * rescheduled. Returns 0 when the row left SENDING in the meantime.
     */
    @Query(
        "UPDATE mls_pending_sends SET nextRetryAt = :nextRetryAt, updatedAt = :now " +
            "WHERE rowId = :rowId AND state = :expectedState"
    )
    suspend fun scheduleRetry(
        rowId: Long,
        nextRetryAt: Long,
        expectedState: MlsDeliveryState,
        now: Long
    ): Int

    /**
     * Removes a message whose delivery reached a confirmed terminal state.
     */
    @Query("DELETE FROM mls_pending_sends WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("DELETE FROM mls_pending_sends WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}
