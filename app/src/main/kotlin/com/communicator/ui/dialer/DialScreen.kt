package com.communicator.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.keyboardActions.KeyboardActions
import androidx.compose.foundation.text.keyboardKey.numericKeyboardKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DialScreen(
    onDigitSelected: (String) -> Unit,
    onCallInitiated: (String) -> Unit,
    initialNumber: String = ""
) {
    var number by remember { mutableStateOf(initialNumber) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp) {
            // Display area
            Text(
                text = number.isEmpty() ? "Enter number" : number,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Dial pad
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                digits1 = ["1", "2", "3"]
                digits2 = ["4", "5", "6"]
                digits3 = ["7", "8", "9"]
                digits4 = ["*", "0", "#"]

                fun addDigit(digit: String) {
                    onDigitSelected(digit)
                    number = if (number.isEmpty()) digit else number + digit
                }

                // Row 1
                callDigitButton("1", digits1[0])
                callDigitButton("2", digits1[1])
                callDigitButton("3", digits1[2])

                // Row 2
                callDigitButton("4", digits2[0])
                callDigitButton("5", digits2[1])
                callDigitButton("6", digits2[2])

                // Row 3
                callDigitButton("7", digits3[0])
                callDigitButton("8", digits3[1])
                callDigitButton("9", digits3[2])

                // Row 4
                callDigitButton("*", digits4[0])
                callDigitButton("0", digits4[1])
                callDigitButton("#", digits4[2])
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MaterialIconButton(
                    onClick = { onCallInitiate(number) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Call", style = MaterialTheme.typography.h6)
                }

                MaterialButtonOutlinedText(
                    onClick = { number = "" },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear", style = MaterialTheme.typography.h6)
                }
            }
        }
    )
