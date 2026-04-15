package com.example.ehts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*

@Composable
fun DobVerificationScreen(
    expectedDob: String,
    onSuccess: () -> Unit,
    onFail: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var attempts by remember { mutableIntStateOf(3) }
    var msg by remember { mutableStateOf("Enter Date of Birth") }

    // Define colors
    val customRed = Color(0xFF9E2F2B)
    val customGreen = Color(0xFF4CAF50)
    val customOrange = Color(0xFFFF9800)

    fun check() {
        if (input == expectedDob) {
            onSuccess()
        } else {
            attempts--
            input = ""
            if (attempts <= 0) {
                onFail()
            } else {
                msg = "Wrong DOB. Try again"
            }
        }
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.height(2.dp))

                Text(
                    text = msg,
                    color = if (attempts < 3) Color.Red else Color.White,
                    style = MaterialTheme.typography.caption2,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                // The DATE Boxes (8 Boxes total for MMDDYYYY)
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until 8) {
                        // Add slash separators after MM and DD
                        if (i == 2 || i == 4) {
                            Text("/", color = Color.Gray, fontSize = 12.sp)
                            Spacer(Modifier.width(2.dp))
                        }

                        val char = if (i < input.length) input[i].toString() else ""

                        Box(
                            modifier = Modifier
                                .size(width = 16.dp, height = 24.dp)
                                .border(
                                    width = 1.dp,
                                    color = if (char.isNotEmpty()) Color.White else Color.Gray,
                                    shape = RoundedCornerShape(2.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.caption2,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        if (i != 7) {
                            Spacer(Modifier.width(2.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Fixed Keypad Layout (No Scroll)
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("CLR", "0", "OK")
                )

                Column(
                    modifier = Modifier.width(150.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    keys.forEach { rowKeys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            rowKeys.forEach { key ->
                                val btnColor = when (key) {
                                    "OK" -> customGreen
                                    "CLR" -> customOrange
                                    else -> Color.Black
                                }

                                var btnModifier = Modifier.size(width = 46.dp, height = 24.dp)
                                if (key != "OK" && key != "CLR") {
                                    btnModifier = btnModifier.border(
                                        width = 1.dp,
                                        color = customRed,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }

                                Button(
                                    onClick = {
                                        if (key == "CLR") {
                                            input = ""
                                        } else if (key == "OK") {
                                            check()
                                        } else {
                                            if (input.length < 8) {
                                                input += key
                                            }
                                        }
                                    },
                                    modifier = btnModifier,
                                    colors = ButtonDefaults.buttonColors(backgroundColor = btnColor),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.body2,
                                        fontSize = 10.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}