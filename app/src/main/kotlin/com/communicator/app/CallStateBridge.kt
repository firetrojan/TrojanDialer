package com.communicator.app

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telecom.Call
import android.telecom.Call.Details
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object CallStateBridge {
    private val _calls = MutableStateFlow<Map<String, CallInfo>>(emptyMap())
    val calls: StateFlow<Map<String, CallInfo>> = _calls

    private val _activeCallId = MutableStateFlow<String?>(null)
    val activeCallId: StateFlow<String?> = _activeCallId

    private val _isInCall = MutableStateFlow(false)
    val isInCall: StateFlow<Boolean> = _isInCall

    // Live Call registry - owned by InCallService, NOT persisted
    private val liveCalls = mutableMapOf<String, Call>()

    private var commandHandler: ((String, CallCommand) -> Boolean)? = null

    enum class CallCommand {
        ANSWER,
        REJECT,
        DISCONNECT,
        HOLD,
        UNHOLD,
        MUTE,
        UNMUTE,
        SET_SPEAKERPHONE
    }

    fun setCommandHandler(handler: (String, CallCommand) -> Boolean) {
        commandHandler = handler
    }

    fun clearCommandHandler() {
        commandHandler = null
    }

    // Called by InCallService when a call is added
    fun onCallAdded(call: Call) {
        val callId = call.details.callId
        liveCalls[callId] = call
        val info = CallInfo.fromCall(call)
        _calls.update { it + (info.callId to info) }
        updateActiveCall()
    }

    // Called by InCallService when a call is removed
    fun onCallRemoved(call: Call) {
        val callId = call.details.callId
        liveCalls.remove(callId)
        _calls.update { it - callId }
        if (_activeCallId.value == callId) {
            _activeCallId.value = null
        }
        updateInCallState()
    }

    // Called by InCallService when call state changes
    fun onCallStateChanged(call: Call, state: Int) {
        val callId = call.details.callId
        liveCalls[callId] = call // Update reference
        _calls.update { calls ->
            val existing = calls[callId]
            if (existing != null) {
                calls + (callId to existing.copy(state = state))
            } else {
                calls
            }
        }
        updateActiveCall()
    }

    // Called by InCallService when call details change
    fun onCallDetailsChanged(call: Call, details: Details) {
        val callId = details.callId
        liveCalls[callId] = call // Update reference
        _calls.update { calls ->
            val existing = calls[callId]
            if (existing != null) {
                calls + (callId to existing.copy(
                    number = details.handle?.schemeSpecificPart,
                    displayName = details.handle?.schemeSpecificPart,
                    isVideo = details.hasVideoCall,
                    isMuted = details.isMuted,
                    state = details.state
                ))
            } else {
                calls
            }
        }
    }

    // Called by InCallService for audio route changes
    fun onAudioRouteChanged(audioRoute: Int) {
        _calls.update { calls ->
            calls.mapValues { (callId, info) ->
                info.copy(audioRoute = audioRoute)
            }
        }
    }

    // Called by InCallService for capability changes
    fun onCanPutOnHoldChanged(call: Call, canPutOnHold: Boolean) {
        updateCallCapabilities(call, { it.copy(canHold = canPutOnHold) })
    }

    fun onCanMergeChanged(call: Call, canMerge: Boolean) {
        updateCallCapabilities(call, { it.copy(canMerge = canMerge) })
    }

    fun onCanSwapChanged(call: Call, canSwap: Boolean) {
        updateCallCapabilities(call, { it.copy(canSwap = canSwap) })
    }

    fun onCanConferenceChanged(call: Call, canConference: Boolean) {
        updateCallCapabilities(call, { it.copy(canConference = canConference) })
    }

    fun onCanDisconnectChanged(call: Call, canDisconnect: Boolean) {
        updateCallCapabilities(call, { it.copy(canDisconnect = canDisconnect) })
    }

    fun onCanAddCallChanged(call: Call, canAddCall: Boolean) {
        updateCallCapabilities(call, { it.copy(canAddCall = canAddCall) })
    }

    private fun updateCallCapabilities(call: Call, transform: CallInfo.Capabilities.() -> CallInfo.Capabilities) {
        val callId = call.details.callId
        _calls.update { calls ->
            val existing = calls[callId]
            if (existing != null) {
                calls + (callId to existing.copy(capabilities = existing.capabilities.transform()))
            } else {
                calls
            }
        }
    }

    private fun updateActiveCall() {
        val activeCall = _calls.value.values.firstOrNull { it.state in setOf(
            Call.STATE_ACTIVE,
            Call.STATE_HOLDING,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_RINGING
        ) }
        _activeCallId.value = activeCall?.callId
        updateInCallState()
    }

    private fun updateInCallState() {
        _isInCall.value = _calls.value.values.any { it.state in setOf(
            Call.STATE_ACTIVE,
            Call.STATE_HOLDING,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_RINGING
        ) }
    }

    // Reconstruct registry from framework calls (on service reconnect)
    fun reconstructRegistry(calls: List<Call>) {
        liveCalls.clear()
        val newCalls = mutableMapOf<String, CallInfo>()
        for (call in calls) {
            val callId = call.details.callId
            liveCalls[callId] = call
            newCalls[callId] = CallInfo.fromCall(call)
        }
        _calls.value = newCalls
        updateActiveCall()
        updateInCallState()
    }

    // Clear registry (on service destroy)
    fun clearRegistry() {
        liveCalls.clear()
        _calls.value = emptyMap()
        _activeCallId.value = null
        _isInCall.value = false
    }

    // Command execution - resolves call ID to live Call and executes
    fun executeCommand(callId: String, command: CallCommand): Boolean {
        val call = liveCalls[callId]
        if (call == null) {
            Log.w("CallStateBridge", "No live Call for callId: $callId")
            return false
        }
        return when (command) {
            CallCommand.ANSWER -> call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
            CallCommand.REJECT -> call.reject(false)
            CallCommand.DISCONNECT -> {
                call.disconnect()
                true
            }
            CallCommand.HOLD -> call.hold()
            CallCommand.UNHOLD -> call.unhold()
            CallCommand.MUTE -> call.mute(true)
            CallCommand.UNMUTE -> call.mute(false)
            CallCommand.SET_SPEAKERPHONE -> {
                // Speakerphone via AudioManager is handled separately
                false
            }
            else -> false
        }
    }

    fun getActiveCallId(): String? = _activeCallId.value
    fun isInCall(): Boolean = _isInCall.value
    fun getCallCount(): Int = _calls.value.size

    fun getLiveCall(callId: String): Call? = liveCalls[callId]

    fun getCallInfo(callId: String): CallInfo? = _calls.value[callId]

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
        // Capability flags from framework
        val canHold: Boolean = false,
        val canMerge: Boolean = false,
        val canSwap: Boolean = false,
        val canConference: Boolean = false,
        val canDisconnect: Boolean = true,
        val canAddCall: Boolean = false,
        // Capabilities data class
        val capabilities: Capabilities = Capabilities()
    ) {
        companion object {
            fun fromCall(call: Call): CallInfo {
                val details = call.details
                return CallInfo(
                    callId = details.callId,
                    number = details.handle?.schemeSpecificPart,
                    displayName = details.handle?.schemeSpecificPart,
                    state = details.state,
                    isVideo = details.hasVideoCall,
                    isMuted = details.isMuted,
                    isOnHold = details.state == Call.STATE_HOLDING,
                    isSpeaker = false,
                    audioRoute = 0,
                    startTime = details.startTimeMillis,
                    duration = if (details.state == Call.STATE_ACTIVE) System.currentTimeMillis() - details.startTimeMillis else 0,
                    canHold = details.can(android.telecom.Call.Details.CAPABILITY_HOLD),
                    canMerge = details.can(android.telecom.Call.Details.CAPABILITY_MERGE_CONFERENCE),
                    canSwap = details.can(android.telecom.Call.Details.CAPABILITY_SWAP),
                    canConference = details.can(android.telecom.Call.Details.CAPABILITY_CONFERENCE),
                    canDisconnect = details.can(android.telecom.Call.Details.CAPABILITY_DISCONNECT),
                    canAddCall = details.can(android.telecom.Call.Details.CAPABILITY_ADD_CALL)
                )
            }
        }

        fun getStateLabel(): String = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_SELECTED -> "SELECTED"
            else -> "UNKNOWN($state)"
        }

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

        data class Capabilities(
            val canHold: Boolean = false,
            val canMerge: Boolean = false,
            val canSwap: Boolean = false,
            val canConference: Boolean = false,
            val canDisconnect: Boolean = true,
            val canAddCall: Boolean = false
        )
    }
}

