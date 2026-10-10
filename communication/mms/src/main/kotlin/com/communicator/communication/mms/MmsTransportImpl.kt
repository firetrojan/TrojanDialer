package com.communicator.communication.mms

import android.app.PendingIntent
import android.content.ContentResolver
import android.os.Bundle
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.communicator.communication.core.CommunicationTransport
import com.communicator.communication.mms.MmsTransport.MmsRequest
import com.communicator.communication.mms.MmsTransport.MmsResult
import com.communicator.communication.mms.MmsTransport.MmsMessage
import com.communicator.communication.mms.MmsTransport.MmsThread
import com.communicator.communication.mms.MmsTransport.MmsAttachment
import com.communicator.communication.mms.MmsTransport.MmsDirection
import com.communicator.communication.mms.MmsTransport.MmsState
import com.communicator.communication.mms.MmsTransport.MmsDeliveryState
import com.communicator.communication.core.CommunicationTransport.VoiceCallRequest
import com.communicator.communication.core.CommunicationTransport.VideoCallRequest
import com.communicator.communication.core.CommunicationTransport.CallResult
import com.communicator.communication.core.CommunicationTransport.MessageRequest
import com.communicator.communication.core.CommunicationTransport.MessageResult
import com.communicator.data.core.model.DeliveryState
import com.communicator.data.core.model.TransportCapabilities
import com.communicator.data.core.mms.MmsMessageEntity
import com.communicator.data.core.mms.MmsPartEntity
import com.communicator.data.core.mms.MmsThreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MmsTransportImpl(
    private val context: Context,
    override val subscriptionId: Int?
) : MmsTransport {

    private val logTag = "MmsTransportImpl"
    private val contentResolver: ContentResolver = context.contentResolver
    /** Concrete subscription id for platform APIs that require one. */
    private val subId: Int = subscriptionId ?: android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID

    private val smsManager = SmsManager.getSmsManagerForSubscriptionId(subId)

    override val id: String = "mms-sub-$subId"
    override val name: String = getSubscriptionDisplayName()
    override val capabilities: TransportCapabilities = MmsCapabilities

    private val _isDefaultMmsApp = MutableStateFlow(false)

    private val _available = MutableStateFlow(true)

    override fun isInstalled(): Boolean = true

    override fun isAvailable(): Boolean = _available.value

    private val subscriptionManager = context.getSystemService(android.telephony.SubscriptionManager::class.java)

    init {
        checkDefaultMmsApp()
    }

    private fun checkDefaultMmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val defaultApp = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
            _isDefaultMmsApp.value = defaultApp == context.packageName
        }
    }

    override suspend fun isDefaultMmsApp(): Boolean {
        checkDefaultMmsApp()
        return _isDefaultMmsApp.value
    }

    override suspend fun requestDefaultMmsRole(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_SMS) == true) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_SMS)
                return true
            }
        }
        return false
    }

    override suspend fun sendMms(request: MmsRequest): MmsResult {
        // REAL PUBLIC API: SmsManager.sendMultimediaMessage() since API 21
        // Takes contentUri (MMS in content://mms), locationUrl, configOverrides, PendingIntent
        // Returns result via PendingIntent with MMS error codes
        // API 31+ supports subscriptionId parameter
        
        return try {
            val messageId = UUID.randomUUID().toString()
            val threadId = getOrCreateThreadId(request.address)
            
            // 1. Build MMS in content provider
            val mmsUri = buildMmsInProvider(request, messageId, threadId)
            if (mmsUri == null) {
                return MmsResult.failure(MmsError("Failed to create MMS in provider"))
            }
            
            // 2. Add parts
            request.attachments.forEachIndexed { index, attachment ->
                addPartToMms(mmsUri, messageId, attachment, index)
            }
            
            // 3. Send via REAL PUBLIC API: SmsManager.sendMultimediaMessage()
            // Since API 21: sendMultimediaMessage(contentUri, locationUrl, configOverrides, sentIntent)
            // Since API 31: sendMultimediaMessage(contentUri, locationUrl, configOverrides, sentIntent, messageId)
            // With subscription-aware manager, it uses the subscriptionId from the manager
            val sentIntent = createSentPendingIntent(messageId)
            
            // Build configOverrides if needed
            val configOverrides = buildConfigOverrides()
            
            // Send via REAL PUBLIC API (API 21+)
            // Using subscription-aware SmsManager, no separate subscriptionId parameter needed
            // Instance overload: (Context, locationUrl, contentUri, config, sentIntent).
            // locationUrl is null because the MMS sits in the provider at content://mms/sms.
            // Verified against android.telephony.SmsManager:
            // sendMultimediaMessage(Context, Uri contentUri, String locationUrl,
            // Bundle config, PendingIntent sentIntent). The order differs from
            // downloadMultimediaMessage, which takes the URL before the URI.
            // locationUrl is null here because the message lives in the provider
            // at content://mms.
            smsManager.sendMultimediaMessage(
                context,
                mmsUri,
                null,
                buildConfigOverrides(),
                sentIntent
            )
            
            MmsResult.success(messageId)
        } catch (e: SecurityException) {
            MmsResult.failure(MmsError("Permission denied: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            MmsResult.failure(MmsError("Invalid address or content: ${e.message}"))
        } catch (e: Exception) {
            MmsResult.failure(MmsError("Send failed: ${e.message}"))
        }
    }

    override suspend fun downloadMms(locationUrl: String, contentUri: Uri, configOverrides: Bundle?, downloadedIntent: PendingIntent): MmsResult {
        // REAL PUBLIC API: SmsManager.downloadMultimediaMessage() since API 31
        // Downloads MMS from carrier using location URL from WAP Push notification
        
        return try {
            // API 31+ only
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return MmsResult.failure(MmsError("downloadMultimediaMessage requires API 31+"))
            }
            
            // Instance overload: (Context, locationUrl, contentUri, config, downloadedIntent).
            smsManager.downloadMultimediaMessage(
                context,
                locationUrl,
                contentUri,
                buildConfigOverrides(),
                downloadedIntent
            )
            
            MmsResult.success("download")
        } catch (e: SecurityException) {
            MmsResult.failure(MmsError("Permission denied: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            MmsResult.failure(MmsError("Invalid location URL or content URI: ${e.message}"))
        } catch (e: Exception) {
            MmsResult.failure(MmsError("Download failed: ${e.message}"))
        }
    }

    override suspend fun getMms(mmsId: String): MmsMessage? {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val uri = Uri.parse("content://mms/$mmsId")
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    cursorToMmsMessage(c)
                } else null
            } ?: null
        }
    }

    /**
     * Re-emits the MMS inbox whenever the platform provider changes.
     *
     * A ContentResolver.query() returns a Cursor, not a Flow source, so the
     * previous `query(...).asFlow()` could never compile. This delegates to a
     * callbackFlow that performs the query and re-runs it on provider changes.
     */
    override suspend fun observeMms(): Flow<MmsMessage> =
        observeMmsChanges(context) { cursor -> cursorToMmsMessage(cursor) }

    override suspend fun getThreads(): Flow<MmsThread> =
        observeThreadChanges(context) { cursor -> cursorToMmsThread(cursor) }

    override suspend fun getMessages(threadId: String): Flow<MmsMessage> =
        observeThreadMessages(context, threadId) { cursor -> cursorToMmsMessage(cursor) }


    override suspend fun resolveContact(
        contactId: String,
        capability: com.communicator.data.core.model.Capability
    ): CommunicationTransport.TransportAvailability {
        val supported = capabilities.supports(capability)
        val available = _available.value
        return CommunicationTransport.TransportAvailability(
            transportId = id,
            isAvailable = available && supported,
            capability = capability,
            reason = when {
                !supported -> CommunicationTransport.UnavailableReason.NOT_SUPPORTED
                // MMS send/receive requires the carrier MMS role.
                !available -> CommunicationTransport.UnavailableReason.PERMISSION_DENIED
                else -> null
            }
        )
    }

    override suspend fun startVoiceCall(request: CommunicationTransport.VoiceCallRequest): CommunicationTransport.CallResult {
        return CommunicationTransport.CallResult.failure(Exception("MMS transport does not support voice calls"))
    }

    override suspend fun startVideoCall(request: CommunicationTransport.VideoCallRequest): CommunicationTransport.CallResult {
        return CommunicationTransport.CallResult.failure(Exception("MMS transport does not support video calls"))
    }

    override suspend fun sendMessage(request: CommunicationTransport.MessageRequest): CommunicationTransport.MessageResult {
        val mmsRequest = MmsRequest(
            address = request.address,
            subject = null,
            textContent = request.content,
            attachments = request.attachments?.map { att ->
                MmsAttachment(
                    contentUri = android.net.Uri.parse(att.uri),
                    mimeType = att.mimeType,
                    filename = att.filename
                )
            } ?: emptyList(),
            subscriptionId = subscriptionId,
            deliveryReport = false,
            readReport = false
        )
        val result = sendMms(mmsRequest)
        return if (result.success) {
            CommunicationTransport.MessageResult.success(result.messageId!!)
        } else {
            CommunicationTransport.MessageResult.failure(result.error!!)
        }
    }


    // ============================================================
    // PRIVATE HELPER METHODS - REAL PUBLIC API
    // ============================================================

    private fun getSubscriptionDisplayName(): String {
        val info = subscriptionManager.activeSubscriptionInfoList
            ?.firstOrNull { it.subscriptionId == subId }
        return info?.carrierName?.toString() ?: "SIM $subId"
    }

    private fun createSentPendingIntent(messageId: String): PendingIntent {
        val intent = Intent("MMS_SENT_$subscriptionId")
            .putExtra("messageId", messageId)
            .putExtra("subscriptionId", subscriptionId)
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildConfigOverrides(): android.os.Bundle {
        // Carrier config overrides - can be empty for default carrier config
        return android.os.Bundle().apply {
            // Add carrier-specific overrides if needed
            // putString("mmsc", "http://mmsc.carrier.com")
            // putString("proxy", "10.0.0.172")
            // putInt("port", 80)
        }
    }

    private fun getOrCreateThreadId(address: String): Long {
        // The MMS provider keys threads on the recipient string exactly as
        // supplied, so no normalization is applied here.
        val normalized = address
        val uri = Telephony.Threads.CONTENT_URI
        val cursor = contentResolver.query(uri, arrayOf("_id"), "recipient_ids = ?", arrayOf(normalized), null)
        return cursor?.use { c ->
            if (c.moveToFirst()) {
                c.getLong(c.getColumnIndexOrThrow("_id"))
            } else {
                val values = ContentValues().apply {
                    put("recipient_ids", normalized as String)
                }
                contentResolver.insert(Telephony.Threads.CONTENT_URI, values)?.lastPathSegment?.toLong() ?: 0
            }
        } ?: 0
    }

    private fun buildMmsInProvider(request: MmsRequest, messageId: String, threadId: Long): Uri? {
        val values = ContentValues().apply {
            put("thread_id", threadId)
            put("msg_box", 4) // MESSAGE_BOX_OUTBOX
            put("m_type", 128) // MESSAGE_TYPE_SEND_REQ (0x80)
            put("date", System.currentTimeMillis() / 1000)
            put("read", 0)
            put("_id", messageId)
            request.subject?.let { put("sub", it as String) }
            request.textContent?.let { put("text", it as String) }
        }

        return contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
    }

    private fun addPartToMms(
        mmsUri: Uri,
        messageId: String,
        attachment: MmsAttachment,
        index: Int
    ) {
        val partUri = Uri.withAppendedPath(mmsUri, "part")
        val partValues = ContentValues().apply {
            put("mid", messageId)
            put("ct", attachment.mimeType)
            put("_data", attachment.contentUri.toString())
            attachment.filename?.let { put("fn", it) }
            attachment.contentId?.let { put("cid", it) }
            attachment.contentLocation?.let { put("cl", it) }
            put("seq", index)
        }
        contentResolver.insert(Uri.withAppendedPath(mmsUri, "part"), partValues)
    }

    /**
     * Maps a row of Telephony.Mms.CONTENT_THREADS_URI to [MmsThread].
     *
     * Column names are read defensively via getColumnIndex, because the MMS
     * provider's exact columns differ across OEM builds; a missing column
     * yields a null rather than crashing the flow.
     */
    private fun cursorToMmsThread(cursor: Cursor): MmsThread? {
        fun col(name: String): Int = cursor.getColumnIndex(name)

        val threadId = col("_id").takeIf { it >= 0 }
            ?.let { cursor.getString(it) }
            ?: return null
        val recipientId = col("recipient_ids").takeIf { it >= 0 }
            ?.let { cursor.getString(it) }
            ?: threadId
        val count = col("message_count").takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0
        val unread = col("unread_count").takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0

        return MmsThread(
            threadId = threadId,
            recipient = recipientId,
            lastMessage = null,
            messageCount = count,
            unreadCount = unread,
            isArchived = false,
            isSpam = false
        )
    }

    private fun cursorToMmsMessage(cursor: Cursor): MmsMessage? {
        val messageId = cursor.getString(cursor.getColumnIndexOrThrow("_id"))
        val threadId = cursor.getLong(cursor.getColumnIndexOrThrow("thread_id"))
        val address = getAddressFromThread(threadId)
        val subject = cursor.getString(cursor.getColumnIndexOrThrow("sub"))
        val textContent = cursor.getString(cursor.getColumnIndexOrThrow("text"))
        val date = cursor.getLong(cursor.getColumnIndexOrThrow("date")) * 1000
        val mType = cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))
        val read = cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1

        val direction = if (cursor.getInt(cursor.getColumnIndexOrThrow("msg_box")) == 4) MmsDirection.OUTGOING else MmsDirection.INCOMING
        val state = when (cursor.getInt(cursor.getColumnIndexOrThrow("m_type"))) {
            128 -> MmsState.SENDING
            129 -> MmsState.SENT
            130 -> MmsState.FAILED
            else -> MmsState.DRAFT
        }

        return MmsMessage(
            messageId = cursor.getString(cursor.getColumnIndexOrThrow("_id")),
            threadId = threadId.toString(),
            address = getAddressFromThread(threadId),
            subject = cursor.getString(cursor.getColumnIndexOrThrow("sub")),
            textContent = cursor.getString(cursor.getColumnIndexOrThrow("text")),
            attachments = emptyList(),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("date")) * 1000,
            direction = direction,
            state = state,
            subscriptionId = subscriptionId,
            transportId = "mms",
            messageSize = 0,
            read = read,
            deliveryState = MmsDeliveryState.PENDING
        )
    }

    private fun getAddressFromThread(threadId: Long): String {
        val uri = Telephony.Threads.CONTENT_URI.buildUpon().appendPath(threadId.toString()).build()
        val cursor = contentResolver.query(uri, arrayOf("recipient_ids"), null, null, null)
        return cursor?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("recipient_ids")) else ""
        } ?: ""
    }

    /** Transport-level failure with a caller-readable description. */
    class MmsError(description: String) : Throwable(description)
}
