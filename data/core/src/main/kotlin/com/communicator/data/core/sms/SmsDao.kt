package com.communicator.data.core.sms

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    suspend fun getConversation(threadId: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE unreadCount > 0 ORDER BY lastMessageTimestamp DESC")
    fun getUnreadConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE transportId = :transportId ORDER BY lastMessageTimestamp DESC")
    fun getConversationsByTransport(transportId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    fun observeConversation(threadId: String): Flow<ConversationEntity?>

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = :count WHERE threadId = :threadId")
    suspend fun setUnreadCount(threadId: String, count: Int)

    @Query("UPDATE conversations SET lastMessageId = :lastMessageId, lastMessageContent = :lastMessageContent, lastMessageTimestamp = :lastMessageTimestamp, updatedAt = :updatedAt WHERE threadId = :threadId")
    suspend fun updateLastMessage(threadId: String, lastMessageId: String, lastMessageContent: String?, lastMessageTimestamp: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE threadId = :threadId")
    suspend fun deleteConversation(threadId: String)

    @Query("DELETE FROM conversations WHERE isArchived = 1")
    suspend fun deleteArchivedConversations()
}

@Dao
interface SmsMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SmsMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<SmsMessageEntity>)

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getMessage(messageId: String): SmsMessageEntity?

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY timestamp ASC")
    fun getMessagesForThread(threadId: String): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM messages WHERE threadId = :threadId AND deliveryState = :state ORDER BY timestamp ASC")
    fun getMessagesByState(threadId: String, state: com.communicator.data.core.model.DeliveryState): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM messages WHERE sender = :sender ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesFromSender(sender: String, limit: Int): List<SmsMessageEntity>

    @Query("SELECT * FROM messages WHERE deliveryState = :state ORDER BY timestamp DESC")
    fun getMessagesByDeliveryState(state: com.communicator.data.core.model.DeliveryState): Flow<List<SmsMessageEntity>>

    @Query("SELECT * FROM messages WHERE subscriptionId = :subscriptionId ORDER BY timestamp DESC")
    fun getMessagesBySubscription(subscriptionId: Int): Flow<List<SmsMessageEntity>>

    @Update
    suspend fun updateMessage(message: SmsMessageEntity)

    @Query("UPDATE messages SET deliveryState = :state, errorMessage = :error WHERE messageId = :messageId")
    suspend fun updateDeliveryState(messageId: String, state: com.communicator.data.core.model.DeliveryState, error: String?)

    // The SET clause must bind the parameter Room can actually resolve. It
    // previously referenced :state, which matches no parameter (the two
    // parameters are oldState and newState), so Room rejected the method with
    // "Each bind variable in the query must have a matching function parameter".
    @Query("UPDATE messages SET deliveryState = :newState WHERE deliveryState = :oldState")
    suspend fun updateDeliveryStateBatch(oldState: com.communicator.data.core.model.DeliveryState, newState: com.communicator.data.core.model.DeliveryState)

    /**
     * Stores a message together with its conversation.
     *
     * The conversation insert lives in [ConversationDao], a different Room
     * interface, so this default method cannot call it directly: Room generates
     * the implementations and there is no instance to delegate to here.
     *
     * Callers that need both rows must insert the conversation and the message
     * inside their own @Transaction, or supply a [ConversationDao]. This method
     * is therefore removed rather than left referencing a method that does not
     * exist on this interface.
     */

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteMessagesForThread(threadId: String)
}
