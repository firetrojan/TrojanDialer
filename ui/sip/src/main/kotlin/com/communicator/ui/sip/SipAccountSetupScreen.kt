package com.communicator.ui.sip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.communicator.app
import com.communicator.ui.theme.Theme
import kotlinx.coroutines.launch

@Composable
fun SipAccountSetupScreen(
    sipRepository: app.SipRepository,
    onAccountRegistered: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(5060) }
    var transportType by remember { mutableStateOf(app.SipTransportType.TLS) }
    var proxy by remember { mutableStateOf("") }
    var outboundProxy by remember { mutableStateOf("") }
    var authUsername by remember { mutableStateOf("") }
    var enableSrtp by remember { mutableStateOf(true) }
    var enableZrtp by remember { mutableStateOf(false) }
    var enableDtlsSrtp by remember { mutableStateOf(true) }
    var registrationState by remember { mutableStateOf<app.SipRegistrationState?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        androidx.compose.material3.Text(
            text = "SIP Account Setup",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        TextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Username") },
            singleLine = true
        )

        TextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Password") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )

        TextField(
            value = domain,
            onValueChange = { domain = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Domain (e.g., sip.example.com)") },
            singleLine = true
        )

        TextField(
            value = displayName,
            onValueChange = { displayName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Display Name (optional)") },
            singleLine = true
        )

        TextField(
            value = port.toString(),
            onValueChange = { port = it.toIntOrNull() ?: 5060 },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Port") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
        )

        androidx.compose.material3.DropdownMenu(
            expanded = transportType != null,
            onDismissRequest = { transportType = null }
        ) {
            app.SipTransportType.values().forEach { type ->
                androidx.compose.material3.DropdownMenuItem(
                    onClick = { transportType = type },
                    content = { androidx.compose.material3.Text(text = type.name) }
                )
            }
        }

        TextField(
            value = proxy,
            onValueChange = { proxy = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Proxy (optional)") },
            singleLine = true
        )

        TextField(
            value = outboundProxy,
            onValueChange = { outboundProxy = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Outbound Proxy (optional)") },
            singleLine = true
        )

        TextField(
            value = authUsername,
            onValueChange = { authUsername = it },
            modifier = Modifier.fillMaxWidth(),
            label = { androidx.compose.material3.Text("Auth Username (optional)") },
            singleLine = true
        )

        androidx.compose.material3.Switch(
            checked = enableSrtp,
            onCheckedChange = { enableSrtp = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Text("Enable SRTP")
        }

        androidx.compose.material3.Switch(
            checked = enableZrtp,
            onCheckedChange = { enableZrtp = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Text("Enable ZRTP")
        }

        androidx.compose.material3.Switch(
            checked = enableDtlsSrtp,
            onCheckedChange = { enableDtlsSrtp = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Text("Enable DTLS-SRTP")
        }

        errorMessage?.let { msg ->
            androidx.compose.material3.Text(
                text = msg,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
        }

        registrationState?.let { state ->
            androidx.compose.material3.Text(
                text = "Registration: ${state.name}",
                fontSize = 16.sp,
                color = when (state) {
                    app.SipRegistrationState.REGISTERED -> androidx.compose.material3.MaterialTheme.colorScheme.primary
                    app.SipRegistrationState.REGISTERING -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
                    app.SipRegistrationState.FAILED -> androidx.compose.material3.MaterialTheme.colorScheme.error
                    else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.Button(
                onClick = {
                    val result = sipRepository.registerAccount(
                        username = username,
                        password = password,
                        domain = domain,
                        displayName = displayName.ifBlank { null },
                        port = port,
                        transportType = transportType,
                        proxy = proxy.ifBlank { null },
                        outboundProxy = outboundProxy.ifBlank { null },
                        authUsername = authUsername.ifBlank { null },
                        enableSrtp = enableSrtp,
                        enableZrtp = enableZrtp,
                        enableDtlsSrtp = enableDtlsSrtp
                    )
                    if (result.isSuccess) {
                        errorMessage = null
                        onAccountRegistered()
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "Registration failed"
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                androidx.compose.material3.Text("Register")
            }

            androidx.compose.material3.OutlinedButton(
                onClick = { onAccountRegistered() },
                modifier = Modifier.weight(1f)
            ) {
                androidx.compose.material3.Text("Cancel")
            }
        }
    }
}
