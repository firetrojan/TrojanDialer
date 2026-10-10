package com.communicator.ui.incoming

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.communicator.app

@Composable
fun IncomingCallScreen(
    phoneNumber: String,
    onAnswer: () -> Unit,
    onReject: () -> Unit
) {
    var accepted by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Incoming Call",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = phoneNumber,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextButton(
                onClick = { onAnswer() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Answer")
            }

            OutlinedTextButton(
                onClick = { onReject() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Reject")
            }
        }

        if (!accepted) {
            // Show accepting animation or text
            Text(
                text = "Accepting in 00:30",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
