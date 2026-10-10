package com.communicator.communication.diagnostics

import com.communicator.data.core.model.TransportCapabilities
import com.communicator.data.core.model.Capability

/**
 * Messaging transport interface for messaging-only transports.
 */
interface MessagingTransport {
    val id: String
    val name: String
    val capabilities: TransportCapabilities
    
    fun isAvailable(): Boolean
    
    suspend fun sendMessage(
        recipient: String,
        content: String,
        attachments: List<com.communicator.data.core.model.Attachment> = emptyList()
    ): MessageSendResult
    
    suspend fun receiveMessages(): kotlinx.coroutines.flow.Flow<com.communicator.data.core.model.Message>
    
    suspend fun getThreads(): kotlinx.coroutines.flow.Flow<List<MessageThread>>
    
    suspend fun getMessages(threadId: String, limit: Int = 50): kotlinx.coroutines.flow.Flow<List<com.communicator.data.core.model.Message>>
    
    suspend fun markAsRead(threadId: String)
    
    suspend fun deleteThread(threadId: String)
    
    suspend fun getDeliveryStatus(messageId: String): DeliveryState
    
    data class MessageSendResult(
        val success: Boolean,
        val messageId: String? = null,
        val error: Throwable? = null
    ) {
        companion object {
            fun success(messageId: String): MessageSendResult = MessageSendResult(true, messageId)
            fun failure(error: Throwable): MessageSendResult = MessageSendResult(false, error = error)
        }
    }
    
    data class MessageThread(
        val threadId: String,
        val recipient: String,
        val contactId: String?,
        val lastMessage: com.communicator.data.core.model.Message?,
        val messageCount: Int,
        val unreadCount: Int,
        val isArchived: Boolean
    )
    
    enum class DeliveryState {
        PENDING,
        SENT,
        DELIVERED,
        READ,
        FAILED
    }
}
