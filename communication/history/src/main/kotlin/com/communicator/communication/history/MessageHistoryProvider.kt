package com.communicator.communication.history

import com.communicator.data.core.model.Message

/**
 * Message history provider.
 * Stores and manages message threads.
 */
interface MessageHistoryProvider {
    suspend fun insertMessage(message: Message): Result<String>
    suspend fun updateMessage(message: Message): Result<Unit>
    suspend fun deleteMessage(messageId: String): Result<Unit>
    suspend fun getMessage(messageId: String): Result<Message?>
    suspend fun getThreads(): kotlinx.coroutines.flow.Flow<List<MessageThread>>
    suspend fun getMessages(threadId: String): kotlinx.coroutines.flow.Flow<List<Message>>
    suspend fun searchMessages(query: String): kotlinx.coroutines.flow.Flow<List<Message>>
    suspend fun archiveThread(threadId: String)
    suspend fun deleteThread(threadId: String)
    suspend fun markAsRead(threadId: String)
    suspend fun getUnreadCount(): kotlinx.coroutines.flow.Flow<Int>

    data class MessageThread(
        val threadId: String,
        val transportId: String,
        val contactId: String?,
        val recipient: String,
        val lastMessage: Message?,
        val messageCount: Int,
        val unreadCount: Int,
        val isArchived: Boolean,
        val isSpam: Boolean
    )
}
