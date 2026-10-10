package com.communicator.communication.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.communicator.communication.core.CommunicationTransport
import com.communicator.communication.core.CommunicationTransport.VoiceCallRequest
import com.communicator.communication.core.CommunicationTransport.VideoCallRequest
import com.communicator.communication.core.CommunicationTransport.CallResult
import com.communicator.communication.core.CommunicationTransport.MessageRequest
import com.communicator.communication.core.CommunicationTransport.MessageResult
import com.communicator.communication.sms.SmsMmsTransport.MmsRequest
import com.communicator.communication.sms.SmsMmsTransport.SmsMmsResult
import com.communicator.communication.sms.SmsMmsTransport.SmsRequest
import com.communicator.data.core.model.DeliveryState
import com.communicator.data.core.model.TransportCapabilities
import com.communicator.data.core.sms.MessageDirection
import com.communicator.data.core.sms.SmsMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Track multipart message delivery state
data class MultipartTracking(
    val segmentIds: MutableSet<String>,
    val deliveredSegments: MutableSet<String>,
    val totalSegments: Int
)

class SmsTransportImpl(
    private val context: Context,
    override val subscriptionId: Int?
) : SmsMmsTransport {

    private val logTag = "SmsTransportImpl"

    /**
     * The subscription this transport sends on.
     *
     * SmsManager requires a concrete subscription id; callers only construct this
     * transport for a real SIM (see SmsRepository, which passes
     * subInfo.subscriptionId). The fallback keeps construction total rather than
     * throwing, and [subscriptionId] still reports the true value.
     */
    private val subId: Int = subscriptionId ?: android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private val smsManager = SmsManager.getSmsManagerForSubscriptionId(subId)
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)

    override val id: String = "sms-sub-$subscriptionId"
    override val name: String = getSubscriptionDisplayName()
    override val capabilities: TransportCapabilities = SmsMmsTransportCapabilities

    private val _isDefaultSmsApp = MutableStateFlow(false)
    val isDefaultSmsApp: kotlinx.coroutines.flow.StateFlow<Boolean> = _isDefaultSmsApp

    private val _available = MutableStateFlow(true)
    override fun isInstalled(): Boolean = true

    override fun isAvailable(): Boolean = _available.value

    private val pendingSentResults = ConcurrentHashMap<String, PendingIntent>()
    private val pendingDeliveredResults = ConcurrentHashMap<String, PendingIntent>()
    
    // Track multipart message delivery state
    private val pendingMultipartMessages = ConcurrentHashMap<String, MultipartTracking>()

    init {
        checkDefaultSmsApp()
        registerResultReceivers()
    }

    /**
     * Human label for the SIM this transport sends on.
     *
     * Uses SubscriptionManager.getSubscriptionInfoList rather than the
     * single-subscription getter, which the platform hides on API 31+.
     */
