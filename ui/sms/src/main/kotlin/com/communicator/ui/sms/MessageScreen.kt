package com.communicator.ui.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
fun MessageScreen(
    smsRepository: app.SmsRepository,
    threadId: String,
    participant: String,
    subscriptionId: Int?,
    onBack: () -> Unit
) {
    val messages = smsRepository.getMessages(threadId).collectAsStateWithLifecycle()
    var messageText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        androidx.compose.material3.TopAppBar(
            title = { Text(text = participant ?: "Unknown", fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    androidx.compose.material.Icon(
                        imageVector = androidx.compose.material.Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )

        // Messages list
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            reverseLayout = true
        ) {
            items(messages.reversed()) { message ->
                MessageBubble(message = message)
            }
        }

        // Input area
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                visualTransformation = VisualTransformation.None,
                label = { Text("Message") }
            )
            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        // Send message
                        messageText = ""
                    }
                },
                modifier = Modifier.height(48.dp),
                enabled = messageText.isNotBlank()
            ) {
                Text("Send", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MessageBubble(message: app.SmsMessageEntity) {
    val isOutgoing = message.direction == app.MessageDirection.OUTGOING

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .padding(8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (isOutgoing)
                    androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                else
                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    fontSize = 16.sp,
                    color = if (isOutgoing)
                        androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatTimestamp(message.timestamp),
                    fontSize = 10.sp,
                    color = if (isOutgoing)
                        androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (message.direction == app.MessageDirection.OUTGOING) {
                    Text(
                        text = message.deliveryState.name,
                        fontSize = 10.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(date)
}
