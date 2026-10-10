package com.communicator.app

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.communicator.communication.mms.MmsTransport.MmsAttachment
import com.communicator.communication.mms.MmsTransport.MmsMessage
import com.communicator.communication.mms.MmsTransport.MmsRequest
import com.communicator.communication.mms.MmsTransport.MmsThread
import com.communicator.communication.mms.MmsTransport.MmsResult
import com.communicator.communication.mms.MmsTransport
import com.communicator.communication.mms.MmsTransportImpl
import com.communicator.data.core.mms.MmsDeliveryState
import com.communicator.data.core.mms.MmsDirection
import com.communicator.data.core.mms.MmsMessageEntity
import com.communicator.data.core.mms.MmsMessageDao
import com.communicator.data.core.mms.MmsPartDao
import com.communicator.data.core.mms.MmsThreadDao
import com.communicator.data.core.mms.MmsMessageEntity
import com.communicator.data.core.mms.MmsThreadEntity
import com.communicator.data.core.mms.MmsDirection
import com.communicator.data.core.mms.MmsState
import com.communicator.data.core.mms.MmsDeliveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

class MmsRepository(
    private val context: Context,
    private val threadDao: MmsThreadDao,
    private val messageDao: MmsMessageDao,
    private val partDao: MmsPartDao,
    private val subscriptionManager: android.telephony.SubscriptionManager
) {

    private val transports = mutableMapOf<Int, MmsTransportImpl>()
    private val activeSubscriptionId = MutableStateFlow<Int?>(null)

    init {
        // Initialize transports for all active subscriptions
        subscriptionManager.activeSubscriptionInfoList?.forEach { subInfo ->
            val transport = MmsTransportImpl(context, subInfo.subscriptionId)
            transports[subInfo.subscriptionId] = transport
        }
        
        // Set default to first active subscription
        subscriptionManager.activeSubscriptionInfoList?.firstOrNull()?.let {
            activeSubscriptionId.value = it.subscriptionId
        }
    }

    fun getTransports(): List<MmsTransportImpl> {
        return transports.values.toList()
    }

    fun getTransport(subscriptionId: Int): MmsTransportImpl? {
        return transports[subscriptionId]
    }

    fun getActiveTransport(): MmsTransportImpl? {
        return activeSubscriptionId.value?.let { transports[it] }
    }

    fun setActiveSubscription(subscriptionId: Int) {
        if (transports.containsKey(subscriptionId)) {
            activeSubscriptionId.value = subscriptionId
        }
    }

    val activeSubscription: kotlinx.coroutines.flow.StateFlow<Int?> = activeSubscriptionId

    suspend fun sendMms(
        address: String,
        subject: String?,
        textContent: String?,
        attachments: List<MmsAttachment>,
        subscriptionId: Int? = null,
        deliveryReport: Boolean = false
    ): MmsResult {
        val targetSubscriptionId = subscriptionId ?: activeSubscriptionId.value
        val transport = targetSubscriptionId?.let { transports[it] } ?: getActiveTransport()
        
        return if (transport != null) {
            val request = MmsRequest(
                address = address,
                subject = subject,
                textContent = textContent,
                attachments = attachments.map { att ->
                    MmsAttachment(
                        contentUri = android.net.Uri.parse(att.uri),
                        mimeType = att.mimeType,
                        filename = att.filename,
                        contentId = att.contentId,
                        contentLocation = att.contentLocation
                    )
                },
                subscriptionId = transport.subscriptionId,
                deliveryReport = deliveryReport,
                readReport = false
            )
            transport.sendMms(request)
        } else {
            MmsResult.failure(IllegalStateException("No active MMS transport"))
        }
    }

    suspend fun getMms(mmsId: String): com.communicator.communication.mms.MmsMessage? {
        val transport = getActiveTransport()
        return transport?.getMms(mmsId)
    }

    suspend fun getThreads(): Flow<List<com.communicator.communication.mms.MmsThread>> {
        val transport = getActiveTransport()
        return transport?.getThreads() ?: kotlinx.coroutines.flow.flow { }
    }

    suspend fun getMessages(threadId: String): Flow<List<com.communicator.communication.mms.MmsMessage>> {
        val transport = getActiveTransport()
        return transport?.getMessages(threadId) ?: kotlinx.coroutines.flow.flow { }
    }

    suspend fun getMms(mmsId: String): com.communicator.communication.mms.MmsMessage? {
        val transport = getActiveTransport()
        return transport?.getMms(mmsId)
    }

    fun getActiveTransport(): MmsTransportImpl? {
        return activeSubscriptionId.value?.let { transports[it] }
    }

    suspend fun observeMms(): Flow<com.communicator.communication.mms.MmsMessage> {
        val transport = getActiveTransport()
        return transport?.observeMms() ?: kotlinx.coroutines.flow.flow { }
    }

    // Handle incoming MMS notification from WAP Push
    fun onMmsNotification(
        transactionId: String,
        contentLocation: String?,
        sender: String,
        subscriptionId: Int
    ) {
        // Trigger MMS download via MmsTransport
        CoroutineScope(Dispatchers.IO).launch {
            val transport = transports[subscriptionId] ?: getActiveTransport()
            if (transport != null && contentLocation != null) {
                // Create a destination URI for the downloaded MMS
                val contentUri = Telephony.Mms.CONTENT_URI
                val messageId = "incoming_${transactionId}_${System.currentTimeMillis()}"
                
                // Create a PendingIntent for the download result
                val downloadedIntent = PendingIntent.getBroadcast(
                    context,
                    messageId.hashCode(),
                    Intent("MMS_DOWNLOADED_$subscriptionId")
                        .putExtra("messageId", messageId)
                        .putExtra("subscriptionId", subscriptionId)
                        .putExtra("transactionId", transactionId),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else
                        PendingIntent.FLAG_UPDATE_CURRENT
                )
                
                // Download the MMS
                CoroutineScope(Dispatchers.IO).launch {
                    val result = (transports[subscriptionId] ?: getActiveTransport())?.downloadMms(
                        locationUrl = contentLocation ?: "",
                        contentUri = Telephony.Mms.CONTENT_URI,
                        configOverrides = android.os.Bundle().apply {
                            putString("transactionId", transactionId)
                        },
                        downloadedIntent = PendingIntent.getBroadcast(
                            context,
                            messageId.hashCode(),
                            Intent("MMS_DOWNLOADED_$subscriptionId")
                                .putExtra("messageId", messageId)
                                .putExtra("subscriptionId", subscriptionId)
                                .putExtra("transactionId", transactionId),
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            else
                                PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                    
                    if (result != null && result.success) {
                        // MMS downloaded successfully - observe the content provider for the new MMS
                        // The downloadMultimediaMessage writes to the content provider
                        // We need to query for the new MMS and persist it
                        Log.d("MmsRepository", "MMS downloaded successfully for transactionId: $transactionId")
                    } else {
                        Log.e("MmsRepository", "MMS download failed for transactionId: $transactionId")
                    }
                }
            }
        }
    }
}
