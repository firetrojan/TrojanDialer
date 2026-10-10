package com.communicator.app

import android.content.Context
import com.communicator.communication.webrtc.WebRtcTransport
import com.communicator.communication.webrtc.WebRtcTransportImpl
import com.communicator.communication.webrtc.WebRtcCallState
import com.communicator.communication.webrtc.WebRtcMessage
import com.communicator.communication.webrtc.WebRtcResult
import com.communicator.communication.webrtc.WebRtcTransport
import com.communicator.communication.webrtc.WebRtcTransport.SignalingServer
import com.communicator.communication.webrtc.WebRtcTransport.SignalingConfig
import com.communicator.communication.webrtc.WebRtcTransport.IceServer
import com.communicator.communication.webrtc.WebRtcTransport.VoiceCallRequest
import com.communicator.communication.webrtc.WebRtcTransport.VideoCallRequest
import com.communicator.communication.webrtc.WebRtcTransport.CallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class WebRtcRepository(
    private val context: Context
) {

    private val transports = mutableMapOf<String, WebRtcTransportImpl>()
    private val activeSessionId = mutableStateFlow<String?>(null)

    init {
        // Initialize WebRTC transport
        val transport = WebRtcTransportImpl(
            context = context,
            signalingConfig = WebRtcTransport.SignalingConfig(
                serverUrl = "",
                isSecure = true
            )
        )
        transports["default"] = transport
        activeSessionId.value = "default"
    }

    fun getTransports(): List<WebRtcTransportImpl> {
        return ArrayList(transports.values)
    }

    fun getTransport(sessionId: String): WebRtcTransportImpl? {
        return transports[sessionId]
    }

    fun getActiveTransport(): WebRtcTransportImpl? {
        return activeSessionId.value?.let { transports[it] }
    }

    fun setActiveSession(sessionId: String) {
        if (transports.containsKey(sessionId)) {
            activeSessionId.value = sessionId
        }
    }

    val activeSession: kotlinx.coroutines.flow.StateFlow<String?> = activeSessionId

    suspend fun connect(
        serverUrl: String,
        apiKey: String? = null,
        authToken: String? = null,
        isSecure: Boolean = true,
        iceServers: List<String> = listOf("stun:stun.l.google.com:19302")
    ): WebRtcResult {
        val transport = getActiveTransport()
        return transport?.connect(
            WebRtcTransport.SignalingServer(
                id = UUID.randomUUID().toString(),
                url = serverUrl,
                isSelfHosted = false
            )
        ) ?: WebRtcResult.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun makeCall(
        destination: String,
        iceServers: List<String> = listOf("stun:stun.l.google.com:19302"),
        roomId: String? = null
    ): Result<CallResult> {
        val transport = getActiveTransport()
        return transport?.makeCallInternal(
            WebRtcTransport.VoiceCallRequest(
                callee = destination,
                iceServers = iceServers,
                roomId = roomId
            )
        ) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun makeVideoCall(
        destination: String,
        iceServers: List<String> = listOf("stun:stun.l.google.com:19302"),
        roomId: String? = null
    ): Result<CallResult> {
        val transport = getActiveTransport()
        return transport?.makeCallInternal(
            WebRtcTransport.VideoCallRequest(
                callee = destination,
                iceServers = iceServers,
                roomId = roomId
            )
        ) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun endCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.endCall(callId) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun holdCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.holdCall(callId) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun resumeCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.resumeCall(callId) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun muteCall(callId: String, mute: Boolean): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.muteCall(callId, mute) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    suspend fun sendDtmf(callId: String, digit: Char): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.sendDtmf(callId, digit) ?: Result.failure(IllegalStateException("No active WebRTC transport"))
    }

    fun getTransports(): List<WebRtcTransportImpl> {
        return ArrayList(transports.values)
    }

    fun getActiveTransport(): WebRtcTransportImpl? {
        return activeSessionId.value?.let { transports[it] }
    }

    suspend fun getCalls(): Flow<List<WebRtcCallState>> {
        val transport = getActiveTransport()
        return transport?.observeCallState() ?: kotlinx.coroutines.flow.flow { }
    }

    fun getActiveCall(): WebRtcCallState? {
        val transport = getActiveTransport()
        return transport?.getActiveCall()
    }

    suspend fun sendMessage(
        message: WebRtcMessage
    ): WebRtcResult {
        val transport = getActiveTransport()
        return transport?.sendMessage(message) ?: WebRtcResult.failure(IllegalStateException("No active WebRTC transport"))
    }
}
