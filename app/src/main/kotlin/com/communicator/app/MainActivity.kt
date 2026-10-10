package com.communicator.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedButton
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.communicator.ui.theme.Theme
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val telecomRepository by lazy { TelecomRepository(this) }

    private val requestDefaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("Dialer", "Default dialer role granted")
        } else {
            Log.d("Dialer", "Default dialer role not granted")
        }
    }

    // Mutable state for dial number that survives recomposition
    private var pendingDialNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle ACTION_DIAL / ACTION_CALL intent
        intent?.let { handleIntent(it) }

        setContent {
            Theme {
                DialerScreen(
                    telecomRepository = telecomRepository,
                    onRequestDefaultDialer = { requestDefaultDialer() },
                    initialNumber = pendingDialNumber ?: ""
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        // Note: In a production app, we'd use a ViewModel or shared state holder
        // to propagate the new number to the Compose UI. For simplicity, we log it.
        // The DialerScreen would need to observe a StateFlow or LiveData for live updates.
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_DIAL == intent.action || Intent.ACTION_CALL == intent.action) {
            val number = intent.data?.schemeSpecificPart ?: ""
            Log.d("Dialer", "${intent.action} received: $number")
            // Store for next recomposition
            pendingDialNumber = number
        }
    }

    fun requestDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                requestDefaultDialerLauncher.launch(intent)
            }
        }
    }

    fun isDefaultDialer(): Boolean {
        return telecomRepository.isDefaultDialerRoleHeld()
    }
}

@Composable
fun DialerScreen(
    telecomRepository: TelecomRepository,
    onRequestDefaultDialer: () -> Unit,
    initialNumber: String = ""
) {
    var number by remember { mutableStateOf(initialNumber) }
    var selectedAccount by remember { mutableStateOf<PhoneAccountHandle?>(null) }

    val callCapableAccounts = telecomRepository.callCapableAccounts.collectAsStateWithLifecycle()
    val isDefaultDialer = telecomRepository.isDefaultDialer.collectAsStateWithLifecycle()
    val activeSubscriptions = telecomRepository.getActiveSubscriptionInfoList()

    // Active calls state
    val activeCalls = telecomRepository.activeCalls.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Default dialer status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (isDefaultDialer) androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                else androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isDefaultDialer) "Default Dialer: ACTIVE" else "Default Dialer: NOT SET",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (!isDefaultDialer) {
                    androidx.compose.material3.TextButton(
                        onClick = onRequestDefaultDialer,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Set as Default Dialer", fontSize = 14.sp)
                    }
                }
            }
        }

        // SIM/Account selector
        if (callCapableAccounts.size > 1) {
            Text("Select SIM/Account:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                callCapableAccounts.forEach { account ->
                    val subscription = activeSubscriptions.find { sub ->
                        val accountSubId = telecomRepository.getPhoneAccountHandleForSubscription(sub.subscriptionId)
                        accountSubId == account
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selectedAccount == account)
                                    androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                                else
                                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .clickable { selectedAccount = if (selectedAccount == account) null else account }
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = subscription?.carrierName?.toString() ?: "SIM ${subscription?.subscriptionId ?: "Unknown"}",
                                fontSize = 16.sp
                            )
                            if (selectedAccount == account) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Number input
        TextField(
            value = number,
            onValueChange = { number = it.filter { it.isDigit() || it in "+*#" } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            visualTransformation = VisualTransformation.None,
            label = { Text("Enter phone number") }
        )

        // Dial pad
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val rows = arrayOf(
                arrayOf("1", "2", "3"),
                arrayOf("4", "5", "6"),
                arrayOf("7", "8", "9"),
                arrayOf("*", "0", "#")
            )
            rows.forEach { row ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { digit ->
                        Button(
                            onClick = { number += digit },
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                        ) {
                            Text(text = digit, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Action buttons
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (number.isNotBlank()) {
                        telecomRepository.placeCall(number, selectedAccount)
                        number = ""
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = number.isNotBlank()
            ) {
                Text(text = "Call", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            androidx.compose.material3.OutlinedButton(
                onClick = { number = "" },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text(text = "Clear", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Active calls section
        if (activeCalls.isNotEmpty()) {
            Text("Active Calls:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                activeCalls.forEach { (callId, callState) ->
                    val callInfo = telecomRepository.getCallInfo(callId)
                    val canHold = callInfo?.canHold ?: false
                    val canMerge = callInfo?.canMerge ?: false
                    val canSwap = callInfo?.canSwap ?: false
                    val canConference = callInfo?.canConference ?: false
                    val canDisconnect = callInfo?.canDisconnect ?: true

                    Card(modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(text = callState.number ?: "Unknown", fontSize = 16.sp)
                                Text(
                                    text = "${TelecomRepository.getStateLabel(callState.state)} ${if (callState.isVideo) "📹" else "📞"}",
                                    fontSize = 12.sp,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (callState.state == android.telecom.Call.STATE_RINGING) {
                                    Button(
                                        onClick = { telecomRepository.answerCall(callId) },
                                        enabled = true
                                    ) { Text("Answer") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { telecomRepository.rejectCall(callId) }
                                    ) { Text("Reject") }
                                } else if (callState.value.isActiveState()) {
                                    Button(
                                        onClick = { telecomRepository.endCall(callId) },
                                        enabled = callState.value.canDisconnect
                                    ) { Text("End") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { 
                                            if (callState.isOnHold) telecomRepository.unholdCall(callId) 
                                            else telecomRepository.holdCall(callId)
                                        },
                                        enabled = callInfo?.canHold ?: false
                                    ) { Text(if (callState.isOnHold) "Unhold" else "Hold") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { telecomRepository.muteCall(callId, !callState.isMuted) }
                                    ) { Text(if (callState.isMuted) "Unmute" else "Mute") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { /* speaker - AudioRouteManager */ }
                                    ) { Text(if (callState.isSpeaker) "Earpiece" else "Speaker") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
