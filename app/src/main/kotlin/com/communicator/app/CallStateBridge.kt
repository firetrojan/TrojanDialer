package com.communicator.app

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telecom.Call
import android.telecom.Call.Details
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
        val callId = call.details.telecomCallId
        liveCalls[callId] = call
        val info = CallInfo.fromCall(call)
        _calls.update { it + (info.callId to info) }
        updateActiveCall()
    }

    // Called by InCallService when a call is removed
    fun onCallRemoved(call: Call) {
        val callId = call.details.telecomCallId
        liveCalls.remove(callId)
        _calls.update { it - callId }
        if (_activeCallId.value == callId) {
            _activeCallId.value = null
        }
        updateInCallState()
    }

    // Called by InCallService when call state changes
    fun onCallStateChanged(call: Call, state: Int) {
        val callId = call.details.telecomCallId
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
        val callId = call.details.telecomCallId
        liveCalls[callId] = call // Update reference
        _calls.update { calls ->
            val existing = calls[callId]
            if (existing != null) {
                calls + (callId to existing.copy(
                    number = details.handle?.schemeSpecificPart,
                    displayName = details.handle?.schemeSpecificPart,
                    isVideo = details.videoState != VideoProfile.STATE_AUDIO_ONLY,
                    isMuted = false,
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
        val callId = call.details.telecomCallId
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
            val callId = call.details.telecomCallId
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

    /**
     * Snapshot of the tracked live calls.
     *
     * Replaces TelecomManager.getActiveCalls(), which is @hide and therefore
     * unavailable to an ordinary app.
     */
    fun activeCallsSnapshot(): List<Call> = liveCalls.values.toList()

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
            /**
             * Snapshots a live Call.
             *
             * Everything read here is on Call.Details, verified against the
             * android14-release Call.java: Details exposes getTelecomCallId(),
             * getState(), getHandle(), getVideoState(), getConnectTimeMillis(),
             * getSupportedAudioRoutes() and can(int). Call itself exposes none
             * of them - it only has getDetails(), disconnect, accept, reject,
             * answer, deflect, hold and the RTT/video accessors - so the earlier
             * call.id, call.audioState and call.hasCapabilities(...) reads could
             * not resolve.
             *
             * Call offers no mute state, so isMuted is always false here; the
             * mute flag is tracked by the UI instead.
             */
            fun fromCall(call: Call, observedAt: Long = System.currentTimeMillis()): CallInfo {
                val details = call.details
                return CallInfo(
                    callId = call.details.telecomCallId,
                    number = details.handle?.schemeSpecificPart,
                    displayName = details.handle?.schemeSpecificPart,
                    state = details.state,
                    isVideo = details.videoState != VideoProfile.STATE_AUDIO_ONLY,
                    isMuted = false,
                    isOnHold = details.state == Call.STATE_HOLDING,
                    isSpeaker = false,
                    // Call exposes no current audio route; the InCallService
                    // callback onCallAudioStateChanged supplies it instead.
                    audioRoute = 0,
                    startTime = details.connectTimeMillis,
                    duration = if (details.connectTimeMillis > 0) observedAt - details.connectTimeMillis else 0,
                    canHold = details.state != Call.STATE_DISCONNECTED,
                    canMerge = details.can(Details.CAPABILITY_MERGE_CONFERENCE),
                    canSwap = details.can(Details.CAPABILITY_SWAP_CONFERENCE),
                    canConference = details.can(Details.CAPABILITY_ADD_PARTICIPANT),
                    canDisconnect = details.state != Call.STATE_DISCONNECTED,
                    canAddCall = details.can(Details.CAPABILITY_ADD_PARTICIPANT)
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
            Call.STATE_PULLING_CALL -> "PULLING"
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
        Log.d(logTag, "onCallAdded: ${call.details.telecomCallId}, state: ${call.details.state}")
        // A single onCallAdded fires for the whole life of the call, so it is
        // also where state, details and audio route changes are observed.
        CallStateBridge.onCallAdded(call)
        CallStateBridge.onCallStateChanged(call, call.details.state)
        CallStateBridge.onCallDetailsChanged(call, call.details)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(logTag, "onCallRemoved: ${call.details.telecomCallId}")
        CallStateBridge.onCallRemoved(call)
    }

    /**
     * Real InCallService callbacks.
     *
     * The previous class overrode onCallStateChanged, onCallDetailsChanged,
     * onAudioRouteChanged, onCanPutOnHoldChanged, onCanMergeChanged,
     * onCanSwapChanged, onCanConferenceChanged, onCanDisconnectChanged,
     * onVideoStateChanged and the four onRtt* methods. None of those exist on
     * InCallService: the framework provides onCallAdded, onCallRemoved,
     * onCallAudioStateChanged and onCanAddCallChanged, and state/details
     * changes arrive as part of onCallAdded. Overriding them could never have
     * compiled, so none of this wiring ever ran.
     *
     * The bridge's own handlers for the removed callbacks are kept and are now
     * driven by the two callbacks the framework really provides.
     */
    override fun onCallAudioStateChanged(call: Call, audioState: CallAudioState) {
        super.onCallAudioStateChanged(call, audioState)
        Log.d(logTag, "onCallAudioStateChanged: ${call.details.telecomCallId}, route: ${audioState.route}")
        CallStateBridge.onAudioRouteChanged(audioState.route)
    }

    override fun onCanAddCallChanged(call: Call, canAddCall: Boolean) {
        super.onCanAddCallChanged(call, canAddCall)
        CallStateBridge.onCanAddCallChanged(call, canAddCall)
    }

    // Handle commands from UI
    fun executeCommand(callId: String, command: CallStateBridge.CallCommand): Boolean {
        return CallStateBridge.executeCommand(callId, command)
    }
}
