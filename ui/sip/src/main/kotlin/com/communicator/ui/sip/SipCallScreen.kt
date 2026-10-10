package com.communicator.ui.sip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicator.app
import com.communicator.ui.theme.Theme
import kotlinx.coroutines.launch

@Composable
fun SipCallScreen(
    sipRepository: app.SipRepository,
    onBack: () -> Unit
) {
    val activeCall = sipRepository.getActiveCall()
    val activeCalls = sipRepository.getCalls().collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        androidx.compose.material3.TopAppBar(
            title = { androidx.compose.material3.Text(text = "SIP Call", fontSize = 18.sp) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material.Icon(
                        imageVector = androidx.compose.material.Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )

        // Active call or dialer
        if (activeCall != null) {
            // Active call UI
            Card(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
                    androidx.compose.material3.Text(
                        text = activeCall.remoteUri ?: "Unknown",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )

                    androidx.compose.material3.Text(
                        text = activeCall.state.name,
                        fontSize = 14.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { sipRepository.holdCall(activeCall.callId) },
                            modifier = Modifier.weight(1f),
                            enabled = !activeCall.isOnHold
                        ) {
                            androidx.compose.material3.Text(if (activeCall.isOnHold) "Unhold" else "Hold")
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { sipRepository.muteCall(activeCall.callId, !activeCall.isMuted) },
                            modifier = Modifier.weight(1f)
                        ) {
                            androidx.compose.material3.Text(if (activeCall.isMuted) "Unmute" else "Mute")
                        }

                        androidx.compose.material3.OutlinedButton(
                            onClick = { /* speaker toggle */ },
                            modifier = Modifier.weight(1f)
                        ) {
                            androidx.compose.material3.Text(if (activeCall.isSpeaker) "Earpiece" else "Speaker")
                        }
                    }

                    Button(
                        onClick = { sipRepository.endCall(activeCall.callId) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        androidx.compose.material3.Text("End Call", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Dialer
            var number by remember { mutableStateOf("") }

            TextField(
                value = number,
                onValueChange = { number = it.filter { it.isDigit() || it in "+*#" } },
                modifier = Modifier.fillMaxWidth(),
                label = { androidx.compose.material3.Text("Enter SIP URI or number") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
            )

            // Dial pad
            androidx.compose.foundation.layout.Column(
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
                                androidx.compose.material3.Text(text = digit, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                            if (isVideoCall) {
                                sipRepository.makeVideoCall("", number)
                            } else {
                                sipRepository.makeCall("", number)
                            }
                            number = ""
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    enabled = number.isNotBlank()
                ) {
                    androidx.compose.material3.Text(text = if (isVideoCall) "Video Call" else "Call", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                androidx.compose.material3.OutlinedButton(
                    onClick = { number = "" },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    androidx.compose.material3.Text(text = "Clear", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
