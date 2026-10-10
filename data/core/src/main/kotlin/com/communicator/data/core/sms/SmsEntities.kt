package com.communicator.data.core.sms

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.communicator.data.core.model.DeliveryState
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "conversations",
    indices = [
        Index("lastMessageTimestamp"),
        Index("participant"),
        Index("unreadCount")
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val threadId: String,
    val participant: String,
    val contactId: String? = null,
    val lastMessageId: String? = null,
    val lastMessageContent: String? = null,
    val lastMessageTimestamp: Long = 0,
    val unreadCount: Int = 0,
    val isArchived: Boolean = false,
    val isSpam: Boolean = false,
    val transportId: String = "sms",
    val subscriptionId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@TypeConverters(SmsConverters::class)
@Entity(
    tableName = "messages",
    indices = [
        Index("threadId"),
        Index("timestamp"),
        Index("sender"),
        Index("deliveryState"),
        Index("subscriptionId")
    ]
)
data class SmsMessageEntity(
    @PrimaryKey
    val messageId: String,
    val threadId: String,
    val sender: String,
    val recipients: List<String>,
    val content: String,
    val direction: MessageDirection,
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryState: DeliveryState = DeliveryState.PENDING,
    val subscriptionId: Int? = null,
    val transportId: String = "sms",
    val subscriptionName: String? = null,
    val errorMessage: String? = null,
    val isMultipart: Boolean = false,
    val multipartSequence: Int? = null,
    val multipartTotal: Int? = null,
    val referenceNumber: String? = null
)

enum class MessageDirection {
    INCOMING,
    OUTGOING
}
