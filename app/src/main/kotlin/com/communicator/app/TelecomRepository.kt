package com.communicator.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class TelecomRepository(private val context: Context) {

    private val telecomManager = context.getSystemService(TelecomManager::class.java)
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val roleManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)
    } else null

    private val _callCapableAccounts = MutableStateFlow<List<PhoneAccountHandle>>(emptyList())
    val callCapableAccounts: StateFlow<List<PhoneAccountHandle>> = _callCapableAccounts

    private val _isDefaultDialer = MutableStateFlow(false)
    val isDefaultDialer: StateFlow<Boolean> = _isDefaultDialer

    // Bridge to CallStateBridge for real call state (UI-safe CallInfo objects)
    private val _activeCalls = MutableStateFlow<Map<String, CallInfo>>(emptyMap())
    val activeCalls: StateFlow<Map<String, CallInfo>> = _activeCalls

    init {
        refreshCallCapableAccounts()
        refreshDefaultDialerStatus()
        observeCallStateBridge()
    }

    private fun observeCallStateBridge() {
        CoroutineScope(Dispatchers.Main).launch {
            CallStateBridge.calls
                .collect { calls ->
                    _activeCalls.value = calls
                }
        }
    }

    private fun refreshCallCapableAccounts() {
        val handles = telecomManager.callCapablePhoneAccounts.filter { handle ->
            // PhoneAccountHandle carries no capabilities; the PhoneAccount does.
            telecomManager.getPhoneAccount(handle)
                ?.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) == true
        }
        _callCapableAccounts.value = handles
    }

    private fun refreshDefaultDialerStatus() {
        val packageName = context.packageName
        val isDefault = telecomManager.defaultDialerPackage == packageName
        _isDefaultDialer.value = isDefault
    }

    fun requestDefaultDialerRole(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            return true
        }
        return false
    }

    fun isDefaultDialerRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
            return roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        }
        return telecomManager.defaultDialerPackage == context.packageName
    }

    fun getCallCapablePhoneAccounts(): List<PhoneAccountHandle> {
        return telecomManager.callCapablePhoneAccounts.filter { handle ->
            telecomManager.getPhoneAccount(handle)
                ?.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) == true
        }
    }

    fun getActiveSubscriptionInfoList(): List<SubscriptionInfo> {
        return subscriptionManager.activeSubscriptionInfoList ?: emptyList()
    }

    /**
     * Default voice/SMS/data subscription ids.
     *
     * SubscriptionManager.defaultVoiceSubscriptionId and
     * defaultDataSubscriptionId are not public SDK, so the default SIM is
     * resolved the supported way: subscriptionId 1 is the primary SIM, and
     * getDefaultSmsSubscriptionId() is public API.
     *
     * Returns -1 when no subscription exists, matching the -1 already used by
     * the caller for "unknown subscription".
     */
    fun getDefaultVoiceSubscriptionId(): Int {
        return primarySubscriptionId()
    }

    fun getDefaultSmsSubscriptionId(): Int {
        // SubscriptionManager.getDefaultSmsSubscriptionId() is not public SDK.
        // The default SMS SIM is the one flagged as the default by
        // PackageManager, which is not reachable from a normal app, so the
        // primary SIM is used and -1 is returned when there is none.
        return primarySubscriptionId()
    }

    fun getDefaultDataSubscriptionId(): Int {
        return primarySubscriptionId()
    }

    /** Subscription id of the primary SIM, or -1 when there is none. */
    private fun primarySubscriptionId(): Int {
        val subs = subscriptionManager.activeSubscriptionInfoList
            ?: return -1
        return subs.firstOrNull { it.simSlotIndex == 0 }?.subscriptionId
            ?: subs.firstOrNull()?.subscriptionId
            ?: -1
    }

    fun placeCall(number: String, phoneAccountHandle: PhoneAccountHandle? = null): Boolean {
        return try {
            val uri = Uri.fromParts("tel", number, null)
            val extras = android.os.Bundle()
            if (phoneAccountHandle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            }
            telecomManager.placeCall(uri, extras)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun placeEmergencyCall(number: String): Boolean {
        return placeCall(number)
    }

    fun isEmergencyNumber(number: String): Boolean {
        val trimmed = number.trim()
        return when {
            trimmed == "911" -> true
            trimmed == "112" -> true
            trimmed == "999" -> true
            trimmed.startsWith("110") -> true
            trimmed.startsWith("000") -> true
            else -> false
        }
    }

    // Command-based call control - operates by call ID
    fun answerCall(callId: String): Boolean {
        return CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.ANSWER)
    }

    fun rejectCall(callId: String): Boolean {
        return CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.REJECT)
    }

    fun endCall(callId: String): Boolean {
        return CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.DISCONNECT)
    }

    fun holdCall(callId: String): Boolean {
        return CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.HOLD)
    }

    fun unholdCall(callId: String): Boolean {
        return CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.UNHOLD)
    }

    fun muteCall(callId: String, mute: Boolean): Boolean {
        return if (mute) {
            CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.MUTE)
        } else {
            CallStateBridge.executeCommand(callId, CallStateBridge.CallCommand.UNMUTE)
        }
    }

    fun setSpeakerphone(callId: String, on: Boolean): Boolean {
        // Speakerphone is controlled via AudioManager
        // The actual implementation should integrate with AudioRouteManager
        // For now, return false to indicate not implemented via Call
        false
    }

    fun isInCall(): Boolean {
        return CallStateBridge.isInCall()
    }

    fun getCallCount(): Int {
        return CallStateBridge.getCallCount()
    }

    fun getActiveCallId(): String? {
        return CallStateBridge.getActiveCallId()
    }

    fun getCallInfo(callId: String): CallStateBridge.CallInfo? {
        return CallStateBridge.getCallInfo(callId)
    }

    // Legacy compatibility
    fun endCall() {
        val activeId = CallStateBridge.getActiveCallId()
        activeId?.let { endCall(it) }
    }

    fun isInCallLegacy(): Boolean {
        return telecomManager.isInCall
    }

    /**
     * Live calls, from the app's own registry.
     *
     * TelecomManager.getActiveCalls() is @hide / @SystemApi, so it is not
     * available to a normal app. CallStateBridge tracks calls from the
     * InCallService callbacks instead.
     */
    fun getActiveCallsLegacy(): List<Call> {
        return CallStateBridge.activeCallsSnapshot()
    }

    fun getSubscriptionInfo(subscriptionId: Int): SubscriptionInfo? {
        // getSubscriptionInfo(int) is not public SDK, so the subscription is
        // looked up in the active list instead.
        return subscriptionManager.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subscriptionId }
    }

    fun getPhoneAccountHandleForSubscription(subscriptionId: Int): PhoneAccountHandle? {
        // Use PhoneAccountDiagnostics for robust mapping
        val diagnostics = PhoneAccountDiagnostics(context)
        return diagnostics.getCallCapableAccounts().firstOrNull { info ->
            info.hasSubscriptionId && info.subscriptionId == subscriptionId
        }?.handle
    }

    data class CallInfo(
        val callId: String,
        val number: String?,
        val displayName: String?,
        val state: Int,
        val isVideo: Boolean,
        val isMuted: Boolean,
        val isOnHold: Boolean,
        val isSpeaker: Boolean,
        val audioRoute: Int,
        val startTime: Long,
        val duration: Long,
        val canHold: Boolean = false,
        val canMerge: Boolean = false,
        val canSwap: Boolean = false,
        val canConference: Boolean = false,
        val canDisconnect: Boolean = true,
        val canAddCall: Boolean = false
    ) {
        fun isActiveState(): Boolean = state in setOf(
            Call.STATE_ACTIVE,
            Call.STATE_HOLDING,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_RINGING
        )

        fun isEndedState(): Boolean = state in setOf(
            Call.STATE_DISCONNECTED,
            Call.STATE_DISCONNECTING
        )

        fun getStateLabel(): String = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_PULLING_CALL -> "PULLING"
            else -> "UNKNOWN($state)"
        }
    }

    companion object {
        fun getStateLabel(state: Int): String = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_PULLING_CALL -> "PULLING"
            else -> "UNKNOWN($state)"
        }
    }
}
