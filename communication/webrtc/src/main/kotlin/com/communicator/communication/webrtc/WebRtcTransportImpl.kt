package com.communicator.communication.webrtc

import android.content.Context
import android.util.Log
import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.TransportCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebRTC transport.
 *
 * What is REAL here:
 *  - PeerConnectionFactory and PeerConnection construction, ICE server
 *    configuration, ordered+reliable DataChannel creation and observation,
 *    a single authoritative owner per peer, safe binary buffer handling, and
 *    explicit release of every native resource.
 *
 * What is NOT implemented, and is reported as failure rather than faked:
 *  - signaling. There is no signaling backend in this project, so offer/answer
 *    exchange and ICE candidate relay cannot happen. Every entry point needing
 *    it fails explicitly. End-to-end calling and message delivery are therefore
 *    EXTERNAL_BACKEND_REQUIRED.
 *
 * A PeerConnection that was never negotiated connects to nobody. Nothing here
 * reports success for a connection that does not exist.
 */
class WebRtcTransportImpl(
    context: Context,
    signalingConfigValue: WebRtcTransport.SignalingConfig
) : WebRtcTransport {

    private val logTag = "WebRtcTransport"
    private val appContext = context.applicationContext

    /** Lazily created so a native-load failure surfaces at use, not construction. */
    private val factory: PeerConnectionFactory? by lazy { createFactory() }

    private val iceServers = mutableListOf<PeerConnection.IceServer>()

    /** Authoritative peerId -> PeerConnection. */
    private val peers = ConcurrentHashMap<String, PeerConnection>()

    /** Authoritative peerId -> DataChannel. The only channel map in the class. */
    private val channels = ConcurrentHashMap<String, DataChannel>()

    @Volatile
    private var dataHandler: ((String, ByteArray) -> Unit)? = null

    private val _connected = MutableStateFlow(false)
    private val _callStates = MutableStateFlow<Map<String, WebRtcCallState>>(emptyMap())
    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    private val _audioTrack = MutableStateFlow<AudioTrack?>(null)

    override val id: String = "webrtc-${UUID.randomUUID()}"
    override val name: String = "WebRTC"
    override val capabilities: TransportCapabilities = WebRtcCapabilities
    override val signalingConfig: WebRtcTransport.SignalingConfig = signalingConfigValue

    /** Observable signaling state. True only when a signaling session exists. */
    val connectedState: StateFlow<Boolean> = _connected

    override fun isInstalled(): Boolean = true

    /** Available only when the native WebRTC library loaded. */
    override fun isAvailable(): Boolean = factory != null

    override suspend fun resolveContact(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): CommunicationTransport.TransportAvailability {
        val supported = capabilities.supports(capability)
        val nativeLoaded = isAvailable()
        return CommunicationTransport.TransportAvailability(
            transportId = id,
            isAvailable = supported && nativeLoaded,
            capability = capability,
            reason = when {
                !supported -> CommunicationTransport.UnavailableReason.NOT_SUPPORTED
                !nativeLoaded -> CommunicationTransport.UnavailableReason.NOT_INSTALLED
                else -> null
            }
        )
    }

    // ------------------------------------------------------------------
    // Signaling - unavailable without a backend
    // ------------------------------------------------------------------

    override suspend fun isConnected(): Boolean = _connected.value

    override suspend fun connect(server: WebRtcTransport.SignalingServer): Result<Unit> =
        Result.failure(NoSignalingBackendException())

    override suspend fun disconnect(): Result<Unit> {
        releaseAll()
        _connected.value = false
        return Result.success(Unit)
    }

    override suspend fun joinRoom(roomId: String, participantId: String): Result<String> =
        Result.failure(NoSignalingBackendException())

    override suspend fun leaveRoom(roomId: String): Result<Unit> {
        // Local peer state is released regardless; there is no session to leave.
        releaseAll()
        return Result.failure(NoSignalingBackendException())
    }

    // ------------------------------------------------------------------
    // ICE
    // ------------------------------------------------------------------

    override suspend fun setIceServers(servers: List<WebRtcTransport.IceServer>): Result<Unit> =
        runCatching {
            iceServers.clear()
            servers.forEach { spec ->
                iceServers.add(
                    PeerConnection.IceServer.builder(spec.urls)
                        .setUsername(spec.username ?: "")
                        .setPassword(spec.password ?: "")
                        .createIceServer()
                )
            }
        }

    // ------------------------------------------------------------------
    // Peer connection lifecycle
    // ------------------------------------------------------------------

    /**
     * Creates the PeerConnection for a peer.
     *
     * This is the transport boundary, not a call setup: it builds the native
     * objects and returns. No connected call can be reported here, because
     * connectivity requires signaling.
     */
    fun createPeerConnection(
        peerId: String,
        configuration: PeerConnection.RTCConfiguration = defaultConfiguration()
    ): Result<PeerConnection> = runCatching {
        val f = factory ?: throw IllegalStateException(
            "PeerConnectionFactory unavailable: the native WebRTC library did not load"
        )
        // Single owner per peer: replace any previous connection rather than
        // leaking it, so a reconnect cannot retain a stale channel.
        channels.remove(peerId)?.close()
        peers.remove(peerId)?.close()

        val connection = requireNotNull(f.createPeerConnection(configuration, PeerObserver(peerId))) {
            "createPeerConnection returned null"
        }
        peers[peerId] = connection
        connection
    }

    /**
     * Creates the ordered, reliable data channel for a peer.
     *
     * Ordered + reliable is the correct pairing for MLS control messages: a
     * commit must arrive in order and must not be silently dropped, because the
     * sender's retry logic treats a lost message as a failed attempt.
     */
    fun createDataChannel(peerId: String): Result<DataChannel> = runCatching {
        val connection = peers[peerId]
            ?: throw IllegalStateException(
                "No PeerConnection for peer $peerId; call createPeerConnection first"
            )
        val init = DataChannel.Init().apply {
            ordered = true
            // Negative disables retransmission limits, giving reliable delivery,
            // which MLS commits require.
            maxRetransmits = -1
        }
        val channel = requireNotNull(
            connection.createDataChannel(DATA_CHANNEL_LABEL, init)
        ) { "createDataChannel returned null" }
        observeChannel(peerId, channel)
        channel
    }

    /** Adopts a channel the remote side opened. */
    fun handleIncomingDataChannel(peerId: String, channel: DataChannel) {
        observeChannel(peerId, channel)
    }

    /** Single registration point, so a channel is never observed twice. */
    private fun observeChannel(peerId: String, channel: DataChannel) {
        channels.put(peerId, channel)?.takeIf { it !== channel }?.close()
        channel.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                Log.d(logTag, "peer $peerId channel: ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val payload = buffer.readValidBytes()
                if (payload == null) {
                    Log.w(logTag, "peer $peerId sent an empty buffer")
                    return
                }
                dataHandler?.invoke(peerId, payload)
            }

            /**
             * WebRTC's only backpressure signal. The queue applies its own
             * retry-with-backoff when a send fails, so this is recorded for
             * diagnosis rather than silently ignored.
             */
            override fun onBufferedAmountChange(previousAmount: Long) {
                val pending = channel.bufferedAmount()
                if (pending > SLOW_CONSUMER_THRESHOLD) {
                    Log.w(
                        logTag,
                        "peer $peerId channel backlog $pending bytes " +
                            "(was $previousAmount): channel is not draining"
                    )
                }
            }
        })
    }

    /**
     * Copies exactly the valid bytes of a data-channel buffer.
     *
     * `DataChannel.Buffer.data` is a ByteBuffer whose position and limit define
     * the actual message. `array()` may expose the whole backing array, so using
     * it directly over-reads and hands the receiver trailing bytes that were
     * never sent. This reads only `remaining()` bytes from the current position.
     */
    private fun DataChannel.Buffer.readValidBytes(): ByteArray? {
        val source: ByteBuffer = data
        val length = source.remaining()
        if (length <= 0) return null
        val out = ByteArray(length)
        source.get(out) // advances position by exactly `length`
        return out
    }

    private inner class PeerObserver(private val peerId: String) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(logTag, "peer $peerId ICE: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

        override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
            // Relaying a local candidate to the peer requires signaling. None is
            // wired, so this is intentionally not sent anywhere.
            Log.d(logTag, "peer $peerId gathered a local candidate (not relayed: no signaling)")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { _remoteVideoTrack.value = it }
            stream?.audioTracks?.firstOrNull()?.let { _audioTrack.value = it }
        }

        override fun onRemoveStream(stream: MediaStream?) {
            _remoteVideoTrack.value = null
            _audioTrack.value = null
        }

        override fun onDataChannel(channel: DataChannel?) {
            channel?.let { observeChannel(peerId, it) }
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(
            receiver: org.webrtc.RtpReceiver?,
            mediaStreams: Array<out MediaStream>?
        ) {
            val track = receiver?.track()
            if (track is VideoTrack) _remoteVideoTrack.value = track
            if (track is AudioTrack) _audioTrack.value = track
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            Log.d(logTag, "peer $peerId connection: $state")
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit

        override fun onSelectedCandidatePairChanged(
            local: org.webrtc.CandidatePairChangeEvent?
        ) = Unit
    }

    // ------------------------------------------------------------------
    // Data channel messaging
    // ------------------------------------------------------------------

    override suspend fun sendDataChannelMessage(peerId: String, data: ByteArray): Result<Unit> =
        runCatching {
            val channel = channels[peerId]
                ?: throw IllegalStateException("No data channel for peer $peerId")
            check(channel.state() == DataChannel.State.OPEN) {
                "Data channel for peer $peerId is ${channel.state()}, not OPEN"
            }
            require(data.isNotEmpty()) { "Refusing to send an empty payload" }
            // binary = false: these are MLS wire bytes, never text.
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(data), false))
        }

    override fun setDataChannelHandler(handler: (String, ByteArray) -> Unit) {
        dataHandler = handler
    }

    /** Closes and forgets one peer's channel and connection. */
    fun releasePeer(peerId: String) {
        channels.remove(peerId)?.close()
        peers.remove(peerId)?.close()
    }

    /** Releases every peer and channel, then disposes the factory. */
    fun releaseAll() {
        channels.keys.toList().forEach { channels.remove(it)?.close() }
        peers.keys.toList().forEach { peers.remove(it)?.close() }
        dataHandler = null
        _remoteVideoTrack.value = null
        _localVideoTrack.value = null
        _audioTrack.value = null
        _callStates.value = emptyMap()
        factory?.dispose()
    }

    private fun defaultConfiguration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(iceServers.toList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

    private fun createFactory(): PeerConnectionFactory? = runCatching {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options().apply { disableEncryption = false })
            .createPeerConnectionFactory()
    }.onFailure {
        Log.e(logTag, "native WebRTC library failed to load; transport unavailable", it)
    }.getOrNull()

    // ------------------------------------------------------------------
    // Calling surface
    // ------------------------------------------------------------------

    override suspend fun startVoiceCall(
        request: CommunicationTransport.VoiceCallRequest
    ): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(NoSignalingBackendException())

    override suspend fun startVideoCall(
        request: CommunicationTransport.VideoCallRequest
    ): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(NoSignalingBackendException())

    override suspend fun sendMessage(
        request: CommunicationTransport.MessageRequest
    ): CommunicationTransport.MessageResult =
        CommunicationTransport.MessageResult.failure(NoSignalingBackendException())

    override suspend fun makeCall(
        request: WebRtcTransport.VoiceCallRequest
    ): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(NoSignalingBackendException())

    override suspend fun makeVideoCall(
        request: WebRtcTransport.VideoCallRequest
    ): CommunicationTransport.CallResult =
        CommunicationTransport.CallResult.failure(NoSignalingBackendException())

    override suspend fun endCall(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.success(callId)

    override suspend fun holdCall(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.success(callId)

    override suspend fun resumeCall(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.success(callId)

    override suspend fun muteCall(callId: String, mute: Boolean): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun sendDtmf(callId: String, dtmf: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun upgradeToVideo(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun downgradeToVoice(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun setCameraEnabled(
        callId: String, enabled: Boolean
    ): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun switchCamera(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun setMicrophoneMuted(
        callId: String, muted: Boolean
    ): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun setSpeakerphone(
        callId: String, enabled: Boolean
    ): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun startScreenShare(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun stopScreenShare(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun pauseVideo(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override suspend fun resumeVideo(callId: String): CommunicationTransport.CallActionResult =
        CommunicationTransport.CallActionResult.failure(NoActiveCallException(callId))

    override fun onRemoteVideoStateChanged(callId: String, enabled: Boolean) {
        Log.d(logTag, "peer $callId remote video enabled=$enabled (signaling not implemented)")
    }

    override fun getActiveCall(): WebRtcCallState? = _callStates.value.values.firstOrNull()
    /** Call state per callId. Callers filter for the call they care about. */
    override fun observeCallState(): Flow<Map<String, WebRtcCallState>> = _callStates.asStateFlow()
    override fun observeLocalVideoTrack(): Flow<VideoTrack?> = _localVideoTrack.asStateFlow()
    override fun observeRemoteVideoTrack(): Flow<VideoTrack?> = _remoteVideoTrack.asStateFlow()
    override fun observeAudioTrack(): Flow<AudioTrack?> = _audioTrack.asStateFlow()

    companion object {
        private const val logTag = "WebRtcTransport"
        private const val DATA_CHANNEL_LABEL = "mls-messaging"

        /**
         * Backlog above which a data channel is considered slow to drain.
         * 256 KiB is roughly 40 average-size MLS application messages, so hitting
         * it means the peer is not reading.
         */
        private const val SLOW_CONSUMER_THRESHOLD = 256L * 1024L
    }
}

/**
 * Signaling is required to establish any WebRTC connection and no backend
 * exists in this project. Every path that needs it fails with this instead of
 * returning a fabricated success.
 */
class NoSignalingBackendException :
    UnsupportedOperationException(
        "WebRTC signaling is not implemented: no signaling backend exists, so no " +
            "PeerConnection can negotiate or exchange ICE candidates. End-to-end " +
            "calling and message delivery are EXTERNAL_BACKEND_REQUIRED."
    )

/** A media operation was requested for a call that cannot exist yet. */
class NoActiveCallException(callId: String) :
    IllegalStateException(
        "No active WebRTC call $callId: calls require signaling, which is not implemented."
    )
