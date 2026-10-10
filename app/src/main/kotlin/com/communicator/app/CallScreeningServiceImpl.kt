package com.communicator.app

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Call screening for incoming calls (API 24+).
 *
 * The platform requires a [CallScreeningService] to answer every screened call
 * by calling [respondToCall] within five seconds of [onScreenCall] being
 * invoked, otherwise the call is treated as blocked. Blocking is therefore not
 * an option here: this service always responds with
 * [CallResponse.Builder.ALLOW] and leaves any spam filtering to the classifier
 * that already runs elsewhere in the messaging pipeline.
 *
 * The previous version overrode `onScreenCall(Call, IBinder, Int)` and also
 * defined `onCallAdded` / `onCallRemoved`. None of those three methods exist
 * on [CallScreeningService] - `onCallAdded` and `onCallRemoved` belong to
 * [android.telecom.InCallService] - so the class could not compile and, more
 * importantly, could never have answered a call it was asked to screen.
 */
class CallScreeningServiceImpl : CallScreeningService() {

    companion object {
        private const val TAG = "CallScreening"
    }

    /**
     * Called by the platform for every incoming call this app is asked to
     * screen. The single-argument form is the real callback.
     */
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val isIncoming = callDetails.hasProperty(Call.Details.PROPERTY_INCOMING)
        Log.d(TAG, "Screening call from $number (incoming=$isIncoming)")

        // Always allow: letting the five-second deadline lapse would block the
        // call, which is the opposite of what a default dialer should do.
        respondToCall(callDetails, CallResponse.Builder()
            .setAllowCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build())
    }
}
