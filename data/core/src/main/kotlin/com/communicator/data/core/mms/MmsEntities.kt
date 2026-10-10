package com.communicator.data.core.mms

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.communicator.data.core.model.DeliveryState
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "mms_messages",
    foreignKeys = [
        ForeignKey(
            entity = MmsThreadEntity::class,
            parentColumns = ["threadId"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("threadId"),
        Index("timestamp"),
        Index("address"),
        Index("direction"),
        Index("state"),
        Index("subscriptionId")
    ]
)
data class MmsMessageEntity(
    @PrimaryKey
    val messageId: String,
    val threadId: String,
    val address: String,
    val subject: String? = null,
    val textContent: String? = null,
    val direction: MmsDirection,
    val timestamp: Long = System.currentTimeMillis(),
    val state: MmsState = MmsState.DRAFT,
    val subscriptionId: Int? = null,
    val transportId: String = "mms",
    val messageSize: Long = 0,
    val read: Boolean = false,
    val deliveryState: MmsDeliveryState = MmsDeliveryState.PENDING,
    val errorMessage: String? = null,
    val mmsType: Int = 0
)

@Serializable
@Entity(
    tableName = "mms_threads",
    indices = [
        Index("lastMessageTimestamp"),
        Index("recipient"),
        Index("unreadCount")
    ]
)
data class MmsThreadEntity(
    @PrimaryKey
    val threadId: String,
    val recipient: String,
    val lastMessageId: String? = null,
    val lastMessageContent: String? = null,
    val lastMessageTimestamp: Long = 0,
    val unreadCount: Int = 0,
    val isArchived: Boolean = false,
    val isSpam: Boolean = false,
    val transportId: String = "mms",
    val subscriptionId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
@Entity(
    tableName = "mms_parts",
    foreignKeys = [
        ForeignKey(
            entity = MmsMessageEntity::class,
            parentColumns = ["messageId"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("messageId"),
        // The column is `contentType`; `mimeType` never existed on this entity,
        // so Room could not resolve the index and refused to build.
        Index("contentType"),
        Index("contentId")
    ]
)
data class MmsPartEntity(
    @PrimaryKey
    val partId: String,
    val messageId: String,
    val contentType: String,
    val contentUri: String? = null,
    val filename: String? = null,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val size: Long = 0,
    val textContent: String? = null,
    val charset: String? = null,
    val contentLocationType: Int = 0
)

enum class MmsDirection {
    INCOMING,
    OUTGOING
}

enum class MmsState {
    DRAFT,
    QUEUED,
    SENDING,
    SENT,
    FAILED
}

enum class MmsDeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    FAILED
}
