package com.example.ehts.presentation

import android.util.Log
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot

@Composable
fun LoginScreen(
    onLoginSuccess: (String, String) -> Unit
) {
    var inputCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Enter Access Code") }
    var isLoading by remember { mutableStateOf(false) }

    // Define colors
    val customRed = Color(0xFF9E2F2B)
    val customGreen = Color(0xFF4CAF50)
    val customOrange = Color(0xFFFF9800)

    fun verifyCode() {
        if (inputCode.length != 6) {
            message = "Code incomplete"
            return
        }
        isLoading = true
        message = "Connecting..." // Tell the user we are connecting to auth first

        val cleanCode = inputCode.trim()
        Log.d("LoginDebug", "Attempting to log in anonymously...")

        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()

        // --- NEW: LOG IN ANONYMOUSLY FIRST ---
        auth.signInAnonymously()
            .addOnSuccessListener { authResult ->
                Log.d("LoginDebug", "Auth Success! UID: ${authResult.user?.uid}")
                message = "Verifying Code..."

                // ALL OF YOUR EXISTING LOGIC GOES INSIDE THIS SUCCESS LISTENER
                fun handleResult(documents: QuerySnapshot, type: String) {
                    val doc = documents.documents[0]
                    val dob = doc.getString("dob") ?: ""
                    val status = doc.getString("status")


                    Log.d("LoginDebug", "Success! Found via $type. Doc ID: ${doc.id}. Status: $status")

                    if (status == "completed") {
                        message = "Test Completed"
                        isLoading = false
                    } else if (dob.isEmpty()) {
                        message = "No DOB Found"
                        isLoading = false
                    } else {
                        onLoginSuccess(cleanCode, dob)
                    }
                }

                fun runDiagnosticProbe() {
                    db.collectionGroup("tests")
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .limit(50)
                        .get()
                        .addOnSuccessListener { probeDocs ->
                            if (!probeDocs.isEmpty) {
                                Log.d("LoginDebug", "Diagnostic: Found ${probeDocs.size()} tests in the DB.")
                                message = "Code Not Found"
                            } else {
                                Log.d("LoginDebug", "Diagnostic: DB is completely EMPTY. Project Mismatch.")
                                message = "DB Empty"
                            }
                            isLoading = false
                        }
                        .addOnFailureListener { e ->
                            Log.e("LoginDebug", "Diagnostic Probe Failed", e)
                            message = "login Failed"
                            isLoading = false
                        }
                }

                // 1. Search as STRING
                db.collectionGroup("tests")
                    .whereEqualTo("code", cleanCode)
                    .get()
                    .addOnSuccessListener { documents ->
                        if (!documents.isEmpty) {
                            handleResult(documents, "String")
                        } else {
                            Log.d("LoginDebug", "No String match. Trying Number...")

                            // 2. Search as NUMBER
                            val codeAsInt = cleanCode.toIntOrNull()
                            if (codeAsInt != null) {
                                db.collectionGroup("tests")
                                    .whereEqualTo("code", codeAsInt)
                                    .get()
                                    .addOnSuccessListener { docsInt ->
                                        if (!docsInt.isEmpty) {
                                            handleResult(docsInt, "Number")
                                        } else {
                                            Log.d("LoginDebug", "No match found. Running Path Diagnostic...")
                                            runDiagnosticProbe()
                                        }
                                    }
                                    .addOnFailureListener { runDiagnosticProbe() }
                            } else {
                                runDiagnosticProbe()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        isLoading = false
                        Log.e("LoginDebug", "Query Failed", e)
                        message = "Network Error"
                    }
            }
            .addOnFailureListener { e ->
                // THIS CATCHES IF THE WATCH CANNOT GET AN ID BADGE
                isLoading = false
                Log.e("LoginDebug", "Anonymous Auth Failed", e)
                message = "Auth Failed"
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Spacer(Modifier.height(2.dp))

                Text(
                    text = message,
                    color = if (message.contains("Invalid") || message.contains("incomplete") || message.contains("Error") || message.contains("Need") || message.contains("Check") || message.contains("Fail") || message.contains("Not Found") || message.contains("Empty")) Color.Red else Color.White,
                    style = MaterialTheme.typography.caption2,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                // Input Boxes Display
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until 6) {
                        if (i == 3) Spacer(modifier = Modifier.width(4.dp))

                        val char = if (i < inputCode.length) inputCode[i].toString() else ""

                        Box(
                            modifier = Modifier
                                .size(width = 20.dp, height = 26.dp)
                                .border(
                                    width = 1.dp,
                                    color = if (char.isNotEmpty()) Color.White else Color.Gray,
                                    shape = RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.body2,
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }

                        if (i != 2 && i != 5) Spacer(modifier = Modifier.width(2.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), indicatorColor = customRed)
                } else {
                    // Fixed Keypad Layout
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
                                                inputCode = ""
                                            } else if (key == "OK") {
                                                verifyCode()
                                            } else {
                                                if (inputCode.length < 6) {
                                                    inputCode += key
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
}