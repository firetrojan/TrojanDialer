package com.communicator.ui.webrtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.communicator.app
import com.communicator.ui.theme.Theme
import kotlinx.coroutines.launch

@Composable
fun WebRtcAccountSetupScreen(
    webrtcRepository: app.WebRtcRepository,
    onAccountConfigured: () -> Unit
) {
    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf("") }
    var isSecure by remember { mutableStateOf(true) }
    var iceServers by remember { mutableStateOf("stun:stun.l.google.com:19302") }
    var connectionState by remember { mutableStateOf<app.WebRtcConnectionState?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        androidx.compose.material3.Text(
            text = "WebRTC Signaling Setup",
            fontSize = 24.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )

        androidx.compose.material3.TextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Signaling Server URL") },
            singleLine = true
        )

        androidx.compose.material3.TextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("API Key (optional)") },
            singleLine = true
        )

        androidx.compose.material3.TextField(
            value = authToken,
            onValueChange = { authToken = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Auth Token (optional)") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )

        androidx.compose.material3.TextField(
            value = iceServers,
            onValueChange = { iceServers = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("ICE Servers (comma-separated)") },
            singleLine = true
        )

        androidx.compose.material3.Switch(
            checked = isSecure,
            onCheckedChange = { isSecure = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            androidx.compose.material3.Text("Use Secure Connection (WSS/HTTPS)")
        }

        connectionState?.let { state ->
            androidx.compose.material3.Text(
                text = "Connection: ${state.name}",
                fontSize = 16.sp,
                color = when (state) {
                    app.WebRtcConnectionState.CONNECTED -> androidx.compose.material3.MaterialTheme.colorScheme.primary
                    app.WebRtcConnectionState.CONNECTING -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
                    app.WebRtcConnectionState.FAILED -> androidx.compose.material3.MaterialTheme.colorScheme.error
                    else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        errorMessage?.let { msg ->
            androidx.compose.material3.Text(
                text = msg,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.Button(
                onClick = {
                    val result = webrtcRepository.connect(
                        serverUrl = serverUrl,
                        apiKey = apiKey.ifBlank { null },
                        authToken = authToken.ifBlank { null },
                        isSecure = isSecure,
                        iceServers = iceServers.split(",").map { it.trim() }
                    )
                    if (result.isSuccess) {
                        errorMessage = null
                        onAccountConfigured()
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "Connection failed"
                    }
                },
                modifier = androidx.compose.ui.Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = serverUrl.isNotBlank()
            ) {
                androidx.compose.material3.Text("Connect", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            androidx.compose.material3.OutlinedButton(
                onClick = { onAccountConfigured() },
                modifier = androidx.compose.ui.Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                androidx.compose.material3.Text("Cancel", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
