package com.communicator.app

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives inbound SMS broadcasts and forwards them to [SmsRepository].
 *
 * Registered for both `SMS_DELIVER` (delivered only to the default SMS app) and
 * `SMS_RECEIVED`, so a single receiver covers both delivery paths.
 *
 * Scope: **inbound SMS only.** Outbound sending lives in [SmsRepository] via the
 * SMS transport. MMS notification handling is separate, in [WapPushReceiver];
 * no MMS content transfer happens here.
 *
 * Multipart messages are reassembled from their PDU parts before reaching the
 * repository, so a long SMS arrives as one body rather than as fragments.
 *
 * DI: a manifest-declared BroadcastReceiver is instantiated by the system and
 * cannot take constructor parameters, so Hilt cannot inject it directly. It is
 * @AndroidEntryPoint, which makes Hilt inject into its fields.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject
    lateinit var smsRepository: SmsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        ) {
            return
        }

        @Suppress("DEPRECATION") // the documented decoder for this broadcast
        val parts: Array<SmsMessage>? = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return

        val assembled = assemble(parts) ?: return
        val subscriptionId = readSubscriptionId(intent)
        val wasMultipart = parts.size > 1

        smsRepository.onIncomingSms(
            sender = assembled.sender,
            content = assembled.body,
            timestamp = assembled.timestamp,
            subscriptionId = subscriptionId,
            isMultipart = wasMultipart
        )

        // Android requires the default SMS app to consume a delivered SMS. Any
        // other app must not abort, or the message can be lost.
        if (action == Telephony.Sms.Intents.SMS_DELIVER_ACTION &&
            isDefaultSmsApplication(context)
        ) {
            abortBroadcast()
        }
    }

    private data class AssembledSms(val sender: String, val body: String, val timestamp: Long)

    /**
     * Concatenates multipart bodies in PDU order. Android delivers all parts of
     * a concatenated SMS in one broadcast, so ordering by originating timestamp
     * keeps the reassembled text readable.
     */
    private fun assemble(parts: Array<SmsMessage>): AssembledSms? {
        val sender = parts.firstOrNull()?.originatingAddress
        if (sender.isNullOrBlank()) return null
        val ordered = parts.sortedBy { it.timestampMillis }
        return AssembledSms(
            sender = sender,
            body = ordered.joinToString(separator = "") { it.messageBody.orEmpty() },
            timestamp = ordered.first().timestampMillis
        )
    }

    /**
     * Subscription id from the broadcast extras. -1 means the broadcast did not
     * identify a SIM, which is normal on single-SIM devices and on releases that
     * predate the `subscription` extra.
     */
    private fun readSubscriptionId(intent: Intent): Int =
        intent.extras?.getInt("subscription", -1) ?: -1

    companion object {
        private const val logTag = "SmsReceiver"

        /**
         * True when this app currently holds the default-SMS-app role.
         *
         * RoleManager from API 29, Telephony.Sms.getDefaultSmsPackage before
         * that, because the role is the only accurate signal on modern releases.
         */
        fun isDefaultSmsApplication(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            } else {
                @Suppress("DEPRECATION")
                Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            }
    }
}
