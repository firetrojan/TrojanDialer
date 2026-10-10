package com.communicator.ui.mms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
fun MmsMessageScreen(
    mmsRepository: app.MmsRepository,
    threadId: String,
    recipient: String,
    subscriptionId: Int?,
    onBack: () -> Unit
) {
    val messages = mmsRepository.getMessages(threadId).collectAsStateWithLifecycle()
    var messageText by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        TopAppBar(
            title = { androidx.compose.material3.Text(text = recipient ?: "Unknown", fontSize = 18.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    androidx.compose.material.Icon(
                        imageVector = androidx.compose.material.Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        )

        // Subject field
        androidx.compose.material3.TextField(
            value = subject,
            onValueChange = { subject = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { androidx.compose.material3.Text("Subject (optional)") },
            singleLine = true
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
                MmsMessageBubble(message = message)
            }
        }

        // Input area
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 8.dp),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                visualTransformation = VisualTransformation.None,
                label = { androidx.compose.material3.Text("Message") }
            )
            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        // Send message - would need to implement send in MmsRepository
                        messageText = ""
                    }
                },
                modifier = Modifier.height(48.dp),
                enabled = messageText.isNotBlank()
            ) {
                androidx.compose.material3.Text("Send", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MmsMessageBubble(message: app.com.communicator.communication.mms.MmsMessage) {
    val isOutgoing = message.direction == app.com.communicator.communication.mms.MmsDirection.OUTGOING

    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        androidx.compose.material3.Card(
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
            androidx.compose.foundation.layout.Column(modifier = Modifier.padding(12.dp)) {
                message.subject?.let { sub ->
                    androidx.compose.material3.Text(
                        text = sub,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOutgoing)
                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
                message.textContent?.let { text ->
                    androidx.compose.material3.Text(
                        text = text,
                        fontSize = 16.sp,
                        color = if (isOutgoing)
                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
                message.attachments.forEach { att ->
                    androidx.compose.material3.Text(
                        text = "📎 ${att.filename ?: att.mimeType}",
                        fontSize = 12.sp,
                        color = if (isOutgoing)
                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                androidx.compose.material3.Text(
                    text = message.deliveryState.name,
                    fontSize = 10.sp,
                    color = if (isOutgoing)
                        androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    else
                        androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
