package com.communicator.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.Call.Details
import android.telecom.CallScreeningService
import android.telecom.PhoneAccount
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CallScreeningServiceImpl : CallScreeningService() {

    override fun onScreenCall(
        incomingCall: Call,
        hal: android.os.IBinder?,
        userIntent: Int
    ): Boolean {
        val number = incomingCall.details.handle?.schemeSpecificPart ?: ""
        Log.d("CallScreening", "Incoming call from: $number")

        // Allow by default - the app is the default dialer and should let Telecom handle it
        // Only block if explicitly configured to do so
        return true
    }

    override fun onCallAdded(
        call: Call,
        state: Details
    ) {
        super.onCallAdded(call, state)
        Log.d("CallScreening", "Call added: ${call.details.handle?.schemeSpecificPart}, state: ${state.state}")
    }

    override fun onCallRemoved(
        call: Call,
        reason: Int
    ) {
        super.onCallRemoved(call, reason)
        Log.d("CallScreening", "Call removed: ${call.details.handle?.schemeSpecificPart}")
    }
}