class InCallServiceImpl : InCallService() {
    private val logTag = "InCallServiceImpl"

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag, "onCreate")
        // Register this service as the command handler
        CallStateBridge.setCommandHandler { callId, command ->
            // Commands are executed on the CallStateBridge thread, but we need to ensure
            // the Call reference is valid. The Call objects are owned by this service.
            true // Handler registered
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallStateBridge.clearRegistry()
        CallStateBridge.clearCommandHandler()
        Log.d(logTag, "onDestroy - registry cleared")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d("InCallService", "onCallAdded: ${call.details.callId}, state: ${call.details.state}")
        CallStateBridge.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d("InCallService", "onCallRemoved: ${call.details.callId}")
        CallStateBridge.onCallRemoved(call)
    }

    override fun onCallStateChanged(call: Call, state: Int) {
        super.onCallStateChanged(call, state)
        Log.d("InCallService", "onCallStateChanged: ${call.details.callId}, state: $state")
        CallStateBridge.onCallStateChanged(call, state)
    }

    override fun onCallDetailsChanged(call: Call, details: Details) {
        super.onCallDetailsChanged(call, details)
        Log.d("InCallService", "onCallDetailsChanged: ${details.callId}")
        CallStateBridge.onCallDetailsChanged(call, details)
    }

    override fun onAudioRouteChanged(audioRoute: Int) {
        super.onAudioRouteChanged(audioRoute)
        Log.d("InCallService", "onAudioRouteChanged: $audioRoute")
        CallStateBridge.onAudioRouteChanged(audioRoute)
    }

    override fun onCanAddCallChanged(call: Call, canAddCall: Boolean) {
        super.onCanAddCallChanged(call, canAddCall)
        CallStateBridge.onCanAddCallChanged(call, canAddCall)
    }

    override fun onCanPutOnHoldChanged(call: Call, canPutOnHold: Boolean) {
        super.onCanPutOnHoldChanged(call, canPutOnHold)
        CallStateBridge.onCanPutOnHoldChanged(call, canPutOnHold)
    }

    override fun onCanMergeChanged(call: Call, canMerge: Boolean) {
        super.onCanMergeChanged(call, canMerge)
        CallStateBridge.onCanMergeChanged(call, canMerge)
    }

    override fun onCanSwapChanged(call: Call, canSwap: Boolean) {
        super.onCanSwapChanged(call, canSwap)
        CallStateBridge.onCanSwapChanged(call, canSwap)
    }

    override fun onCanConferenceChanged(call: Call, canConference: Boolean) {
        super.onCanConferenceChanged(call, canConference)
        CallStateBridge.onCanConferenceChanged(call, canConference)
    }

    override fun onCanDisconnectChanged(call: Call, canDisconnect: Boolean) {
        super.onCanDisconnectChanged(call, canDisconnect)
        CallStateBridge.onCanDisconnectChanged(call, canDisconnect)
    }

    override fun onVideoStateChanged(call: Call, videoState: VideoProfile) {
        super.onVideoStateChanged(call, videoState)
        Log.d(logTag, "onVideoStateChanged: ${call.details.callId}, videoState: $videoState")
    }

    override fun onRttInitiationSuccess(call: Call) {
        super.onRttInitiationSuccess(call)
    }

    override fun onRttInitiationFailure(call: Call, reason: Int) {
        super.onRttInitiationFailure(call, reason)
    }

    override fun onRttRemoteCallEnded(call: Call) {
        super.onRttRemoteCallEnded(call)
    }

    override fun onRttRemoteCallUpgraded(call: Call) {
        super.onRttRemoteCallUpgraded(call)
    }

    // Handle commands from UI
    fun executeCommand(callId: String, command: CallStateBridge.CallCommand): Boolean {
        return CallStateBridge.executeCommand(callId, command)
    }
}
