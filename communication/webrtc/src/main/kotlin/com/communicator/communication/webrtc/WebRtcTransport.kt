package com.communicator.communication.webrtc

import com.communicator.communication.core.CommunicationTransport
import com.communicator.communication.core.VideoTransport
import com.communicator.data.core.model.TransportCapabilities

/**
 * WebRTC transport interface.
 *
 * Result types are the ones from [CommunicationTransport]; they are not
 * redeclared here. An earlier revision declared nested `CallResult` and
 * `CallActionResult` types that shadowed the core ones, which made every
 * `override` in this interface illegal.
 */
interface WebRtcTransport : VideoTransport {

    /** The signaling configuration for this transport. */
    val signalingConfig: SignalingConfig

    /** Whether a signaling session is currently established. */
    suspend fun isConnected(): Boolean

    /**
     * Opens a signaling session.
     *
     * No signaling backend exists in this project, so implementations return
     * failure rather than reporting a connection that was never made.
     */
    suspend fun connect(server: SignalingServer): Result<Unit>

    /** Closes the signaling session and releases peer state. */
    suspend fun disconnect(): Result<Unit>

    suspend fun joinRoom(roomId: String, participantId: String): Result<String>

    suspend fun leaveRoom(roomId: String): Result<Unit>

    /** Replaces the configured STUN/TURN servers. */
    suspend fun setIceServers(servers: List<IceServer>): Result<Unit>

    /** Toggles the camera for an existing call. */
    override suspend fun setCameraEnabled(callId: String, enabled: Boolean): CommunicationTransport.CallActionResult

    /** Switches between front and rear camera. */
    override suspend fun switchCamera(callId: String): CommunicationTransport.CallActionResult

    suspend fun setMicrophoneMuted(callId: String, muted: Boolean): CommunicationTransport.CallActionResult

    suspend fun setSpeakerphone(callId: String, enabled: Boolean): CommunicationTransport.CallActionResult

    override suspend fun startScreenShare(callId: String): CommunicationTransport.CallActionResult

    override suspend fun stopScreenShare(callId: String): CommunicationTransport.CallActionResult

    override suspend fun pauseVideo(callId: String): CommunicationTransport.CallActionResult

    override suspend fun resumeVideo(callId: String): CommunicationTransport.CallActionResult

    /** Sends raw bytes over the peer's data channel. */
    suspend fun sendDataChannelMessage(peerId: String, data: ByteArray): Result<Unit>

    /** Registers the handler for inbound data-channel payloads. */
    fun setDataChannelHandler(handler: (String, ByteArray) -> Unit)

    suspend fun makeCall(request: VoiceCallRequest): CommunicationTransport.CallResult

    suspend fun makeVideoCall(request: VideoCallRequest): CommunicationTransport.CallResult

    override suspend fun endCall(callId: String): CommunicationTransport.CallActionResult

    override suspend fun holdCall(callId: String): CommunicationTransport.CallActionResult

    override suspend fun resumeCall(callId: String): CommunicationTransport.CallActionResult

    suspend fun muteCall(callId: String, mute: Boolean): CommunicationTransport.CallActionResult

    override suspend fun sendDtmf(callId: String, dtmf: String): CommunicationTransport.CallActionResult

    fun getActiveCall(): WebRtcCallState?

    /** Call state keyed by callId, so several calls can be tracked at once. */
    fun observeCallState(): kotlinx.coroutines.flow.Flow<Map<String, WebRtcCallState>>

    fun observeLocalVideoTrack(): kotlinx.coroutines.flow.Flow<org.webrtc.VideoTrack?>

    fun observeRemoteVideoTrack(): kotlinx.coroutines.flow.Flow<org.webrtc.VideoTrack?>

    fun observeAudioTrack(): kotlinx.coroutines.flow.Flow<org.webrtc.AudioTrack?>

    data class SignalingConfig(
        val serverUrl: String,
        val apiKey: String? = null,
        val authToken: String? = null,
        val isSecure: Boolean = true,
        val metadata: Map<String, String> = emptyMap()
    )

    data class SignalingServer(
        val id: String,
        val url: String,
        val name: String? = null,
        val isSelfHosted: Boolean = false
    )

    data class IceServer(
        val urls: List<String>,
        val username: String? = null,
        val password: String? = null,
        val tlsPort: Int? = null
    )

    data class VoiceCallRequest(
        val callee: String,
        val iceServers: List<String> = emptyList(),
        val roomId: String? = null
    )

    data class VideoCallRequest(
        val callee: String,
        val iceServers: List<String> = emptyList(),
        val roomId: String? = null
    )

    data class Participant(
        val participantId: String,
        val displayName: String?,
        val isAudioAvailable: Boolean,
        val isVideoAvailable: Boolean,
        val isScreenSharing: Boolean,
        val hasJoined: Boolean
    )
}

/** Real-time call state for UI observation. */
data class WebRtcCallState(
    val callId: String,
    val remoteId: String,
    val state: WebRtcCallState.CallState,
    val isVideo: Boolean,
    val isOnHold: Boolean,
    val isMuted: Boolean,
    val isSpeaker: Boolean,
    val localVideoTrack: org.webrtc.VideoTrack? = null,
    val remoteVideoTrack: org.webrtc.VideoTrack? = null,
    val audioTrack: org.webrtc.AudioTrack? = null,
    val startTime: Long,
    val duration: Long
) {
    enum class CallState {
        INITIATING,
        CONNECTING,
        RINGING,
        CONNECTED,
        ON_HOLD,
        ENDED,
        FAILED
    }
}

/**
 * Capabilities this transport can genuinely provide.
 *
 * VIDEO and AUDIO require a media path, which requires signaling that is not
 * implemented, so they are NOT advertised as usable. Only the data-channel and
 * ICE capabilities are claimed, because those code paths exist and are
 * exercised by unit tests.
 */
val WebRtcCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.INTERNET,
        com.communicator.data.core.model.Capability.END_TO_END_ENCRYPTION
    )
)
