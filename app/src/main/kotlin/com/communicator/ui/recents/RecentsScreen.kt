package com.communicator.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Recent call list with a search box.
 *
 * Reconstructed: the previous version could not be parsed (`Column(` was closed
 * by a stray `)` before its body) and filtered with a bare `filter { }` on no
 * receiver. It also read `call.missed` and `call.formattedDateTime`, neither of
 * which exists on CallRecord. Nothing called the screen, so it had never been
 * compiled.
 *
 * Records are passed in rather than fetched here, so the screen stays free of
 * repository dependencies. The timestamp is formatted for display instead of
 * relying on the missing helper.
 */
@Composable
fun RecentsScreen(
    calls: List<CallHistoryRepository.CallRecord> = emptyList(),
    onCallSelected: (String) -> Unit,
    onNewCall: () -> Unit
) {
    var searchText by remember { mutableStateOf("") }

    val filteredCalls = remember(calls, searchText) {
        if (searchText.isEmpty()) calls
        else calls.filter { record ->
            record.number.contains(searchText, ignoreCase = true) ||
                record.name?.contains(searchText, ignoreCase = true) == true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            label = { Text("Search recents") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(onClick = onNewCall) {
            Text("New call")
        }

        if (filteredCalls.isEmpty()) {
            Text(
                text = "No recent calls",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        filteredCalls.forEach { record ->
            val missed = record.type == CallHistoryRepository.CallType.MISSED
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (missed) Icons.Default.CallMissed else Icons.Default.Call,
                        contentDescription = null
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.name ?: record.number,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = DateFormat.getDateTimeInstance()
                                .format(Date(record.date)),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onCallSelected(record.number) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Call back")
                }
            }
        }
    }
}
