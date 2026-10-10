package com.communicator.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WAP Push Receiver for MMS notifications.
 * 
 * Receives WAP Push MMS notifications from the carrier via the Android framework.
 * The framework's WapPushOverSms parses the WAP Push PDU and delivers the parsed
 * fields as intent extras to the default SMS/MMS application.
 * 
 * Receives both WAP_PUSH_RECEIVED (legacy) and WAP_PUSH_DELIVER (correct for MMS).
 * Requires BROADCAST_WAP_PUSH permission.
 */
class WapPushReceiver : BroadcastReceiver() {

    private val logTag = "WapPushReceiver"
    private var mmsNotificationCallback: ((String, Long, Int, String?) -> Unit)? = null

    fun setMmsNotificationCallback(callback: (String, Long, Int, String?) -> Unit) {
        mmsNotificationCallback = callback
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Handle both WAP_PUSH_RECEIVED (legacy) and WAP_PUSH_DELIVER (correct for MMS)
        if (action != "android.provider.Telephony.WAP_PUSH_RECEIVED" &&
            action != "android.provider.Telephony.WAP_PUSH_DELIVER") {
            return
        }

        val bundle = intent.extras ?: return
        
        // Only process MMS notifications
        val contentType = bundle.getString("type") ?: ""
        if (!contentType.startsWith("application/vnd.wap.mms-message")) {
            return
        }

        // Parse WAP Push MMS notification from intent extras
        // The framework's WapPushOverSms parses the WAP Push PDU and puts
        // parsed fields in intent extras.
        val transactionId = intent.getIntExtra("transactionId", -1)
        val contentLocation = intent.getStringExtra("contentLocation") ?: 
            intent.getStringExtra("content-location")
        val expiry = intent.getLongExtra("expiry", 0)
        val sender = intent.getStringExtra("address")
        val subId = intent.getIntExtra("subscription", -1)
        val transactionId = intent.getIntExtra("transactionId", -1)
        val pduType = intent.getIntExtra("pduType", -1)
        
        // Also check for contentLocation in different keys
        val contentLocation = intent.getStringExtra("contentLocation") ?: 
            intent.getStringExtra("content-location") ?:
            intent.getStringExtra("Content-Location")

        if (transactionId == -1 && contentLocation == null) {
            Log.w("WapPushReceiver", "Incomplete MMS notification: missing transactionId or contentLocation")
            // Don't return - we might still have a valid notification
        }

        val sender = intent.getStringExtra("address")
        val subId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.getIntExtra("subscription", -1)
        } else {
            intent.getIntExtra("subscription", -1)
        }
        val pduType = intent.getIntExtra("pduType", -1)
        val mimeType = intent.getStringExtra("type") ?: ""
        val expiry = intent.getLongExtra("expiry", 0)
        val contentLocation = intent.getStringExtra("contentLocation") ?: 
            intent.getStringExtra("content-location") ?:
            intent.getStringExtra("Content-Location")

        Log.d("WapPushReceiver", "MMS notification received: transactionId=$transactionId, location=$contentLocation, from=$sender, subId=$subId, pduType=$pduType, expiry=$expiry")

        // Notify callback with the parsed notification data
        mmsNotificationCallback?.invoke(
            transactionId.toString(),
            expiry,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) subId else -1,
            contentLocation
        )
    }
}
