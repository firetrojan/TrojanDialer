package com.communicator.ui.mms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

@Composable
fun MmsConversationListScreen(
    mmsRepository: app.MmsRepository,
    onConversationClick: (String, String, Int?) -> Unit,
    onNewMessage: () -> Unit
) {
    val conversations = remember { mmsRepository.getThreads() }.collectAsStateWithLifecycle()
    val transports = remember { mmsRepository.getTransports() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Transport selector if multiple SIMs
        if (transports.size > 1) {
            androidx.compose.material3.Text("MMS Transport:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                transports.forEach { transport ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (transport.subscriptionId == mmsRepository.activeSubscription.value)
                                    androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                                else
                                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .clickable { mmsRepository.setActiveSubscription(transport.subscriptionId) }
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            androidx.compose.material3.Text(
                                text = transport.name,
                                fontSize = 14.sp
                            )
                            if (transport.subscriptionId == mmsRepository.activeSubscription.value) {
                                androidx.compose.material.Icon(
                                    imageVector = androidx.compose.material.Icons.Default.Check,
                                    contentDescription = "Selected"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Compose new message
        androidx.compose.material3.FloatingActionButton(
            onClick = onNewMessage,
            modifier = Modifier.align(Alignment.End)
        ) {
            androidx.compose.material.Icon(
                imageVector = androidx.compose.material.Icons.Default.Add,
                contentDescription = "New MMS"
            )
        }

        // Conversation list
        if (conversations.isEmpty()) {
            androidx.compose.material3.Text(
                text = "No MMS messages yet",
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center),
                fontSize = 16.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            conversations.forEach { conversation ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onConversationClick(conversation.threadId, conversation.recipient, conversation.subscriptionId) }
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text(
                                text = conversation.recipient ?: "Unknown",
                                fontSize = 16.sp,
                                fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                            )
                            androidx.compose.material3.Text(
                                text = conversation.lastMessage?.textContent ?: conversation.lastMessage?.subject ?: "No messages",
                                fontSize = 14.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.overflow.TextOverflow.Ellipsis
                            )
                        }
                        if (conversation.unreadCount > 0) {
                            androidx.compose.material3.Text(
                                text = conversation.unreadCount.toString(),
                                fontSize = 12.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
