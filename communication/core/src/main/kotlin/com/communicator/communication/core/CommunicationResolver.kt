package com.communicator.communication.core

import com.communicator.data.core.model.TransportCapabilities

/**
 * Resolves communication endpoints for a contact across available transports.
 *
 * This is the "universal resolver" that determines which transports can reach
 * a given contact and in what order they should be offered to the user based on
 * their preferences.
 */
interface CommunicationResolver {
    /**
     * Returns an ordered list of transports that can reach [contactId] for the
     * requested [capability], sorted by the user's preference order and
     * transport availability.
     */
    suspend fun resolveContact(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): List<TransportResolution>

    /**
     * Resolves the best single transport for reaching [contactId] for the
     * requested [capability].
     */
    suspend fun resolveBestTransport(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): TransportResolution?

    /**
     * Returns all available transports regardless of contact.
     */
    suspend fun getAvailableTransports(): List<TransportResolution>

    /**
     * Returns all installed transports regardless of availability.
     */
    suspend fun getInstalledTransports(): List<TransportResolution>

    data class TransportResolution(
        val transportId: String,
        val transportName: String,
        val capabilities: TransportCapabilities,
        val isAvailable: Boolean,
        val priority: Int,
        val isPreferred: Boolean = false,
        val metadata: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun fromTransport(transport: CommunicationTransport, isPreferred: Boolean = false): TransportResolution {
                return TransportResolution(
                    transportId = transport.id,
                    transportName = transport.name,
                    capabilities = transport.capabilities,
                    isAvailable = transport.isAvailable(),
                    priority = 0,
                    isPreferred = isPreferred
                )
            }
        }
    }
}

/**
 * Default implementation of [CommunicationResolver] that aggregates transports
 * and resolves them based on user preferences and availability.
 */
class DefaultCommunicationResolver(
    private val transports: List<CommunicationTransport>
) : CommunicationResolver {

    override suspend fun resolveContact(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): List<CommunicationResolver.TransportResolution> {
        val resolutions = transports.mapNotNull { transport ->
            val availability = transport.resolveContact(contactId, capability)
            if (availability.isAvailable && transport.capabilities.supports(capability)) {
                CommunicationResolver.TransportResolution.fromTransport(transport).copy(priority = transportOrder(transport.id))
            } else {
                null
            }
        }
        return resolutions.sortedByDescending { it.priority }
    }

    override suspend fun resolveBestTransport(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): CommunicationResolver.TransportResolution? {
        return resolveContact(contactId, capability).firstOrNull()
    }

    override suspend fun getAvailableTransports(): List<CommunicationResolver.TransportResolution> {
        return transports.filter { it.isAvailable() }.map {
            CommunicationResolver.TransportResolution.fromTransport(it)
        }.sortedByDescending { transportOrder(it.transportId) }
    }

    override suspend fun getInstalledTransports(): List<CommunicationResolver.TransportResolution> {
        return transports.filter { it.isInstalled() }.map {
            CommunicationResolver.TransportResolution.fromTransport(it)
        }
    }

    private fun transportOrder(transportId: String): Int {
        // Preference order: Carrier > E2E > SIP > WebRTC > Mesh
        return when {
            transportId.startsWith("carrier") -> 100
            transportId.startsWith("encrypted") -> 90
            transportId.startsWith("sip") -> 80
            transportId.startsWith("webrtc") -> 70
            transportId.startsWith("mesh") -> 60
            transportId.startsWith("sms") -> 50
            transportId.startsWith("mms") -> 40
            else -> 0
        }
    }
}
