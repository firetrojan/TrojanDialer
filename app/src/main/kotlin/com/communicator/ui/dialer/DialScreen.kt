package com.communicator.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Numeric dial pad.
 *
 * Reconstructed: the previous version could not be parsed. `Column(` was closed
 * by a stray `)` before its body, the digit rows were assigned to undeclared
 * names (digits1..digits4, callDigitButton), and it referenced composables that
 * do not exist (`MaterialIconButton`, `MaterialButtonOutlinedText`). Nothing
 * called the screen, so it had never been compiled.
 *
 * The digit layout is a straight 4x3 grid with no per-row grouping, matching
 * what the surrounding dialer screens expect.
 */
@Composable
fun DialScreen(
    onDigitSelected: (String) -> Unit,
    onCallInitiated: (String) -> Unit,
    initialNumber: String = ""
) {
    var number by remember { mutableStateOf(initialNumber) }

    fun addDigit(digit: String) {
        onDigitSelected(digit)
        number += digit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (number.isEmpty()) "Enter number" else number,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { digit ->
                    Button(
                        onClick = { addDigit(digit) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(digit)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { onCallInitiated(number) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Call", style = MaterialTheme.typography.titleLarge)
            }

            OutlinedButton(
                onClick = { number = "" },
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
