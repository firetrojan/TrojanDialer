package com.communicator.communication.core

import com.communicator.data.core.model.Capability
import com.communicator.data.core.model.TransportCapabilities

/**
 * Base interface for all communication transports.
 * A transport is any component that can perform communication over a specific
 * medium (carrier telephony, SIP, WebRTC, E2E encrypted, mesh, etc).
 *
 * Not every implementation must support every method. Implementers should call
 * [TransportCapabilities.supports] before using features and return
 * [CallResult.Failure] / [MessageResult.Failure] with an appropriate
 * [UnsupportedOperationException] when a capability is not available.
 */
interface CommunicationTransport {
    /** Unique identifier for this transport instance (e.g. "carrier-1", "sip-account-42"). */
    val id: String

    /** Human-readable name for display in UI. */
    val name: String

    /** The real capabilities reported by this transport. */
    val capabilities: TransportCapabilities

    /** Whether this transport is fundamentally installed / configured on the device. */
    fun isInstalled(): Boolean = true

    /** Whether this transport is currently available for use (registered, network up, etc). */
    fun isAvailable(): Boolean = false

    /**
     * Resolves whether this transport can reach the given contact via the
     * requested capability. Used for capability discovery before showing
     * transport options in the UI.
     */
    suspend fun resolveContact(
        contactId: String,
        capability: Capability
    ): TransportAvailability

    suspend fun startVoiceCall(request: VoiceCallRequest): CallResult
    suspend fun startVideoCall(request: VideoCallRequest): CallResult
    suspend fun sendMessage(request: MessageRequest): MessageResult

    // Optional methods that may or may not be supported. Implementers
    // should return CallResult.Failure(UnsupportedOperationException(...))
    // if the corresponding capability is absent.
    suspend fun holdCall(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Hold not supported by transport $id"))

    suspend fun resumeCall(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Resume not supported by transport $id"))

    suspend fun endCall(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("End not supported by transport $id"))

    suspend fun mergeCalls(conferenceId: String, callIds: List<String>): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Merge not supported by transport $id"))

    suspend fun swapCalls(activeCallId: String, heldCallId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Swap not supported by transport $id"))

    suspend fun sendDtmf(callId: String, dtmf: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("DTMF not supported by transport $id"))

    suspend fun setAudioRoute(callId: String, route: AudioRoute): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Audio routing not supported by transport $id"))

    suspend fun upgradeToVideo(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Voice-to-video upgrade not supported by transport $id"))

    suspend fun downgradeToVoice(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Video-to-voice downgrade not supported by transport $id"))

    suspend fun startRecording(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Recording not supported by transport $id"))

    suspend fun stopRecording(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Recording not supported by transport $id"))

    suspend fun startTranscription(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Transcription not supported by transport $id"))

    suspend fun stopTranscription(callId: String): CallActionResult =
        CallActionResult.failure(UnsupportedOperationException("Transcription not supported by transport $id"))

    /** Called by the framework when the transport is being shut down or uninstalled. */
    suspend fun close() {}

    /** Listener for transport events such as incoming calls and state changes. */
    val eventListener: TransportEventListener?
        get() = null

    /** Data classes for requests and results. */
    data class VoiceCallRequest(
        val contactId: String,
        val address: String,
        val subscriptionId: Int? = null,
        val isVideo: Boolean = false,
        val audioRoute: AudioRoute? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    data class VideoCallRequest(
        val contactId: String,
        val address: String,
        val subscriptionId: Int? = null,
        val enableCamera: Boolean = true,
        val audioRoute: AudioRoute? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    data class MessageRequest(
        val contactId: String,
        val address: String,
        val content: String,
        val attachments: List<com.communicator.data.core.model.Attachment> = emptyList(),
        val subscriptionId: Int? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    data class TransportAvailability(
        val transportId: String,
        val isAvailable: Boolean,
        val capability: Capability,
        val reason: UnavailableReason? = null
    )

    enum class UnavailableReason {
        NOT_INSTALLED,
        NOT_REGISTERED,
        OFFLINE,
        BLOCKED,
        NOT_SUPPORTED,
        NO_NETWORK,
        CARRIER_RESTRICTION,
        PERMISSION_DENIED,
        UNKNOWN
    }

    data class CallResult(
        val success: Boolean,
        val callId: String? = null,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(callId: String): CallResult = CallResult(true, callId)
            fun failure(error: Throwable): CallResult = CallResult(false, error = error)
        }
    }

    data class MessageResult(
        val success: Boolean,
        val messageId: String? = null,
        val error: Throwable? = null
    ) {
        companion object {
            fun success(messageId: String): MessageResult = MessageResult(true, messageId)
            fun failure(error: Throwable): MessageResult = MessageResult(false, error = error)
        }
    }

    data class CallActionResult(
        val success: Boolean,
        val callId: String? = null,
        val error: Throwable? = null,
        val metadata: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(callId: String): CallActionResult = CallActionResult(true, callId)
            fun failure(error: Throwable): CallActionResult = CallActionResult(false, error = error)
        }
    }
}

enum class AudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    NONE
}

/**
 * Listener for events from a transport, such as incoming calls.
 */
interface TransportEventListener {
    fun onIncomingCall(incomingCall: IncomingCallInfo)
    fun onCallStateChanged(callState: CallState)
    fun onMessageReceived(message: com.communicator.data.core.model.Message)
    fun onTransportAvailabilityChanged(availability: CommunicationTransport.TransportAvailability)
    fun onError(error: Throwable)
}

data class IncomingCallInfo(
    val callId: String,
    val transportId: String,
    val callerId: String?,
    val callerName: String?,
    val isVideo: Boolean,
    val isBlocked: Boolean,
    val subscriptionId: Int? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class CallState(
    val callId: String,
    val transportId: String,
    val state: CallStateStatus,
    val isVideo: Boolean,
    val isOnHold: Boolean,
    val isMuted: Boolean,
    val isSpeaker: Boolean,
    val audioRoute: AudioRoute?,
    val duration: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

enum class CallStateStatus {
    RINGING,
    OFFHOOK,
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ON_HOLD
}
