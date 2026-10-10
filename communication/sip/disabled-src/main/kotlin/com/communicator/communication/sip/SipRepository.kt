package com.communicator.app

import android.content.Context
import com.communicator.communication.sip.SipTransport
import com.communicator.communication.sip.SipTransportImpl
import com.communicator.data.core.sip.SipAccountDao
import com.communicator.data.core.sip.SipCallDao
import com.communicator.data.core.sip.SipMessageDao
import com.communicator.data.core.sip.SipAccountEntity
import com.communicator.data.core.sip.SipCallEntity
import com.communicator.data.core.sip.SipMessageEntity
import com.communicator.data.core.sip.SipConverters
import com.communicator.data.core.sip.CallDirection
import com.communicator.data.core.sip.CallState
import com.communicator.data.core.sip.MessageDirection
import com.communicator.data.core.sip.DeliveryState
import com.communicator.data.security.SipCredentialManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class SipRepository(
    private val context: Context,
    private val accountDao: SipAccountDao,
    private val callDao: SipCallDao,
    private val messageDao: SipMessageDao,
    private val sipCredentialManager: SipCredentialManager
) {

    private val transports = mutableMapOf<Int, SipTransportImpl>()
    private val activeAccountId = mutableStateFlow<String?>(null)

    init {
        // Initialize transports for all active accounts
        loadAccounts()
    }

    private fun loadAccounts() {
        // In a real implementation, this would load from database
        // For now, we'll create a default transport
    }

    fun getTransports(): List<SipTransportImpl> {
        return ArrayList(transports.values)
    }

    fun getTransport(accountId: String): SipTransportImpl? {
        return transports[accountId.hashCode()]
    }

    fun getActiveTransport(): SipTransportImpl? {
        return activeAccountId.value?.let { transports[it.hashCode()] }
    }

    fun setActiveAccount(accountId: String) {
        if (transports.containsKey(accountId.hashCode())) {
            activeAccountId.value = accountId
        }
    }

    val activeAccount: kotlinx.coroutines.flow.StateFlow<String?> = activeAccountId

    suspend fun registerAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        port: Int = 5060,
        transportType: SipTransportType = SipTransportType.TLS,
        proxy: String? = null,
        outboundProxy: String? = null,
        authUsername: String? = null,
        enableSrtp: Boolean = true,
        enableZrtp: Boolean = false,
        enableDtlsSrtp: Boolean = true
    ): Result<Unit> {
        val transport = getActiveTransport()
        return if (transport != null) {
            val account = SipAccount(
                accountId = UUID.randomUUID().toString(),
                username = username,
                password = password,
                domain = domain,
                displayName = displayName,
                port = port,
                transportType = transportType,
                proxy = proxy,
                outboundProxy = outboundProxy,
                authUsername = authUsername,
                enableSrtp = enableSrtp,
                enableZrtp = enableZrtp,
                enableDtlsSrtp = enableDtlsSrtp
            )
            transport.registerAccount(account)
        } else {
            Result.failure(IllegalStateException("No active SIP transport"))
        }
    }

    suspend fun unregisterAccount(accountId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.unregisterAccount(accountId) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun sendMessage(
        accountId: String,
        destination: String,
        content: String,
        contentType: String = "text/plain"
    ): SipResult {
        val transport = getActiveTransport()
        return transport?.sendSipMessage(
            SipMessage(
                messageId = UUID.randomUUID().toString(),
                from = "",
                to = destination,
                content = content,
                contentType = contentType,
                accountId = accountId
            )
        ) ?: SipResult.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun makeCall(accountId: String, destination: String): Result<CallResult> {
        val transport = getActiveTransport()
        return transport?.makeCall(accountId, destination) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun makeVideoCall(accountId: String, destination: String): Result<CallResult> {
        val transport = getActiveTransport()
        return transport?.makeVideoCall(accountId, destination) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun answerCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.answerCall(callId) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun rejectCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.rejectCall(callId) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun endCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.endCall(callId) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun holdCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.holdCall(callId) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun resumeCall(callId: String): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.resumeCall(callId) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun muteCall(callId: String, mute: Boolean): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.muteCall(callId, mute) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    suspend fun sendDtmf(callId: String, digit: Char): Result<Unit> {
        val transport = getActiveTransport()
        return transport?.sendDtmf(callId, digit) ?: Result.failure(IllegalStateException("No active SIP transport"))
    }

    fun getAccounts(): Flow<List<SipAccount>> = kotlinx.coroutines.flow.flow {
        // In a real implementation, this would load from database
        emit(emptyList())
    }

    suspend fun getRegistrationState(accountId: String): SipRegistrationState {
        val transport = getActiveTransport()
        return transport?.getRegistrationState(accountId) ?: SipRegistrationState.UNKNOWN
    }

    suspend fun getCalls(): Flow<List<SipCallState>> {
        val transport = getActiveTransport()
        return transport?.observeCallState() ?: kotlinx.coroutines.flow.flow { }
    }

    fun getActiveCall(): SipCallState? {
        val transport = getActiveTransport()
        return transport?.getActiveCall()
    }
}
