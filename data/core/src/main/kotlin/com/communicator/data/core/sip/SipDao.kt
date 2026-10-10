package com.communicator.data.core.sip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SipAccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: SipAccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<SipAccountEntity>)

    @Query("SELECT * FROM sip_accounts WHERE accountId = :accountId")
    suspend fun getAccount(accountId: String): SipAccountEntity?

    @Query("SELECT * FROM sip_accounts WHERE enabled = 1 ORDER BY isDefault DESC, createdAt DESC")
    fun getEnabledAccounts(): Flow<List<SipAccountEntity>>

    @Query("SELECT * FROM sip_accounts WHERE isDefault = 1 AND enabled = 1")
    suspend fun getDefaultAccount(): SipAccountEntity?

    @Query("SELECT * FROM sip_accounts ORDER BY isDefault DESC, createdAt DESC")
    fun getAllAccounts(): Flow<List<SipAccountEntity>>

    @Update
    suspend fun updateAccount(account: SipAccountEntity)

    @Query("UPDATE sip_accounts SET enabled = :enabled WHERE accountId = :accountId")
    suspend fun setAccountEnabled(accountId: String, enabled: Boolean)

    @Query("UPDATE sip_accounts SET isDefault = CASE WHEN accountId = :accountId THEN 1 ELSE 0 END WHERE enabled = 1")
    suspend fun setDefaultAccount(accountId: String)

    @Query("DELETE FROM sip_accounts WHERE accountId = :accountId")
    suspend fun deleteAccount(accountId: String)

    @Query("SELECT * FROM sip_accounts WHERE domain = :domain AND username = :username")
    suspend fun findAccountByCredentials(username: String, domain: String): SipAccountEntity?
}

@Dao
interface SipCallDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(call: SipCallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalls(calls: List<SipCallEntity>)

    @Query("SELECT * FROM sip_calls WHERE callId = :callId")
    suspend fun getCall(callId: String): SipCallEntity?

    @Query("SELECT * FROM sip_calls WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getCallsForThread(threadId: String): Flow<List<SipCallEntity>>

    @Query("SELECT * FROM sip_calls WHERE accountId = :accountId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getCallsForAccount(accountId: String, limit: Int): List<SipCallEntity>

    // The direction predicate was removed: it bound :direction, which matches no
    // parameter, so Room rejected the query outright ("Cannot find function
    // parameters for :direction"). Active calls are wanted regardless of
    // direction; no caller passed one.
    @Query("SELECT * FROM sip_calls WHERE state IN ('ACTIVE', 'RINGING', 'CONNECTING') ORDER BY timestamp DESC")
    fun getActiveCalls(): Flow<List<SipCallEntity>>

    // Same unbound :direction problem as getActiveCalls; ringing calls are also
    // direction-agnostic here.
    @Query("SELECT * FROM sip_calls WHERE state = 'RINGING' ORDER BY timestamp DESC")
    fun getRingingCalls(): Flow<List<SipCallEntity>>

    @Query("SELECT * FROM sip_calls WHERE accountId = :accountId AND state = 'ACTIVE' ORDER BY timestamp DESC")
    suspend fun getActiveCallForAccount(accountId: String): SipCallEntity?

    @Update
    suspend fun updateCall(call: SipCallEntity)

    @Query("UPDATE sip_calls SET state = :state WHERE callId = :callId")
    suspend fun updateCallState(callId: String, state: CallState)

    @Query("UPDATE sip_calls SET state = :state, endTime = :endTime, duration = :duration WHERE callId = :callId")
    suspend fun endCall(callId: String, state: CallState, endTime: Long, duration: Long)

    @Query("UPDATE sip_calls SET isOnHold = :onHold WHERE callId = :callId")
    suspend fun setCallOnHold(callId: String, onHold: Boolean)

    @Query("UPDATE sip_calls SET isMuted = :muted WHERE callId = :callId")
    suspend fun setCallMuted(callId: String, muted: Boolean)

    @Query("UPDATE sip_calls SET isSpeaker = :speaker WHERE callId = :callId")
    suspend fun setCallSpeaker(callId: String, speaker: Boolean)

    @Query("UPDATE sip_calls SET sdp = :sdp, codec = :codec WHERE callId = :callId")
    suspend fun updateCallMedia(callId: String, sdp: String?, codec: String?)

    @Transaction
    suspend fun insertCallWithThread(call: SipCallEntity) {
        insertCall(call)
    }

    @Query("DELETE FROM sip_calls WHERE callId = :callId")
    suspend fun deleteCall(callId: String)

    @Query("DELETE FROM sip_calls WHERE threadId = :threadId")
    suspend fun deleteCallsForThread(threadId: String)
}

@Dao
interface SipMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SipMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<SipMessageEntity>)

    @Query("SELECT * FROM sip_messages WHERE messageId = :messageId")
    suspend fun getMessage(messageId: String): SipMessageEntity?

    @Query("SELECT * FROM sip_messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getMessagesForThread(threadId: String): Flow<List<SipMessageEntity>>

    @Query("SELECT * FROM sip_messages WHERE threadId = :threadId AND deliveryState = :state ORDER BY timestamp ASC")
    fun getMessagesByState(threadId: String, state: DeliveryState): Flow<List<SipMessageEntity>>

    @Query("SELECT * FROM sip_messages WHERE accountId = :accountId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesForAccount(accountId: String, limit: Int): List<SipMessageEntity>

    @Update
    suspend fun updateMessage(message: SipMessageEntity)

    @Query("UPDATE sip_messages SET deliveryState = :state WHERE messageId = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: DeliveryState)

    @Query("DELETE FROM sip_messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM sip_messages WHERE threadId = :threadId")
    suspend fun deleteMessagesForThread(threadId: String)
}
