package com.communicator.communication.core

import com.communicator.data.core.model.Contact
import com.communicator.data.core.model.TransportCapabilities

/**
 * Specialized interface for transports that support video calling.
 * Carries the additional state needed for video-specific operations such as
 * camera control, local/remote video state, and video profile negotiation.
 */
interface VideoTransport : CommunicationTransport {
    /**
     * Starts a video call to [request.address] for [contactId].
     * Must verify video capability via [TransportCapabilities] before calling.
     */
    override suspend fun startVideoCall(request: CommunicationTransport.VideoCallRequest): CommunicationTransport.CallResult

    /**
     * Upgrades an ongoing voice call to a video call.
     */
    override suspend fun upgradeToVideo(callId: String): CommunicationTransport.CallActionResult

    /**
     * Downgrades an ongoing video call to a voice call.
     */
    override suspend fun downgradeToVoice(callId: String): CommunicationTransport.CallActionResult

    /**
     * Toggles the camera on/off for an ongoing video call.
     */
    suspend fun setCameraEnabled(callId: String, enabled: Boolean): CommunicationTransport.CallActionResult

    /**
     * Switches between front and rear camera.
     */
    suspend fun switchCamera(callId: String): CommunicationTransport.CallActionResult

    suspend fun pauseVideo(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(UnsupportedOperationException("Pause video not supported by transport ${id}"))

    suspend fun resumeVideo(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(UnsupportedOperationException("Resume video not supported by transport ${id}"))

    suspend fun startScreenShare(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(UnsupportedOperationException("Screen share not supported by transport ${id}"))

    suspend fun stopScreenShare(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(UnsupportedOperationException("Stop screen share not supported by transport ${id}"))

    /**
     * Called when the remote party's video state changes.
     */
    fun onRemoteVideoStateChanged(callId: String, enabled: Boolean)
}