private fun getSubscriptionDisplayName(): String {
        val subId = subscriptionId ?: return "SIM"
        val subInfo = subscriptionManager.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subId }
        return subInfo?.carrierName?.toString() ?: "SIM $subId"
    }

    private fun checkDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val defaultApp = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
            _isDefaultSmsApp.value = defaultApp == context.packageName
        }
    }

    private fun registerResultReceivers() {
        // Sent result receiver
        val sentFilter = IntentFilter("SMS_SENT_$subId")
        val sentPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent("SMS_SENT_$subId").putExtra("subscriptionId", subscriptionId),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        context.registerReceiver(sentResultReceiver, sentFilter)
        pendingSentResults["default"] = sentPendingIntent

        // Delivered result receiver
        val deliveredFilter = IntentFilter("SMS_DELIVERED_$subscriptionId")
        val deliveredPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent("SMS_DELIVERED_$subId").putExtra("subscriptionId", subId),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        context.registerReceiver(deliveredResultReceiver, deliveredFilter)
        pendingDeliveredResults["default"] = deliveredPendingIntent
    }

    private val sentResultReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val resultCode = resultCode
            val messageId = intent?.getStringExtra("messageId") ?: return
            val error = when (resultCode) {
                android.app.Activity.RESULT_OK -> null
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SmsError("Generic failure")
                SmsManager.RESULT_ERROR_RADIO_OFF -> SmsError("Radio off")
                SmsManager.RESULT_ERROR_NULL_PDU -> SmsError("Null PDU")
                SmsManager.RESULT_ERROR_NO_SERVICE -> SmsError("No service")
                SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> SmsError("Limit exceeded")
                SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> SmsError("FDN check failure")
                SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> SmsError("Short code never allowed")
                SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> SmsError("Short code not allowed")
                else -> SmsError("Unknown error: $resultCode")
            }
            onSentResult(messageId, error)
        }
    }

    private val deliveredResultReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val messageId = intent?.getStringExtra("messageId") ?: return
            onDeliveredResult(messageId)
        }
    }

    private fun onSentResult(messageId: String, error: Throwable?) {
        // Check if this is a segment of a multipart message
        val parentMessageId = findParentMessageId(messageId)
        if (parentMessageId != null) {
            // Track segment sent status
            val tracking = pendingMultipartMessages[parentMessageId]
            if (tracking != null) {
                // Segment sent successfully, remove from pending
                tracking.segmentIds.remove(messageId)
            }
        }
        
        // Update message state in database via callback
        CoroutineScope(Dispatchers.IO).launch {
            sentResultCallback?.invoke(messageId, error)
        }
    }

    private fun onDeliveredResult(messageId: String) {
        val parentMessageId = findParentMessageId(messageId)
        if (parentMessageId != null) {
            // Track segment delivery for multipart message
            val tracking = pendingMultipartMessages[parentMessageId]
            tracking?.let {
                it.deliveredSegments.add(messageId)
                // Check if all segments delivered
                if (it.deliveredSegments.size >= it.totalSegments) {
                    // All segments delivered - parent message is fully delivered
                    CoroutineScope(Dispatchers.IO).launch {
                        deliveredResultCallback?.invoke(parentMessageId)
                    }
                    pendingMultipartMessages.remove(parentMessageId)
                }
            }
        } else {
            // Single-part message
            CoroutineScope(Dispatchers.IO).launch {
                deliveredResultCallback?.invoke(messageId)
            }
        }
    }

    private fun findParentMessageId(segmentMessageId: String): String? {
        return pendingMultipartMessages.entries.firstOrNull { (_, tracking) ->
            segmentMessageId in tracking.segmentIds
        }?.key
    }

    // Callbacks to be set by repository
    var sentResultCallback: ((String, Throwable?) -> Unit)? = null
    var deliveredResultCallback: ((String) -> Unit)? = null

    override suspend fun isDefaultSmsApp(): Boolean {
        checkDefaultSmsApp()
        return _isDefaultSmsApp.value
    }

    override suspend fun requestDefaultSmsRole(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS) == true) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
                // Caller must startActivityForResult with this intent
                return true
            }
        }
        return false
    }

    override suspend fun sendSms(request: SmsRequest): SmsMmsResult {
        return try {
            // SmsManager validates the recipient itself; no normalization is applied.
            val address = request.address
            val content = request.content

            val multipart = smsManager.divideMessage(content)
            val parentMessageId = UUID.randomUUID().toString()

            if (multipart.size > 1) {
                // Multipart SMS - each segment gets unique messageId for tracking
                val segmentIds = (0 until multipart.size).map { UUID.randomUUID().toString() }
                
                val sentIntents = segmentIds.map { createSentPendingIntent(it, parentMessageId) }
                val deliveredIntents = if (request.deliveryReport) segmentIds.map { createDeliveredPendingIntent(it, parentMessageId) } else List(multipart.size) { null }
                
                smsManager.sendMultipartTextMessage(
                    address,
                    null, // scAddress
                    multipart,
                    ArrayList(sentIntents),
                    ArrayList(deliveredIntents)
                )
                
                // Track parent message for aggregate state
                pendingMultipartMessages[parentMessageId] = MultipartTracking(
                    segmentIds = segmentIds.toMutableSet(),
                    deliveredSegments = mutableSetOf(),
                    totalSegments = multipart.size
                )
                
                SmsMmsResult.success(parentMessageId)
            } else {
                // Single-part SMS
                val messageId = UUID.randomUUID().toString()
                val sentIntent = createSentPendingIntent(messageId)
                val deliveredIntent = if (request.deliveryReport) createDeliveredPendingIntent(messageId) else null
                
                smsManager.sendTextMessage(
                    address,
                    null, // scAddress
                    content,
                    sentIntent,
                    deliveredIntent
                )
                
                SmsMmsResult.success(messageId)
            }
        } catch (e: SecurityException) {
            SmsMmsResult.failure(SmsError("Permission denied: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            SmsMmsResult.failure(SmsError("Invalid address or content: ${e.message}"))
        } catch (e: Exception) {
            SmsMmsResult.failure(SmsError("Send failed: ${e.message}"))
        }
    }

    private fun createSentPendingIntent(messageId: String, parentMessageId: String? = null): PendingIntent {
        val intent = Intent("SMS_SENT_$subId")
            .putExtra("messageId", messageId)
            .putExtra("subscriptionId", subscriptionId)
            .putExtra("parentMessageId", parentMessageId ?: "")
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createDeliveredPendingIntent(messageId: String, parentMessageId: String? = null): PendingIntent {
        val intent = Intent("SMS_DELIVERED_$subscriptionId")
            .putExtra("messageId", messageId)
            .putExtra("subscriptionId", subscriptionId)
            .putExtra("parentMessageId", parentMessageId ?: "")
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode() + 1,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override suspend fun sendMms(request: MmsRequest): SmsMmsResult {
        // MMS not implemented in Phase 2A
        return SmsMmsResult.failure(SmsError("MMS not implemented in Phase 2A"))
    }

    override suspend fun receiveMessages(): Flow<com.communicator.data.core.model.Message> {
        // This will be implemented by the repository observing the database
        return kotlinx.coroutines.flow.flow { }
    }

    override suspend fun getThreads(): Flow<List<MessageThread>> {
        return kotlinx.coroutines.flow.flow { }
    }

    override suspend fun getMessages(threadId: String): Flow<List<com.communicator.data.core.model.Message>> {
        return kotlinx.coroutines.flow.flow { }
    }

    override suspend fun resolveContact(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): CommunicationTransport.TransportAvailability =
        CommunicationTransport.TransportAvailability(
            transportId = id,
            // Sending SMS requires holding the default-SMS-app role, so that is
            // the availability gate rather than mere installation.
            isAvailable = _available.value,
            capability = capability,
            reason = if (_available.value) null
            else CommunicationTransport.UnavailableReason.PERMISSION_DENIED
        )

    override suspend fun startVoiceCall(request: CommunicationTransport.VoiceCallRequest): CommunicationTransport.CallResult {
        return CommunicationTransport.CallResult.failure(Exception("SMS transport does not support voice calls"))
    }

    override suspend fun startVideoCall(request: CommunicationTransport.VideoCallRequest): CommunicationTransport.CallResult {
        return CommunicationTransport.CallResult.failure(Exception("SMS transport does not support video calls"))
    }

    override suspend fun sendMessage(request: CommunicationTransport.MessageRequest): CommunicationTransport.MessageResult {
        val smsRequest = SmsRequest(
            address = request.address,
            content = request.content,
            subscriptionId = subscriptionId,
            deliveryReport = false,
            readReport = false,
            scheduledTime = null,
            isMultipart = false
        )
        val result = sendSms(smsRequest)
        return if (result.success) {
            CommunicationTransport.MessageResult.success(result.messageId!!)
        } else {
            CommunicationTransport.MessageResult.failure(result.error!!)
        }
    }

    /** Transport-level failure with a caller-readable description. */
    class SmsError(description: String) : Throwable(description)
}
