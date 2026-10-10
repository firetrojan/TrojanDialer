package com.communicator.communication.webrtc

import android.util.Log
import com.communicator.communication.encrypted.MlsGroupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the Stage 5D durable queue to the WebRTC data-channel transport.
 *
 * Responsibilities, and only these:
 *  - register a peer so the transport has a single owner for its connection and
 *    channel;
 *  - drive the durable outbound queue for the groups that have registered peers;
 *  - hand inbound MLS wire bytes to [MlsGroupManager.processInbound].
 *
 * What it deliberately does NOT do:
 *  - It performs no REMOTE_RECEIVED or PROCESSED transition. No authenticated,
 *    message-bound ACK exists, so advancing those states from an inbound event
 *    would let any peer able to open a channel mark arbitrary messages as
 *    delivered. Those transitions remain fail-closed inside MlsGroupManager.
 *  - It does not report a send as successful when no channel accepted the bytes.
 *    The queue's send callback is suspend, so the real outcome is returned and a
 *    genuine failure becomes a retryable attempt with backoff.
 */
class WebRtcMlsAdapter(
    private val webRtcTransport: WebRtcTransportImpl,
    private val mlsManager: MlsGroupManager,
    private val scope: CoroutineScope
) {

    private val logTag = "WebRtcMlsAdapter"

    private val groupPeers = ConcurrentGroupPeers()
    private val peerToGroup = ConcurrentHashMap<String, String>()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private var processorJob: Job? = null

    init {
        // Inbound payloads are MLS wire bytes, handled as binary by the transport.
        webRtcTransport.setDataChannelHandler { peerId, data -> handleIncomingData(peerId, data) }
    }

    /**
     * Registers a peer for [groupId], creating its PeerConnection and its ordered,
     * reliable data channel.
     *
     * Success here means the native objects were created. It does NOT mean the
     * peer is reachable: negotiation needs signaling, which is not implemented,
     * so the channel will not become OPEN until a backend exists. Callers must
     * treat an OPEN channel, not this call, as evidence of connectivity.
     */
    suspend fun registerPeer(groupId: String, peerId: String): Result<Unit> = runCatching {
        groupPeers.add(groupId, peerId)
        peerToGroup[peerId] = groupId
        webRtcTransport.createPeerConnection(peerId)
        webRtcTransport.createDataChannel(peerId)
        Unit
    }.onFailure {
        // Never leave a half-registered peer: a group listing a peer with no
        // channel would make the queue believe delivery is possible.
        groupPeers.remove(groupId, peerId)
        peerToGroup.remove(peerId)
        Log.w(logTag, "registerPeer($peerId) failed: ${it.message}")
    }

    /** Adopts a channel the remote side opened for a known peer. */
    fun handleIncomingDataChannel(peerId: String, channel: DataChannel) {
        webRtcTransport.handleIncomingDataChannel(peerId, channel)
    }

    /** Removes a peer and releases its native resources. */
    fun unregisterPeer(groupId: String, peerId: String) {
        groupPeers.remove(groupId, peerId)
        peerToGroup.remove(peerId)
        webRtcTransport.releasePeer(peerId)
    }

    /** True when [peerId] is registered for [groupId]. */
    fun isPeerRegistered(groupId: String, peerId: String): Boolean =
        groupPeers.contains(groupId, peerId)

    /** Registered peer ids for [groupId]. */
    fun peersOf(groupId: String): Set<String> = groupPeers.peersOf(groupId)

    /** Starts the background outbound pump. */
    fun startProcessor(intervalMs: Long = 1_000L) {
        if (processorJob?.isActive == true) return
        _isProcessing.value = true
        processorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    for (groupId in groupPeers.groups()) {
                        if (groupPeers.peersOf(groupId).isNotEmpty()) {
                            processOutboundQueue(groupId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(logTag, "outbound pump iteration failed", e)
                }
                delay(intervalMs)
            }
        }
    }

    /** Stops the outbound pump. */
    fun stopProcessor() {
        processorJob?.cancel()
        processorJob = null
        _isProcessing.value = false
    }

    /**
     * Runs one outbound pass for [groupId].
     *
     * The payload goes to every registered peer. Success is reported only if at
     * least one channel actually accepted it; otherwise the failure propagates so
     * the queue schedules a retry with backoff. Reporting success with zero
     * delivered peers would silently drop MLS commits.
     */
    private suspend fun processOutboundQueue(groupId: String) {
        val peers = groupPeers.peersOf(groupId)
        if (peers.isEmpty()) return

        mlsManager.processOutboundQueue(groupId) { _, wire ->
            var acceptedByAny = false
            var lastError: Throwable? = null
            for (peerId in peers) {
                webRtcTransport.sendDataChannelMessage(peerId, wire).fold(
                    onSuccess = { acceptedByAny = true },
                    onFailure = { lastError = it }
                )
            }
            if (acceptedByAny) {
                Result.success(Unit)
            } else {
                Result.failure(
                    lastError ?: IllegalStateException(
                        "No peer accepted the payload for group $groupId"
                    )
                )
            }
        }.onFailure {
            Log.w(logTag, "queue pass failed for $groupId: ${it.message}")
        }
    }

    /**
     * Handles inbound MLS wire bytes.
     *
     * Decryption and validation happen in [MlsGroupManager.processInbound], which
     * rejects malformed, duplicated, stale-epoch and replayed messages. This
     * method reports the outcome and performs no delivery-state transition: the
     * REMOTE_RECEIVED and PROCESSED states require an authenticated ACK that does
     * not exist yet, so nothing here may set them.
     */
    private fun handleIncomingData(peerId: String, data: ByteArray) {
        val groupId = peerToGroup[peerId]
        if (groupId == null) {
            Log.w(logTag, "dropping inbound payload from unregistered peer $peerId")
            return
        }
        scope.launch(Dispatchers.IO) {
            mlsManager.processInbound(groupId, data).fold(
                onSuccess = { inbound ->
                    Log.d(
                        logTag,
                        "peer=$peerId group=$groupId accepted=${inbound != null} " +
                            "(no delivery-state transition: no authenticated ACK)"
                    )
                },
                onFailure = { Log.w(logTag, "peer=$peerId inbound rejected: ${it.message}") }
            )
        }
    }

    /**
     * Thread-safe group -> peers registry. WebRTC callbacks arrive on native
     * threads while the pump reads from a coroutine, so concurrent access matters.
     */
    private class ConcurrentGroupPeers {
        private val map = ConcurrentHashMap<String, MutableSet<String>>()

        fun add(groupId: String, peerId: String) {
            map.computeIfAbsent(groupId) {
                Collections.newSetFromMap(ConcurrentHashMap())
            }.add(peerId)
        }

        fun remove(groupId: String, peerId: String) {
            map[groupId]?.remove(peerId)
        }

        fun contains(groupId: String, peerId: String): Boolean =
            map[groupId]?.contains(peerId) == true

        fun peersOf(groupId: String): Set<String> = map[groupId]?.toSet() ?: emptySet()

        fun groups(): Set<String> = map.keys.toSet()
    }
}
