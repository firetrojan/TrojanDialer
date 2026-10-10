package com.communicator.communication.rcs

import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.Attachment
import com.communicator.data.core.model.TransportCapabilities

/**
 * RCS (Rich Communication Services) transport.
 *
 * Investigates Android/carrier limitations first.
 * RCS is ONLY implemented through supported/public mechanisms.
 * If ordinary third-party APK access is not available, this provider
 * exposes diagnostic state and does not fake RCS.
 */
interface RcsTransport : CommunicationTransport {
    /**
     * Checks whether RCS is accessible through Android APIs.
     * Returns the access method or null if not available.
     */
    suspend fun getRcsAccessMode(): RcsAccessMode?

    /** Requests RCS capabilities for a contact. */
    suspend fun getRcsCapabilities(contactId: String): RcsCapabilities

    /** Sends an RCS message. */
    suspend fun sendRcsMessage(message: RcsMessage): RcsResult

    /** Sends an RCS fileTransfer. */
    suspend fun sendRcsFileTransfer(fileId: String, recipient: String): RcsResult

    /** Starts an RCS voice call. */
    suspend fun startRcsVoiceCall(contactId: String, address: String): CommunicationTransport.CallResult

    /** Starts an RCS video call. */
    suspend fun startRcsVideoCall(contactId: String, address: String): CommunicationTransport.CallResult

    /** Observes RCS messages. */
    fun observeRcsMessages(): kotlinx.coroutines.flow.Flow<RcsMessage>

    data class RcsMessage(
        val messageId: String,
        val sender: String,
        val recipients: List<String>,
        val content: String,
        val contentType: String = "text/plain",
        val attachments: List<Attachment> = emptyList(),
        val isTyping: Boolean = false
    )

    data class RcsCapabilities(
        val isAvailable: Boolean,
        val isChatEnabled: Boolean,
        val isVoiceEnabled: Boolean,
        val isVideoEnabled: Boolean,
        val isFileTransferEnabled: Boolean,
        val isGroupChatEnabled: Boolean,
        val isTypingEnabled: Boolean,
        val isDeliveredReadEnabled: Boolean,
        val reason: String? = null
    )

    enum class RcsAccessMode {
        ANDROID_API,
        CARRIER_APP,
        SYSTEM_API,
        INTENT_BASED,
        NOT_AVAILABLE
    }

    data class RcsResult(
        val success: Boolean,
        val messageId: String? = null,
        val error: Throwable? = null,
        val accessMode: RcsAccessMode? = null
    ) {
        companion object {
            fun success(messageId: String): RcsResult = RcsResult(true, messageId)
            fun failure(error: Throwable): RcsResult = RcsResult(false, error = error)
        }
    }
}

/**
 * Capability set for RCS. RCS capabilities are determined dynamically
 * based on what the carrier/device exposes.
 */
val RcsCapabilitiesSet = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.VOICE,
        com.communicator.data.core.model.Capability.VIDEO,
        com.communicator.data.core.model.Capability.FILE_TRANSFER,
        com.communicator.data.core.model.Capability.GROUP_CALL,
        com.communicator.data.core.model.Capability.GROUP_VIDEO,
        com.communicator.data.core.model.Capability.END_TO_END_ENCRYPTION,
        com.communicator.data.core.model.Capability.CARRIER,
        com.communicator.data.core.model.Capability.INTERNET
    )
)
