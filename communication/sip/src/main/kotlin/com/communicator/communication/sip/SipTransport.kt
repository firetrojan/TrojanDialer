package com.communicator.communication.sip

import android.content.Context
import com.communicator.communication.core.CallResult
import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.model.TransportCapabilities
import com.communicator.data.core.sip.SipAccountEntity
import kotlinx.coroutines.flow.Flow
import org.pjsip.pjsua2.*

/**
 * SIP (Session Initiation Protocol) transport using PJSIP.
 * 
 * Uses PJSIP (pjsip.org) - a mature, actively maintained SIP stack.
 * License: GPLv2 with linking exception for linking with OpenSSL.
 * 
 * Supported operations:
 * - SIP REGISTER (registration)
 * - SIP INVITE (outbound calls)
 * - Incoming INVITE handling
 * - SIP BYE (hangup)
 * - SIP CANCEL
 * - SIP re-INVITE (hold/resume)
 * - SIP INFO (DTMF)
 * - SIP MESSAGE (instant messaging)
 * - SDP negotiation
 * - RTP/SRTP media
 * - STUN/ICE NAT traversal
 * - TLS/SRTP security
 * - Multiple accounts
 */
interface SipTransport : com.communicator.communication.core.CommunicationTransport {
    /** The SIP accounts managed by this transport. */
    val accounts: List<SipAccount>

    /** Registers a new SIP account. */
    suspend fun registerAccount(account: SipAccount): Result<Unit>

    /** Unregisters a SIP account. */
    suspend fun unregisterAccount(accountId: String): Result<Unit>

    /** Updates an existing SIP account. */
    suspend fun updateAccount(account: SipAccount): Result<Unit>

    /** Deletes a SIP account. */
    suspend fun deleteAccount(accountId: String): Result<Unit>

    /** Checks registration status for an account. */
    suspend fun getRegistrationState(accountId: String): RegistrationState

    /** Registers for push notifications for incoming calls. */
    suspend fun registerPushNotifications(accountId: String): Result<Unit>

    /** Unregisters push notifications. */
    suspend fun unregisterPushNotifications(accountId: String): Result<Unit>

    /** Checks if push notifications are enabled for an account. */
    suspend fun isPushRegistered(accountId: String): Boolean

    /** Makes an outbound SIP voice call. */
    suspend fun makeCall(accountId: String, destination: String): CommunicationTransport.CallResult

    /** Makes an outbound SIP video call. */
    suspend fun makeVideoCall(accountId: String, destination: String): CommunicationTransport.CallResult

    /** Answers an incoming call. */
    suspend fun answerCall(callId: String): Result<Unit>

    /** Rejects an incoming call. */
    suspend fun rejectCall(callId: String): Result<Unit>

    /** Ends an active call. */
    suspend fun endCall(callId: String): Result<Unit>

    /** Places a call on hold. */
    suspend fun holdCall(callId: String): Result<Unit>

    /** Resumes a held call. */
    suspend fun resumeCall(callId: String): Result<Unit>

    /** Toggles mute on a call. */
    suspend fun muteCall(callId: String, mute: Boolean): Result<Unit>

    /** Sends DTMF digit. */
    suspend fun sendDtmf(callId: String, digit: Char): Result<Unit>

    /** Sends a SIP MESSAGE. */
    suspend fun sendSipMessage(message: SipMessage): SipResult

    /** Observes incoming SIP messages. */
    fun observeMessages(): kotlinx.coroutines.flow.Flow<SipMessage>

    /** Observes call state changes. */
    fun observeCallState(): kotlinx.coroutines.flow.Flow<SipCallState>

    data class SipAccount(
        val accountId: String,
        val username: String,
        val password: String,
        val domain: String,
        val displayName: String? = null,
        val port: Int = 5060,
        val transportType: SipTransportType = SipTransportType.TLS,
        val proxy: String? = null,
        val outboundProxy: String? = null,
        val authUsername: String? = null,
        val enableSrtp: Boolean = true,
        val enableZrtp: Boolean = false,
        val enableDtlsSrtp: Boolean = true,
        val isDefault: Boolean = false,
        val enabled: Boolean = true,
        val metadata: Map<String, String> = emptyMap()
    )

    enum class SipTransportType {
        UDP,
        TCP,
        TLS
    }

    enum class RegistrationState {
        UNKNOWN,
        REGISTERING,
        REGISTERED,
        UNREGISTERED,
        FAILED,
        AUTHENTICATION_FAILED
    }

    data class SipMessage(
        val messageId: String,
        val from: String,
        val to: String,
        val content: String,
        val contentType: String = "text/plain",
        val accountId: String? = null
    )

    data class SipResult(
        val success: Boolean,
        val transactionId: String? = null,
        val error: Throwable? = null
    ) {
        companion object {
            fun success(transactionId: String): SipResult = SipResult(true, transactionId)
            fun failure(error: Throwable): SipResult = SipResult(false, error = error)
        }
    }
}

/**
 * Real-time call state for UI observation.
 */
data class SipCallState(
    val callId: String,
    val accountId: String,
    val remoteUri: String,
    val remoteDisplayName: String?,
    val direction: CallDirection,
    val state: CallState,
    val isVideo: Boolean,
    val isOnHold: Boolean,
    val isMuted: Boolean,
    val isSpeaker: Boolean,
    val startTime: Long,
    val duration: Long
)

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallState {
    INITIATING,
    CONNECTING,
    RINGING,
    ACTIVE,
    ON_HOLD,
    ENDED,
    MISSED,
    REJECTED,
    FAILED
}

/**
 * Capability set for SIP transport.
 */
val SipCapabilities = TransportCapabilities(
    setOf(
        com.communicator.data.core.model.Capability.VOICE,
        com.communicator.data.core.model.Capability.VIDEO,
        com.communicator.data.core.model.Capability.MESSAGING,
        com.communicator.data.core.model.Capability.INTERNET,
        com.communicator.data.core.model.Capability.CALL_HOLD,
        com.communicator.data.core.model.Capability.CALL_WAITING,
        // Conference and Transfer are implemented but limited - PARTIAL
        // com.communicator.data.core.model.Capability.CONFERENCE,
        // com.communicator.data.core.model.Capability.CALL_TRANSFER,
        com.communicator.data.core.model.Capability.END_TO_END_ENCRYPTION
    )
)
