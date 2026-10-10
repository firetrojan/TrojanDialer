package com.communicator.communication.mms

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.app.PendingIntent
import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.Attachment
import com.communicator.data.core.model.TransportCapabilities

/**
 * MMS (Multimedia Messaging Service) transport.
 *
 * Uses REAL PUBLIC Android APIs:
 * - SmsManager.sendMultimediaMessage() (API 21+) for sending MMS
 * - SmsManager.downloadMultimediaMessage() (API 31+) for downloading MMS
 * - content://mms provider for MMS storage
 * - WAP Push for incoming MMS notifications
 * 
 * As default SMS app, uses SmsManager.sendMultimediaMessage() and downloadMultimediaMessage()
 * which are PUBLIC APIs since API 21/31 respectively.
 * Platform MmsService handles MMSC transaction, carrier config, PDU encoding internally.
 */
interface MmsTransport : com.communicator.communication.core.CommunicationTransport {

    /** The subscription ID for multi-SIM MMS. */
    val subscriptionId: Int?

    /** Whether this app is the default SMS/MMS handler. */
    suspend fun isDefaultMmsApp(): Boolean

    /** Requests the default SMS/MMS role (will prompt user via system dialog). */
    suspend fun requestDefaultMmsRole(): Boolean

    /** Sends an MMS message with attachments using REAL PUBLIC API SmsManager.sendMultimediaMessage(). */
    suspend fun sendMms(request: MmsRequest): MmsResult

    /** Downloads an MMS from carrier using REAL PUBLIC API SmsManager.downloadMultimediaMessage(). */
    suspend fun downloadMms(locationUrl: String, contentUri: Uri, configOverrides: Bundle?, downloadedIntent: PendingIntent): MmsResult

    /** Retrieves an MMS by ID from content provider. */
    suspend fun getMms(mmsId: String): MmsMessage?

    /** Observes incoming MMS messages via content provider observation. */
    suspend fun observeMms(): kotlinx.coroutines.flow.Flow<MmsMessage>

    /** Gets MMS threads/conversations. */
    suspend fun getThreads(): kotlinx.coroutines.flow.Flow<MmsThread>

    /** Gets messages for a thread. */
    suspend fun getMessages(threadId: String): kotlinx.coroutines.flow.Flow<MmsMessage>

    data class MmsRequest(
        val address: String,
        val subject: String? = null,
        val textContent: String? = null,
        val attachments: List<MmsAttachment> = emptyList(),
        val subscriptionId: Int? = null,
        val deliveryReport: Boolean = false,
        val readReport: Boolean = false
    )

    data class MmsAttachment(
        val contentUri: android.net.Uri,
        val mimeType: String,
        val filename: String? = null,
        val contentId: String? = null,
        val contentLocation: String? = null
    )

    data class MmsResult(
        val success: Boolean,
        val messageId: String? = null,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(messageId: String): MmsResult = MmsResult(true, messageId)
            fun failure(error: Throwable): MmsResult = MmsResult(false, error = error)
        }
    }

    /** MMS message model. */
    data class MmsMessage(
        val messageId: String,
        val threadId: String,
        val address: String,
        val subject: String?,
        val textContent: String?,
        val attachments: List<MmsAttachment> = emptyList(),
        val timestamp: Long,
        val direction: MmsDirection,
        val state: MmsState,
        val subscriptionId: Int?,
        val transportId: String = "mms",
        val messageSize: Long = 0,
        val read: Boolean = false,
        val deliveryState: MmsDeliveryState = MmsDeliveryState.PENDING
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

    data class MmsThread(
        val threadId: String,
        val recipient: String,
        val lastMessage: MmsMessage?,
        val messageCount: Int,
        val unreadCount: Int,
        val isArchived: Boolean,
        val isSpam: Boolean,
        val transportId: String = "mms"
    )
}

/**
 * Capability set for MMS transport.
 */
val MmsCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.MMS,
        com.communicator.data.core.model.Capability.FILE_TRANSFER,
        com.communicator.data.core.model.Capability.CARRIER
    )
)
