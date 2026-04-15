package com.example.ehts.presentation



import android.Manifest

import android.os.Bundle

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.runtime.*

import androidx.wear.compose.material.MaterialTheme



// --- Screen Enum ---

enum class Screen { LOGIN, VERIFICATION, TEST }



class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)



// Request necessary permissions

        val requestPermissionLauncher = registerForActivityResult(

            ActivityResultContracts.RequestMultiplePermissions()

        ) { }



        requestPermissionLauncher.launch(

            arrayOf(

                Manifest.permission.BODY_SENSORS,

                Manifest.permission.WAKE_LOCK,

                Manifest.permission.INTERNET

            )

        )



        setContent {

// --- Navigation State ---

            var currentScreen by remember { mutableStateOf(Screen.LOGIN) }



// --- Data persistence between screens ---

            var activeCode by remember { mutableStateOf("") }

            var activeDob by remember { mutableStateOf("") }



            MaterialTheme {

                when (currentScreen) {

                    Screen.LOGIN -> {

                        LoginScreen(

                            onLoginSuccess = { code, dob ->

                                activeCode = code

                                activeDob = dob

                                currentScreen = Screen.VERIFICATION

                            }

                        )

                    }



                    Screen.VERIFICATION -> {

                        DobVerificationScreen(

                            expectedDob = activeDob,

                            onSuccess = {

                                currentScreen = Screen.TEST

                            },

                            onFail = {

                                currentScreen = Screen.LOGIN

                            }

                        )

                    }



                    Screen.TEST -> {

                        TestScreen(

                            testCode = activeCode,

                            testDuration = 60, // 60 seconds

                            onTestComplete = {

                                currentScreen = Screen.LOGIN

                            }

                        )

                    }

                }

            }

        }

    }

}