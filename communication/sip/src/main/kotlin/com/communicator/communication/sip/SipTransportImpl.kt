package com.communicator.communication.sip

import android.content.Context
import android.util.Log
import com.communicator.communication.core.CallResult
import com.communicator.communication.core.CommunicationTransport
import com.communicator.data.core.sip.SipAccountEntity
import com.communicator.data.security.SipCredentialManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.pjsip.pjsua2.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

class SipTransportImpl(
    private val context: Context,
    private val sipCredentialManager: SipCredentialManager
) : SipTransport {

    private val logTag = "SipTransportImpl"
    private val endpoint = Endpoint()
    private val accountMap = mutableMapOf<String, Account>()
    private val callMap = mutableMapOf<String, Call>()
    private val accountStateMap = mutableMapOf<String, RegistrationState>()

    private val _accounts = MutableStateFlow<List<SipAccount>>(emptyList())
    override val accounts: List<SipAccount> = _accounts.value
        get() = _accounts.value

    private val _callStates = MutableStateFlow<Map<String, SipCallState>>(emptyMap())
    override fun observeCallState(): Flow<SipCallState> = _callStates.asFlow()

    private val _messages = MutableStateFlow<List<SipMessage>>(emptyList())
    override fun observeMessages(): Flow<SipMessage> = _messages.asFlow()

    init {
        initializePjsip()
    }

    private fun initializePjsip() {
        try {
            // Create endpoint
            val epConfig = EpConfig()
            endpoint.libCreate()

            // Configure logging
            val logConfig = LogConfig()
            logConfig.level = 4
            logConfig.consoleLevel = 4
            endpoint.libSetLogConfig(logConfig)

            // Initialize with default config
            val uaConfig = UaConfig()
            uaConfig.userAgent = "TrojanDialer/1.0"
            endpoint.libInit(uaConfig)

            // Create transport configs
            val sipTransportConfig = TransportConfig()
            sipTransportConfig.port = 5060
            sipTransportConfig.type = PJSIP_TRANSPORT_UDP
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, sipTransportConfig)

            // TLS transport
            val tlsConfig = TransportConfig()
            tlsConfig.port = 5061
            tlsConfig.type = PJSIP_TRANSPORT_TLS
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsConfig)

            // TCP transport
            val tcpConfig = TransportConfig()
            tcpConfig.port = 5060
            tcpConfig.type = PJSIP_TRANSPORT_TCP
            endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TCP, tcpConfig)

            // Start the library
            endpoint.libStart()

            // Set up call callback
            endpoint.setCallback(object : CallCallback() {
                override fun onIncomingCall(call: Call, prm: OnIncomingCallParam) {
                    handleIncomingCall(call, prm)
                }

                override fun onCallState(call: Call, prm: OnCallStateParam) {
                    updateCallState(call)
                }

                override fun onCallMediaState(call: Call, prm: OnCallMediaStateParam) {
                    // Handle media state changes
                }

                override fun onDtmfDigit(call: Call, prm: OnDtmfDigitParam) {
                    // Handle DTMF
                }
            })

            // Set up registration callback
            endpoint.setCallback(object : RegCallback() {
                override fun onRegState(prm: OnRegStateParam) {
                    // Registration state handled per-account
                }
            })

            Log.d("SipTransportImpl", "PJSIP initialized successfully")
        } catch (e: Exception) {
            Log.e("SipTransportImpl", "Failed to initialize PJSIP", e)
        }
    }

    override val accounts: List<SipAccount>
        get() = _accounts.value

    private val _accounts = MutableStateFlow<List<SipAccount>>(emptyList())

    private val _registrationStates = MutableStateFlow<Map<String, RegistrationState>>(emptyMap())
    private val _callStates = MutableStateFlow<Map<String, SipCallState>>(emptyMap())

    override fun observeCallState(): Flow<SipCallState> = _callStates.asFlow().flatMapLatest { states ->
        kotlinx.coroutines.flow.flow { states.values.toCollection() }
    }

    override fun observeMessages(): Flow<SipMessage> = _messages.asFlow()

    private val _messages = MutableStateFlow<List<SipMessage>>(emptyList())

    override suspend fun registerAccount(account: SipAccount): Result<Unit> {
        return try {
            val decryptedPassword = sipCredentialManager.decryptPassword(account.password)
            val accountConfig = createAccountConfig(account, decryptedPassword)

            val acc = Account()
            acc.create(accountConfig)

            // Register
            acc.setRegistration(true)

            accountMap[account.accountId] = acc
            updateAccountList()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unregisterAccount(accountId: String): Result<Unit> {
        return try {
            val account = accountMap[accountId] ?: return Result.failure(IllegalArgumentException("Account not found"))
            account.setRegistration(false)
            account.delete()
            accountMap.remove(accountId)
            updateAccountList()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateAccount(account: SipAccount): Result<Unit> {
        return try {
            val existingAccount = accountMap[account.accountId] ?: return Result.failure(IllegalArgumentException("Account not found"))
            val decryptedPassword = sipCredentialManager.decryptPassword(account.password)
            val accountConfig = createAccountConfig(account, decryptedPassword)
            existingAccount.edit(accountConfig)
            updateAccountList()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAccount(accountId: String): Result<Unit> {
        return unregisterAccount(accountId)
    }

    override suspend fun getRegistrationState(accountId: String): RegistrationState {
        val account = accountMap[accountId] ?: return RegistrationState.UNKNOWN
        try {
            val info = account.getInfo()
            return when (info.regStatus / 100) {
                2 -> RegistrationState.REGISTERED
                1 -> RegistrationState.REGISTERING
                4, 5, 6 -> RegistrationState.FAILED
                else -> RegistrationState.UNREGISTERED
            }
        } catch (e: Exception) {
            RegistrationState.UNKNOWN
        }
    }

    override suspend fun registerPushNotifications(accountId: String): Result<Unit> {
        return try {
            val account = accountMap[accountId] ?: return Result.failure(IllegalArgumentException("Account not found"))
            // Register for FCM push notifications
            // This would typically involve:
            // 1. Get FCM token from Firebase
            // 2. Send token to SIP server via SIP PUBLISH or custom method
            // 3. Server stores token for push notifications
            
            // For now, log that push registration is requested
            Log.d("SipTransportImpl", "Push notification registration requested for account: $accountId")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unregisterPushNotifications(accountId: String): Result<Unit> {
        return try {
            // Unregister push notifications
            Log.d("SipTransportImpl", "Push notification unregistration requested for account: $accountId")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isPushRegistered(accountId: String): Boolean {
        // Check if push notifications are registered for this account
        // This would typically check a local preference or server-side state
        return false
    }

    override suspend fun makeCall(accountId: String, destination: String): CommunicationTransport.CallResult {
        return makeCallInternal(accountId, destination, false)
    }

    override suspend fun makeVideoCall(accountId: String, destination: String): CommunicationTransport.CallResult {
        return makeCallInternal(accountId, destination, true)
    }

    override suspend fun holdCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.hold(callParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resumeCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.reinvite(callParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun makeCallInternal(accountId: String, destination: String, isVideo: Boolean): CommunicationTransport.CallResult {
        return try {
            val account = accountMap[accountId] ?: return CallResult.failure(IllegalArgumentException("Account not found"))
            val call = Call()
            val callParam = CallOpParam(true)
            
            if (isVideo) {
                // Add video media to call
                callParam.vidIn = true
                callParam.vidOut = true
            }
            
            account.makeCall(destination, callParam)
            
            val callId = UUID.randomUUID().toString()
            val callState = SipCallState(
                callId = callId,
                accountId = accountId,
                remoteUri = destination,
                remoteDisplayName = null,
                direction = CallDirection.OUTGOING,
                state = CallState.CONNECTING,
                isVideo = isVideo,
                isOnHold = false,
                isMuted = false,
                isSpeaker = false,
                startTime = System.currentTimeMillis(),
                duration = 0
            )
            _callStates.update { it + (callId to callState) }
            
            CallResult.success(callId)
        } catch (e: Exception) {
            CallResult.failure(e)
        }
    }

    override suspend fun answerCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.answer(callParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rejectCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.hangup(callParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun endCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.hangup(callParam)
            callMap.remove(callId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun holdCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.hold(callParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resumeCall(callId: String): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callParam = CallOpParam(true)
            call.reinvite(callParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun muteCall(callId: String, mute: Boolean): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val callMediaInfoVector = call.getCallInfo().media
            // Mute logic would go here
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendDtmf(callId: String, digit: Char): Result<Unit> {
        return try {
            val call = callMap[callId] ?: return Result.failure(IllegalArgumentException("Call not found"))
            val dtmfParam = DtmfInfo()
            dtmfParam.digit = digit.toInt()
            call.sendDtmf(dtmfParam)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendSipMessage(message: SipMessage): SipResult {
        return try {
            val account = accountMap[message.accountId ?: ""] ?: return SipResult.failure(IllegalArgumentException("Account not found"))
            val msgData = MsgData()
            msgData.content = message.content
            msgData.contentType = message.contentType
            account.sendMessage(message.to, msgData)
            SipResult.success(UUID.randomUUID().toString())
        } catch (e: Exception) {
            SipResult.failure(e)
        }
    }

    override fun observeMessages(): Flow<SipMessage> = _messages.asFlow()

    private fun createAccountConfig(account: SipAccount, password: String): AccountConfig {
        val cfg = AccountConfig()
        cfg.idUri = "sip:${account.username}@${account.domain}"
        cfg.regConfig.registrarUri = "sip:${account.proxy ?: account.domain}"
        cfg.regConfig.timeoutSec = 300
        cfg.sipConfig.authCreds.add(AuthCredInfo("digest", "*", account.authUsername ?: account.username, 0, password))
        
        // Transport
        when (account.transportType) {
            SipTransportType.TCP -> cfg.sipConfig.transport = pjsip_transport_type_e.PJSIP_TRANSPORT_TCP
            SipTransportType.TLS -> cfg.sipConfig.transport = pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
            else -> cfg.sipConfig.transport = pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
        }
        
        // Proxy
        account.proxy?.let {
            cfg.regConfig.proxy.add(it)
        }
        
        // Outbound proxy
        account.outboundProxy?.let {
            cfg.sipConfig.outboundProxy.add(it)
        }
        
        // Media
        cfg.mediaConfig.enableIce = true
        cfg.mediaConfig.rtpPort = 4000
        cfg.mediaConfig.rtpPortCount = 100
        
        // SRTP
        if (account.enableSrtp) {
            cfg.mediaConfig.srtpUse = PJMEDIA_SRTP_OPTIONAL
        }
        
        return cfg
    }

    private fun updateAccountList() {
        _accounts.value = accountMap.values.map { it.toSipAccount() }
    }

    private fun handleIncomingCall(call: Call, prm: OnIncomingCallParam) {
        val callId = UUID.randomUUID().toString()
        val callInfo = call.getInfo()
        
        val callState = SipCallState(
            callId = callId,
            accountId = "", // Would need to find matching account
            remoteUri = callInfo.remoteUri,
            remoteDisplayName = null,
            direction = CallDirection.INCOMING,
            state = CallState.RINGING,
            isVideo = false,
            isOnHold = false,
            isMuted = false,
            isSpeaker = false,
            startTime = System.currentTimeMillis(),
            duration = 0
        )
        
        callMap[callId] = call
        _callStates.update { it + (callId to callState) }
    }

    private fun updateCallState(call: Call) {
        val callInfo = call.getInfo()
        val callId = findCallIdByCall(call)
        if (callId != null) {
            _callStates.update { states ->
                val existing = states[callId]
                if (existing != null) {
                    val newState = when (callInfo.state) {
                        PJSIP_INV_STATE_EARLY -> CallState.RINGING
                        PJSIP_INV_STATE_CONNECTING -> CallState.CONNECTING
                        PJSIP_INV_STATE_CONFIRMED -> CallState.ACTIVE
                        PJSIP_INV_STATE_DISCONNECTED -> CallState.ENDED
                        else -> CallState.INITIATING
                    }
                    states + (callId to existing.copy(
                        state = newState,
                        duration = if (newState == CallState.ACTIVE) System.currentTimeMillis() - existing.startTime else existing.duration
                    ))
                } else {
                    states
                }
            }
        }
    }

    private fun findCallIdByCall(call: Call): String? {
        return callMap.entries.firstOrNull { it.value === call }?.key
    }

    companion object {
        private const val logTag = "SipTransportImpl"
    }
}
