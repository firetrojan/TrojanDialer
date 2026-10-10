package com.communicator.communication.carrier

import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionInfo
import com.communicator.communication.core.CommunicationTransport
import com.communicator.communication.core.VideoTransport
import com.communicator.data.core.model.TransportCapabilities

/**
 * Carrier-native telephony transport.
 *
 * Provides voice calling via the device's SIM / carrier telephony stack,
 * integrated with Android TelecomManager.
 */
interface CarrierTelephonyTransport : CommunicationTransport {
    /** The subscription IDs (SIM slots) this transport can use. */
    val subscriptionIds: List<Int>

    /** Returns the [PhoneAccountHandle]s registered for this transport. */
    val phoneAccountHandles: List<PhoneAccountHandle>

    /** Returns [SubscriptionInfo] for the subscription with [subscriptionId]. */
    suspend fun getSubscriptionInfo(subscriptionId: Int): SubscriptionInfo?

    /** Returns the active subscription IDs on this device. */
    suspend fun getActiveSubscriptions(): List<Int>

    /** Returns the default voice subscription ID. */
    suspend fun getDefaultVoiceSubscription(): Int?

    /** Returns the default SMS subscription ID. */
    suspend fun getDefaultSmsSubscription(): Int?

    /** Returns the default data subscription ID. */
    suspend fun getDefaultDataSubscription(): Int?

    /** Selects a specific SIM for a call. */
    suspend fun selectSubscriptionForCall(subscriptionId: Int): Boolean

    /** Checks if the device supports dual SIM. */
    suspend fun isMultiSimSupported(): Boolean
}

/**
 * Carrier video calling transport.
 *
 * Uses Android's IMS / MMTEL stack for carrier-native ViLTE video calling.
 * Does NOT use SIP or WebRTC for this feature.
 *
 * Capabilities must be discovered from the real carrier IMS stack:
 * only report video capability when the device/carrier exposes it.
 */
interface CarrierVideoTransport : VideoTransport {
    /** Returns whether IMS is registered for the given subscription. */
    suspend fun isImsRegistered(subscriptionId: Int): Boolean

    /** Returns whether MMTEL is available for the given subscription. */
    suspend fun isMmTelAvailable(subscriptionId: Int): Boolean

    /** Returns whether VoLTE is enabled for the given subscription. */
    suspend fun hasVolteCapability(subscriptionId: Int): Boolean

    /** Returns whether VoWiFi is enabled for the given subscription. */
    suspend fun hasVoWifiCapability(subscriptionId: Int): Boolean

    /** Returns whether ViLTE (carrier video) is available for the given subscription. */
    suspend fun hasVilteCapability(subscriptionId: Int): Boolean

    /** Returns whether the carrier supports voice-to-video upgrade. */
    suspend fun supportsUpgradeToVideo(subscriptionId: Int): Boolean

    /** Returns whether the carrier supports video-to-voice downgrade. */
    suspend fun supportsDowngradeToVoice(subscriptionId: Int): Boolean

    /** Returns whether the device supports bidirectional video, receive-only, or transmit-only. */
    suspend fun getVideoProfile(subscriptionId: Int): CarrierVideoProfile

    /** Returns the [PhoneAccountHandle] to use for carrier video on [subscriptionId]. */
    suspend fun getPhoneAccountHandle(subscriptionId: Int): PhoneAccountHandle?

    /** Returns the carrier config state for diagnostics. */
    suspend fun getCarrierConfigState(subscriptionId: Int): CarrierConfigState

    /** The capabilities of this transport must reflect the real IMS stack. */
    override val capabilities: TransportCapabilities
}

/**
 * Which video directions the carrier IMS stack supports for a subscription.
 *
 * This replaces a `VideoProfile` type that the core module never defined. The
 * three flags mirror the MMTEL capability set the platform exposes
 * (video-enabled, transmit, receive).
 */
data class CarrierVideoProfile(
    val isVideoEnabled: Boolean,
    val isTransmitEnabled: Boolean,
    val isReceiveEnabled: Boolean
)

/**
 * Diagnostic state for carrier configuration.
 */
data class CarrierConfigState(
    val isImsRegistered: Boolean,
    val mmTelAvailable: Boolean,
    val volteEnabled: Boolean,
    val voWifiEnabled: Boolean,
    val vilteAvailable: Boolean,
    val supportsUpgrade: Boolean,
    val supportsDowngrade: Boolean,
    val videoProfile: CarrierVideoProfile,
    val carrierName: String?,
    val mcc: Int,
    val mnc: Int
)
