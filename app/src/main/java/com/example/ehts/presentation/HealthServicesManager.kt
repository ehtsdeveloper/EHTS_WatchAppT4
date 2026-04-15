package com.example.ehts.presentation

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class BiometricData(val heartRate: Double)

class HealthServicesManager(context: Context) {
    private val measureClient = HealthServices.getClient(context).measureClient

    fun biometricFlow(): Flow<BiometricData> = callbackFlow {
        val callback = object : MeasureCallback {
            override fun onDataReceived(data: DataPointContainer) {
                val hrList = data.getData(DataType.HEART_RATE_BPM)
                val currentHr = hrList.lastOrNull()?.value

                if (currentHr != null) {
                    trySend(BiometricData(currentHr))
                }
            }

            override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) {
                // This runs if the user takes the watch off their wrist.
                // For now, we ignore it, but later we could pause the test here.
            }
        }

        Log.d("Health", "Registering Sensor...")

        try {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        } catch (e: Exception) {
            Log.e("Health", "Sensor Error", e)
        }
        awaitClose {
            Log.d("Health", "Unregistering Sensor...")
            measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
        }
    }
}