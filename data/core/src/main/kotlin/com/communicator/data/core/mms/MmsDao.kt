package com.communicator.data.core.mms

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MmsThreadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: MmsThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThreads(threads: List<MmsThreadEntity>)

    @Query("SELECT * FROM mms_threads WHERE threadId = :threadId")
    suspend fun getThread(threadId: String): MmsThreadEntity?

    @Query("SELECT * FROM mms_threads ORDER BY lastMessageTimestamp DESC")
    fun getAllThreads(): Flow<List<MmsThreadEntity>>

    @Query("SELECT * FROM mms_threads WHERE unreadCount > 0 ORDER BY lastMessageTimestamp DESC")
    fun getUnreadThreads(): Flow<List<MmsThreadEntity>>

    @Query("SELECT * FROM mms_threads WHERE transportId = :transportId ORDER BY lastMessageTimestamp DESC")
    fun getThreadsByTransport(transportId: String): Flow<List<MmsThreadEntity>>

    @Query("SELECT * FROM mms_threads WHERE threadId = :threadId")
    fun observeThread(threadId: String): Flow<MmsThreadEntity?>

    @Update
    suspend fun updateThread(thread: MmsThreadEntity)

    @Query("UPDATE mms_threads SET unreadCount = :count WHERE threadId = :threadId")
    suspend fun setUnreadCount(threadId: String, count: Int)

    @Query("UPDATE mms_threads SET lastMessageId = :lastMessageId, lastMessageContent = :lastMessageContent, lastMessageTimestamp = :lastMessageTimestamp, updatedAt = :updatedAt WHERE threadId = :threadId")
    suspend fun updateLastMessage(threadId: String, lastMessageId: String, lastMessageContent: String?, lastMessageTimestamp: Long, updatedAt: Long)

    @Query("DELETE FROM mms_threads WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: String)

    @Query("DELETE FROM mms_threads WHERE isArchived = 1")
    suspend fun deleteArchivedThreads()
}

@Dao
interface MmsMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MmsMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MmsMessageEntity>)

    @Query("SELECT * FROM mms_messages WHERE messageId = :messageId")
    suspend fun getMessage(messageId: String): MmsMessageEntity?

    @Query("SELECT * FROM mms_messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getMessagesForThread(threadId: String): Flow<List<MmsMessageEntity>>

    @Query("SELECT * FROM mms_messages WHERE threadId = :threadId AND state = :state ORDER BY timestamp ASC")
    fun getMessagesByState(threadId: String, state: com.communicator.data.core.mms.MmsState): Flow<List<MmsMessageEntity>>

    @Query("SELECT * FROM mms_messages WHERE address = :address ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesFromAddress(address: String, limit: Int): List<MmsMessageEntity>

    @Query("SELECT * FROM mms_messages WHERE deliveryState = :state ORDER BY timestamp DESC")
    fun getMessagesByDeliveryState(state: com.communicator.data.core.mms.MmsDeliveryState): Flow<List<MmsMessageEntity>>

    @Query("SELECT * FROM mms_messages WHERE subscriptionId = :subscriptionId ORDER BY timestamp DESC")
    fun getMessagesBySubscription(subscriptionId: Int): Flow<List<MmsMessageEntity>>

    @Update
    suspend fun updateMessage(message: MmsMessageEntity)

    @Query("UPDATE mms_messages SET state = :state WHERE messageId = :messageId")
    suspend fun updateMessageState(messageId: String, state: com.communicator.data.core.mms.MmsState)

    @Query("UPDATE mms_messages SET deliveryState = :state, errorMessage = :error WHERE messageId = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: com.communicator.data.core.mms.MmsDeliveryState, error: String?)

    @Query("UPDATE mms_messages SET read = :read WHERE messageId = :messageId")
    suspend fun setRead(messageId: String, read: Boolean)

    /**
 * Stores a message together with its thread.
 *
 * `insertThread` lives in [MmsThreadDao], a different Room interface, so this
 * default method cannot call it: Room generates the implementations and there is
 * no instance to delegate to. Callers that need both rows must do so inside
 * their own @Transaction with an injected [MmsThreadDao].
 *
 * Removed rather than left referencing a method that does not exist here.
 */

    @Query("DELETE FROM mms_messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM mms_messages WHERE threadId = :threadId")
    suspend fun deleteMessagesForThread(threadId: String)
}

@Dao
interface MmsPartDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPart(part: MmsPartEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<MmsPartEntity>)

    @Query("SELECT * FROM mms_parts WHERE partId = :partId")
    suspend fun getPart(partId: String): MmsPartEntity?

    @Query("SELECT * FROM mms_parts WHERE messageId = :messageId")
    fun getPartsForMessage(messageId: String): Flow<List<MmsPartEntity>>

    @Query("SELECT * FROM mms_parts WHERE contentType LIKE 'text/%' AND messageId = :messageId")
    fun getTextParts(messageId: String): Flow<List<MmsPartEntity>>

    @Query("SELECT * FROM mms_parts WHERE contentType LIKE 'image/%' AND messageId = :messageId")
    fun getImageParts(messageId: String): Flow<List<MmsPartEntity>>

    @Query("SELECT * FROM mms_parts WHERE contentType LIKE 'audio/%' AND messageId = :messageId")
    fun getAudioParts(messageId: String): Flow<List<MmsPartEntity>>

    @Query("SELECT * FROM mms_parts WHERE contentType LIKE 'video/%' AND messageId = :messageId")
    fun getVideoParts(messageId: String): Flow<List<MmsPartEntity>>

    @Update
    suspend fun updatePart(part: MmsPartEntity)

    @Transaction
    suspend fun insertPartsWithMessage(parts: List<MmsPartEntity>, message: MmsPartEntity) {
        insertParts(parts)
    }

    @Query("DELETE FROM mms_parts WHERE partId = :partId")
    suspend fun deletePart(partId: String)

    @Query("DELETE FROM mms_parts WHERE messageId = :messageId")
    suspend fun deletePartsForMessage(messageId: String)
}
