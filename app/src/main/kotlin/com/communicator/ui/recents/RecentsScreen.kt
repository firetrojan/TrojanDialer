package com.communicator.ui.recents

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.communicator.app

@Composable
fun RecentsScreen(
    onCallSelected: (String) -> Unit,
    onNewCall: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }
    val filteredCalls = remember {
        filter {
            it.contains(searchText, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp) {
            // Search field
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = "Search recents",
                modifier = Modifier.fillMaxWidth()
            )

            // Empty state
            if (filteredCalls.isEmpty()) {
                Text(
                    text = "No recent calls",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // List of calls
                filteredCalls.map { call ->
                    Button(
                        onClick = { onCallSelected(call) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (call.missed) Icons.Default.CallMissed else Icons.Default.Call,
                                contentDescription = null
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Start
                            ) {
                                Text(
                                    text = call.name ?: call.number ?: "Unknown",
                                    style = MaterialTheme.typography.body1
                                )
                                Text(
                                    text = call.formattedDateTime,
                                    style = MaterialTheme.typography.caption
                                )
                            }
                        }
                    )
                }
            }
        }
    )
}
