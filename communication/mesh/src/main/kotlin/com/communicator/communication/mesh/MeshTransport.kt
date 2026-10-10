package com.communicator.communication.mesh

import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.TransportCapabilities

/**
 * Mesh / offline communication transport.
 *
 * Supports BLE, Wi-Fi Direct, Wi-Fi Aware, and local network communication.
 * Independent of carrier telephony.
 */
interface MeshTransport : CommunicationTransport {
    /** Discovers nearby mesh peers. */
    suspend fun discoverPeers(timeoutMs: Long = 5000): Result<List<MeshPeer>>

    /** Connects to a mesh peer. */
    suspend fun connectToPeer(peerId: String): Result<Unit>

    /** Sends a store-and-forward message. */
    suspend fun sendMeshMessage(message: MeshMessage): Result<String>

    /** Sends a file over the mesh network. */
    suspend fun sendFile(fileId: String, peerId: String): Result<String>

    /** Starts mesh voice streaming. */
    suspend fun startMeshVoiceCall(peerId: String): CommunicationTransport.CallResult

    /** Starts mesh video streaming. */
    suspend fun startMeshVideoCall(peerId: String): CommunicationTransport.CallResult

    /** Stops a mesh call. */
    suspend fun stopMeshCall(callId: String): CommunicationTransport.CallActionResult

    data class MeshPeer(
        val peerId: String,
        val displayName: String?,
        val connectionType: ConnectionType,
        val rssi: Int?,
        val isTrusted: Boolean,
        val lastSeen: Long,
        val metadata: Map<String, String>
    )

    enum class ConnectionType {
        BLE,
        WIFI_DIRECT,
        WIFI_AWARE,
        LOCAL_NETWORK,
        UNKNOWN
    }

    data class MeshMessage(
        val messageId: String,
        val senderId: String,
        val recipientId: String?,
        val content: String,
        val isEncrypted: Boolean,
        val timestamp: Long,
        val ttl: Long,
        val isDelivered: Boolean = false
    )
}

/**
 * Capability set for mesh transport.
 */
val MeshCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.VOICE,
        com.communicator.data.core.model.Capability.VIDEO,
        com.communicator.data.core.model.Capability.FILE_TRANSFER,
        com.communicator.data.core.model.Capability.OFFLINE,
        com.communicator.data.core.model.Capability.MESH,
        com.communicator.data.core.model.Capability.LOCAL_NETWORK,
        com.communicator.data.core.model.Capability.END_TO_END_ENCRYPTION
    )
)
