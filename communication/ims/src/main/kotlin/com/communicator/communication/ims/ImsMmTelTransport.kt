package com.communicator.communication.ims

import com.communicator.communication.core.CommunicationTransport
import com.communicator.communication.core.VideoTransport
import com.communicator.data.core.model.TransportCapabilities

/**
 * IMS (IP Multimedia Subsystem) MMTEL transport.
 *
 * Provides access to carrier IMS services including:
 * - VoLTE (Voice over LTE)
 * - VoWiFi (Voice over Wi-Fi)
 * - ViLTE (Video over LTE)
 *
 * Uses Android's ImsMmTelManager and Telephony APIs.
 * Does NOT implement IMS registration inside the APK - it only
 * reads the state exposed by the device's carrier IMS stack.
 */
interface ImsMmTelTransport : VideoTransport {
    /** Checks if IMS is registered for the given subscription. */
    suspend fun isImsRegistered(subscriptionId: Int): Boolean

    /** Checks if MMTEL feature is available. */
    suspend fun isMmTelAvailable(subscriptionId: Int): Boolean

    /** Checks if VoLTE is available. */
    suspend fun hasVolteCapability(subscriptionId: Int): Boolean

    /** Checks if VoWiFi is available. */
    suspend fun hasVoWifiCapability(subscriptionId: Int): Boolean

    /** Checks if ViLTE (video) is available. */
    suspend fun hasVilteCapability(subscriptionId: Int): Boolean

    /** Gets the IMS registration tech (LTE, NR, WiFi). */
    suspend fun getImsRegistrationTech(subscriptionId: Int): ImsRegistrationTech?

    /** Gets the MMTEL capabilities for a subscription. */
    suspend fun getMmTelCapabilities(subscriptionId: Int): MmTelCapabilityState

    /**
     * Accepts an incoming IMS call.
     *
     * [videoState] was typed as a `VideoState` that never existed in the core
     * module, so the call state is expressed with the `isVideo` flag instead.
     */
    suspend fun acceptCall(
        callId: String,
        isVideo: Boolean = false
    ): CommunicationTransport.CallActionResult

    /** Rejects an incoming IMS call. */
    suspend fun rejectCall(callId: String): CommunicationTransport.CallActionResult

    /** Sends DTMF via IMS. */
    override suspend fun sendDtmf(callId: String, dtmf: String): CommunicationTransport.CallActionResult

    enum class ImsRegistrationTech {
        LTE,
        NR,
        WIFI
    }

    data class MmTelCapabilityState(
        val isVoiceEnabled: Boolean,
        val isVideoEnabled: Boolean,
        val isVtLocalTxEnabled: Boolean,
        val isVtLocalRxEnabled: Boolean,
        val isCpoverWifiEnabled: Boolean,
        val isConcurrentVoiceAndVideo: Boolean
    )
}

/**
 * Capability set for IMS MMTEL transport.
 */
val ImsMmTelCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.VOICE,
        com.communicator.data.core.model.Capability.VIDEO,
        com.communicator.data.core.model.Capability.CALL_HOLD,
        com.communicator.data.core.model.Capability.CALL_WAITING,
        com.communicator.data.core.model.Capability.CONFERENCE,
        com.communicator.data.core.model.Capability.CALL_TRANSFER,
        com.communicator.data.core.model.Capability.VOICE_TO_VIDEO,
        com.communicator.data.core.model.Capability.VIDEO_TO_VOICE,
        com.communicator.data.core.model.Capability.CARRIER,
        com.communicator.data.core.model.Capability.INTERNET
    )
)
