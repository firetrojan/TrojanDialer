package com.communicator.app

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.communicator.communication.sms.SmsMmsTransport
import com.communicator.communication.sms.SmsRequest
import com.communicator.communication.sms.SmsTransportImpl
import com.communicator.communication.sms.SmsMmsResult
import com.communicator.data.core.model.DeliveryState
import com.communicator.data.core.sms.ConversationEntity
import com.communicator.data.core.sms.ConversationDao
import com.communicator.data.core.sms.SmsMessageEntity
import com.communicator.data.core.sms.SmsMessageDao
// NOTE: .sms and .sip BOTH declare a MessageDirection (and a DeliveryState).
// This file imports the SMS one explicitly under an alias so the simple name
// can never resolve to the SIP enum by accident.
import com.communicator.data.core.sms.MessageDirection as SmsMessageDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class SmsRepository(
    private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: SmsMessageDao,
    private val subscriptionManager: SubscriptionManager
) {

    private val transports = mutableMapOf<Int, SmsTransportImpl>()
    private val activeSubscriptionId = MutableStateFlow<Int?>(null)

    init {
        // Initialize transports for all active subscriptions
        subscriptionManager.activeSubscriptionInfoList?.forEach { subInfo ->
            val transport = SmsTransportImpl(context, subInfo.subscriptionId)
            transports[subInfo.subscriptionId] = transport
            transport.sentResultCallback = { messageId, error ->
                onSentResult(messageId, error)
            }
            transport.deliveredResultCallback = { messageId ->
                onDeliveredResult(messageId)
            }
        }
        
        // Set default to first active subscription
        subscriptionManager.activeSubscriptionInfoList?.firstOrNull()?.let {
            activeSubscriptionId.value = it.subscriptionId
        }
    }

    fun getTransports(): List<SmsTransportImpl> {
        return transports.values.toList()
    }

    fun getTransport(subscriptionId: Int): SmsTransportImpl? {
        return transports[subscriptionId]
    }

    fun getActiveTransport(): SmsTransportImpl? {
        return activeSubscriptionId.value?.let { transports[it] }
    }

    fun setActiveSubscription(subscriptionId: Int) {
        if (transports.containsKey(subscriptionId)) {
            activeSubscriptionId.value = subscriptionId
        }
    }

    val activeSubscription: kotlinx.coroutines.flow.StateFlow<Int?> = activeSubscriptionId

    suspend fun sendSms(
        address: String,
        content: String,
        subscriptionId: Int? = null,
        deliveryReport: Boolean = false
    ): SmsMmsResult {
        val targetSubscriptionId = subscriptionId ?: activeSubscriptionId.value
        val transport = targetSubscriptionId?.let { transports[it] } ?: getActiveTransport()
        
        return if (transport != null) {
            val request = SmsRequest(
                address = address,
                content = content,
                subscriptionId = transport.subscriptionId,
                deliveryReport = deliveryReport
            )
            transport.sendSms(request)
        } else {
            SmsMmsResult.failure(IllegalStateException("No active SMS transport"))
        }
    }

    private fun onSentResult(messageId: String, error: Throwable?) {
        CoroutineScope(Dispatchers.IO).launch {
            if (error != null) {
                messageDao.updateDeliveryState(messageId, DeliveryState.FAILED, error.message)
            } else {
                messageDao.updateDeliveryState(messageId, DeliveryState.SENT, null)
            }
            // Update conversation last message
            updateConversationForMessage(messageId)
        }
    }

    private fun onDeliveredResult(messageId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            messageDao.updateDeliveryState(messageId, DeliveryState.DELIVERED, null)
        }
    }

    private suspend fun updateConversationForMessage(messageId: String) {
        val message = messageDao.getMessage(messageId)
        message?.let { msg ->
            val conversation = ConversationEntity(
                threadId = msg.threadId,
                participant = if (msg.direction == SmsMessageDirection.OUTGOING) msg.recipients.firstOrNull() ?? "" else msg.sender,
                contactId = null,
                lastMessageId = msg.messageId,
                lastMessageContent = msg.content,
                lastMessageTimestamp = msg.timestamp,
                unreadCount = if (msg.direction == SmsMessageDirection.INCOMING) 1 else 0,
                isArchived = false,
                isSpam = false,
                transportId = "sms",
                subscriptionId = msg.subscriptionId
            )
            conversationDao.insertConversation(conversation)
        }
    }

    // Incoming SMS handling with duplicate prevention
    fun onIncomingSms(
        sender: String,
        content: String,
        timestamp: Long,
        subscriptionId: Int,
        isMultipart: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val threadId = generateThreadId(sender)
            
            // Create a deterministic message ID based on content hash + timestamp + sender
            // This prevents duplicates from multiple broadcasts of the same message
            val contentHash = "${sender}:${content}:${timestamp}:${subscriptionId}".hashCode()
            val messageId = "incoming_${contentHash}_${timestamp}"

            // Check for existing message with same identity (duplicate prevention)
            val existingMessage = messageDao.getMessage("incoming_${"${sender}:${content}:${timestamp}:${subscriptionId}".hashCode()}_${timestamp}")
            if (existingMessage != null) {
                Log.d("SmsRepository", "Duplicate SMS ignored: $sender at $timestamp")
                return@launch
            }

            val message = SmsMessageEntity(
                messageId = messageId,
                threadId = generateThreadId(sender),
                sender = sender,
                recipients = listOf(),
                content = content,
                direction = SmsMessageDirection.INCOMING,
                timestamp = timestamp,
                deliveryState = com.communicator.data.core.model.DeliveryState.DELIVERED,
                subscriptionId = subscriptionId,
                transportId = "sms",
                isMultipart = false
            )

            messageDao.insertMessage(message)

            val conversation = ConversationEntity(
                threadId = generateThreadId(sender),
                participant = sender,
                contactId = null,
                lastMessageId = messageId,
                lastMessageContent = content,
                lastMessageTimestamp = timestamp,
                unreadCount = 1,
                isArchived = false,
                isSpam = false,
                transportId = "sms",
                subscriptionId = subscriptionId
            )
            conversationDao.insertConversation(conversation)
        }
    }

    private fun generateThreadId(address: String): String {
        return address.normalizePhoneNumber()
    }

    suspend fun getConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.getAllConversations()
    }

    suspend fun getConversation(threadId: String): ConversationEntity? {
        return conversationDao.getConversation(threadId)
    }

    suspend fun getMessages(threadId: String): Flow<List<SmsMessageEntity>> {
        return messageDao.getMessagesForThread(threadId)
    }

    suspend fun markAsRead(threadId: String) {
        conversationDao.setUnreadCount(threadId, 0)
    }

    suspend fun deleteConversation(threadId: String) {
        conversationDao.deleteConversation(threadId)
        messageDao.deleteMessagesForThread(threadId)
    }
}
