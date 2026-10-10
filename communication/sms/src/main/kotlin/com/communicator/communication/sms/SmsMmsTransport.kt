package com.communicator.communication.sms

import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.TransportCapabilities

/**
 * SMS / MMS messaging transport.
 *
 * Independent of carrier calling transport. Uses Android's default SMS
 * role where required for sending/receiving.
 */
interface SmsMmsTransport : CommunicationTransport {
    /** The subscription ID for multi-SIM SMS. */
    val subscriptionId: Int?

    /** Whether this app is the default SMS handler. */
    suspend fun isDefaultSmsApp(): Boolean

    /** Requests the default SMS role (will prompt user via system dialog). */
    suspend fun requestDefaultSmsRole(): Boolean

    suspend fun sendSms(request: SmsRequest): SmsMmsResult
    suspend fun sendMms(request: MmsRequest): SmsMmsResult
    suspend fun receiveMessages(): kotlinx.coroutines.flow.Flow<com.communicator.data.core.model.Message>
    suspend fun getThreads(): kotlinx.coroutines.flow.Flow<List<MessageThread>>
    suspend fun getMessages(threadId: String): kotlinx.coroutines.flow.Flow<List<com.communicator.data.core.model.Message>>

    data class SmsRequest(
        val address: String,
        val content: String,
        val subscriptionId: Int? = null,
        val deliveryReport: Boolean = false,
        val readReport: Boolean = false,
        val scheduledTime: Long? = null,
        val isMultipart: Boolean = false
    )

    data class MmsRequest(
        val address: String,
        val content: String,
        val attachments: List<com.communicator.data.core.model.Attachment> = emptyList(),
        val subscriptionId: Int? = null,
        val deliveryReport: Boolean = false,
        val readReport: Boolean = false,
        val scheduledTime: Long? = null
    )

    data class SmsMmsResult(
        val success: Boolean,
        val messageId: String? = null,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(messageId: String): SmsMmsResult = SmsMmsResult(true, messageId)
            fun failure(error: Throwable): SmsMmsResult = SmsMmsResult(false, error = error)
        }
    }
}

/**
 * Convenience alias for SMS/MMS transport capabilities.
 */
val SmsMmsTransportCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.SMS,
        com.communicator.data.core.model.Capability.MMS,
        com.communicator.data.core.model.Capability.FILE_TRANSFER
    )
)

data class MessageThread(
    val threadId: String,
    val recipient: String,
    val contactId: String?,
    val lastMessage: com.communicator.data.core.model.Message?,
    val messageCount: Int,
    val unreadCount: Int,
    val isArchived: Boolean,
    val isSpam: Boolean,
    val transportId: String
)
