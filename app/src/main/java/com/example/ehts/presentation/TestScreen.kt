package com.example.ehts.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.example.ehts.R
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun TestScreen(
    testCode: String,
    testDuration: Int = 60,
    onTestComplete: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            syncOfflineRecordings(context)
        }
    }
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    val hrSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    val motionSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) }
    val baselineDuration = 60

    // REMOVED: val gracePeriod = 5

    var currentTestDuration by remember { mutableIntStateOf(if (testDuration < 120) 7200 else testDuration) }

    var scenarioName by remember { mutableStateOf("Loading...") }
    var testType by remember { mutableStateOf("audio") }
    var isTestStarted by remember { mutableStateOf(false) }
    var isSensorsReady by remember { mutableStateOf(false) }
    var isTestFinished by remember { mutableStateOf(false) }
    var forceStop by remember { mutableStateOf(false) }

    var currentHeartRate by remember { mutableFloatStateOf(0f) }
    var currentMotion by remember { mutableFloatStateOf(0f) }

    var baselineHRMean by remember { mutableFloatStateOf(0f) }
    var baselineHRStdDev by remember { mutableFloatStateOf(0f) }
    var baselineHRVMean by remember { mutableFloatStateOf(0f) }
    var baselineHRVStdDev by remember { mutableFloatStateOf(0f) }
    var baselineMotionMean by remember { mutableFloatStateOf(0f) }
    var baselineMotionStdDev by remember { mutableFloatStateOf(0f) }

    var calculatedHrv by remember { mutableIntStateOf(0) }
    var stressState by remember { mutableStateOf("Ready") }
    var uploadStatus by remember { mutableStateOf("") }

    var failCount by remember { mutableIntStateOf(0) }
    var totalSamples by remember { mutableIntStateOf(0) }
    var isSignalClean by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var phaseLabel by remember { mutableStateOf("Initializing...") }

    val rrIntervals = remember { mutableListOf<Float>() }
    val baselineHRList = remember { mutableListOf<Float>() }
    val baselineHRVList = remember { mutableListOf<Float>() }
    val baselineMotionList = remember { mutableListOf<Float>() }
    val timeSeriesData = remember { mutableListOf<HashMap<String, Any>>() }

    var testDocRef by remember { mutableStateOf<DocumentReference?>(null) }
    var isBaselineCompleted by remember { mutableStateOf(false) }

    val customRed = Color(0xFF9E2F2B)

    DisposableEffect(Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "EHTS:TestScreenWakeLock"
        )
        wakeLock.acquire(125 * 60 * 1000L)
        onDispose { if (wakeLock.isHeld) wakeLock.release() }
    }

    LaunchedEffect(isTestStarted) {
        if (!isTestStarted) {
            delay(60000)
            if (!isTestStarted) onTestComplete()
        }
    }

    val perms = arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.RECORD_AUDIO)
    var hasPermissions by remember {
        mutableStateOf(perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result -> hasPermissions = result.values.all { it } }
    )

    LaunchedEffect(Unit) {
        if (!hasPermissions) permissionLauncher.launch(perms)

        val db = FirebaseFirestore.getInstance()
        db.collectionGroup("tests")
            .whereEqualTo("code", testCode)
            .get()
            .addOnSuccessListener { docs ->
                if (!docs.isEmpty) {
                    val doc = docs.documents[0]
                    testDocRef = doc.reference
                    scenarioName = doc.getString("scenario") ?: "Test"
                    testType = doc.getString("simulationType")?.lowercase() ?: "audio"

                    if (testType == "audio") {
                        val resId = getAudioResource(scenarioName)
                        if (resId != 0) {
                            val mp = MediaPlayer.create(context, resId)
                            val durationSec = (mp.duration / 1000)
                            currentTestDuration = durationSec
                            mp.release()
                        }
                    } else {
                        currentTestDuration = 3600
                    }
                }
            }
    }

    DisposableEffect(testDocRef, isSensorsReady) {
        if (testDocRef == null || !isSensorsReady) return@DisposableEffect onDispose {}
        val registration = testDocRef!!.addSnapshotListener { snapshot, e ->
            if (e == null && snapshot != null && snapshot.exists()) {
                if (snapshot.getBoolean("stopSignal") == true) forceStop = true
            }
        }
        onDispose { registration.remove() }
    }

    DisposableEffect(isSensorsReady) {
        var mediaPlayer: MediaPlayer? = null
        if (isSensorsReady && testType == "audio") {
            val audioResId = getAudioResource(scenarioName)
            if (audioResId != 0) {
                mediaPlayer = MediaPlayer.create(context, audioResId)
                mediaPlayer?.setOnCompletionListener { forceStop = true }
                mediaPlayer?.start()
            }
        }
        onDispose {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    @Suppress("DEPRECATION")
    val recorder = remember { MediaRecorder() }
    val audioFile = remember { File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "rec_${testCode}_${System.currentTimeMillis()}.m4a") }

    fun startRecording() {
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(audioFile.absolutePath)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.prepare()
            recorder.start()
            Log.d("TestScreen", "Recording started: ${audioFile.absolutePath}")
        } catch (e: IOException) { Log.e("TestScreen", "Recorder failed", e) }
    }

    fun stopRecording() {
        try {
            recorder.stop()
            recorder.reset()
            recorder.release()
        } catch (e: Exception) { Log.e("TestScreen", "Stop failed", e) }
    }

    DisposableEffect(isTestStarted, hasPermissions) {
        if (!isTestStarted) return@DisposableEffect onDispose {}

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return

                if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
                    val newHr = event.values[0]
                    if (newHr > 0) {
                        currentHeartRate = newHr
                        val rrInterval = 60000f / newHr
                        rrIntervals.add(rrInterval)
                        if (rrIntervals.size > 20) rrIntervals.removeAt(0)
                        if (rrIntervals.size > 1) {
                            var sum = 0f
                            for (i in 1 until rrIntervals.size) sum += (rrIntervals[i] - rrIntervals[i-1]).pow(2)
                            calculatedHrv = sqrt(sum / (rrIntervals.size - 1)).roundToInt()
                        }
                    }
                }

                if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    currentMotion = sqrt(x*x + y*y + z*z)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (currentHeartRate > 0) isSignalClean = accuracy != 0
            }
        }

        if (hrSensor != null) sensorManager.registerListener(sensorListener, hrSensor, SensorManager.SENSOR_DELAY_FASTEST)
        if (motionSensor != null) sensorManager.registerListener(sensorListener, motionSensor, SensorManager.SENSOR_DELAY_GAME)

        onDispose { sensorManager.unregisterListener(sensorListener) }
    }

    fun finishTest() {
        isSaving = true
        if (testType == "live") stopRecording()

        if (!isBaselineCompleted) {
            if (audioFile.exists()) audioFile.delete()
            isSaving = false
            isTestFinished = true
            return
        }

        val db = FirebaseFirestore.getInstance()

        fun saveToFirestore(audioDownloadUrl: String) {
            val failRatio = if (totalSamples > 0) failCount.toFloat() / totalSamples.toFloat() else 0f
            val result = if (failRatio > 0.25) "FAIL (Bias Detected)" else "PASS (No Bias)"
            val finalScore = (failRatio * 100).roundToInt().coerceIn(0, 100)
            val dateStr = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date())

            val updates = hashMapOf(
                "status" to "completed",
                "completedAt" to com.google.firebase.Timestamp.now(),
                "completedDate" to dateStr,
                "finalHeartRate" to currentHeartRate.roundToInt(),
                "finalHRV" to calculatedHrv,
                "baselineHRVMean" to baselineHRVMean,
                "baselineHRV_StdDev" to baselineHRVStdDev,
                "baselineHR" to baselineHRMean,
                "baselineHR_StdDev" to baselineHRStdDev,
                "stressScore" to finalScore,
                "result" to result,
                "timeSeriesData" to timeSeriesData,
                "audioUrl" to audioDownloadUrl
            )

            testDocRef?.set(updates, SetOptions.merge())?.addOnSuccessListener {
                isSaving = false
                isTestFinished = true

                if (audioFile.exists()) {
                    audioFile.delete()
                    Log.d("TestScreen", "Local audio file deleted after successful upload.")
                }
            }
        }

        if (testType == "live" && audioFile.exists() && audioFile.length() > 0) {
            // We use the testCode.m4a to keep names clean
            val storageRef = FirebaseStorage.getInstance().reference.child("recordings/${testCode}.m4a")
            val uri = Uri.fromFile(audioFile)
            val metadata = StorageMetadata.Builder().setContentType("audio/mp4").build()

            storageRef.putFile(uri, metadata)
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                        saveToFirestore(downloadUri.toString())
                    }
                }
                .addOnFailureListener {
                    saveToFirestore("SYNC_PENDING")
                }
        } else {
            saveToFirestore("")
        }
    }

    suspend fun syncOfflineRecordings(context: Context) {
        val db = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()

        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return
        val orphanedFiles = musicDir.listFiles { file ->
            file.extension == "m4a" && file.name.startsWith("rec_")
        } ?: return

        if (orphanedFiles.isEmpty()) return
        Log.d("SyncEngine", "Found ${orphanedFiles.size} offline recordings.")

        for (file in orphanedFiles) {
            try {
                val parts = file.name.split("_")
                if (parts.size >= 2) {
                    val extractedCode = parts[1]

                    val storageRef = storage.reference.child("recordings/${extractedCode}.m4a")
                    val uri = Uri.fromFile(file)
                    val metadata = StorageMetadata.Builder().setContentType("audio/mp4").build()

                    storageRef.putFile(uri, metadata).await()
                    val downloadUri = storageRef.downloadUrl.await()

                    val querySnapshot = db.collectionGroup("tests")
                        .whereEqualTo("code", extractedCode)
                        .get()
                        .await()

                    if (!querySnapshot.isEmpty) {
                        val docRef = querySnapshot.documents[0].reference
                        docRef.update("audioUrl", downloadUri.toString()).await()
                    }

                    file.delete() // Clean up watch storage
                    Log.d("SyncEngine", "Synced and cleaned up: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e("SyncEngine", "Sync failed for ${file.name}", e)
            }
        }
    }


    LaunchedEffect(isTestStarted) {
        if (isTestStarted) {
            phaseLabel = "Acquiring Signal..."
            stressState = "Please Wait..."

            while (currentHeartRate <= 0f || calculatedHrv == 0) {
                delay(500)
            }

            isSensorsReady = true
            val toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

            if (testType == "live") startRecording()

            var elapsed = 0
            while (elapsed < currentTestDuration && !forceStop) {
                delay(1000)
                elapsed++

                if (elapsed <= baselineDuration) {
                    phaseLabel = "Baseline Collection (${baselineDuration - elapsed}s)"
                    stressState = "Calibrating..."
                    baselineHRList.add(currentHeartRate)
                    baselineHRVList.add(calculatedHrv.toFloat())
                    baselineMotionList.add(currentMotion)

                    if (elapsed == baselineDuration) {
                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP)
                        isBaselineCompleted = true

                        // HR Baseline
                        baselineHRMean = baselineHRList.average().toFloat()
                        val hrSumSq = baselineHRList.fold(0.0) { acc, num -> acc + (num - baselineHRMean).pow(2) }
                        baselineHRStdDev = sqrt(hrSumSq / baselineHRList.size).toFloat().coerceAtLeast(3.0f)

                        baselineHRVMean = baselineHRVList.average().toFloat()
                        val hrvSumSq = baselineHRVList.fold(0.0) { acc, num -> acc + (num - baselineHRVMean).pow(2) }
                        baselineHRVStdDev = sqrt(hrvSumSq / baselineHRVList.size).toFloat().coerceAtLeast(3.0f)

                        baselineMotionMean = baselineMotionList.average().toFloat()
                        val motionSumSq = baselineMotionList.fold(0.0) { acc, num -> acc + (num - baselineMotionMean).pow(2) }
                        baselineMotionStdDev = sqrt(motionSumSq / baselineMotionList.size).toFloat().coerceAtLeast(1.0f)
                    }
                } else {
                    phaseLabel = if (testType == "live") "Live Scenario" else "Active Audio"

                    val hrJumpZ = (currentHeartRate - baselineHRMean) / baselineHRStdDev
                    val hrvDropZ = (baselineHRVMean - calculatedHrv) / baselineHRVStdDev
                    val motionZ = (currentMotion - baselineMotionMean) / baselineMotionStdDev

                    val activeMotionZ = if (testType == "audio") motionZ.coerceIn(0f, 4.0f) else 0f

                    val totalStressVolume = hrJumpZ.coerceAtLeast(0f) +
                            (1.5f * hrvDropZ.coerceAtLeast(0f)) +
                            (0.5f * activeMotionZ)

                    val isStress = totalStressVolume >= 4.0f

                    if (isStress) {
                        stressState = "STRESS DETECTED"
                        failCount++
                    } else {
                        stressState = "Stable"
                    }
                    totalSamples++

                    timeSeriesData.add(hashMapOf<String, Any>(
                        "time" to elapsed,
                        "heart_rate_0_to_200" to currentHeartRate.roundToInt(),
                        "hrv" to calculatedHrv,
                        "motion" to if(testType == "live") 0f else currentMotion,
                        "stress" to isStress,
                        "stress_score_0_to_10" to totalStressVolume,
                        "threshold_line_2.0" to 4.0f,
                        "stressIndicator" to (if(isStress) totalStressVolume else -1f)
                    ))
                }

                if (elapsed <= baselineDuration) {
                    timeSeriesData.add(hashMapOf<String, Any>(
                        "time" to elapsed,
                        "heart_rate_0_to_200" to currentHeartRate.roundToInt(),
                        "hrv" to calculatedHrv,
                        "motion" to if(testType == "live") 0f else currentMotion,
                        "stress" to false,
                        "stress_score_0_to_10" to 0f,
                        "threshold_line_2.0" to 4.0f,
                        "stressIndicator" to -1f
                    ))
                }
            }
            toneGenerator.release()
            finishTest()
        }
    }

    LaunchedEffect(isTestFinished) { if (isTestFinished) { delay(4000); onTestComplete() } }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (isTestFinished) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Test Complete", color = Color(0xFF00C853), style = MaterialTheme.typography.title2)
                    if (testType == "live") Text("Audio Uploaded", color = Color.Cyan, fontSize = 10.sp)
                    Text("Data Saved", color = Color.Gray, style = MaterialTheme.typography.body2)
                }
            } else if (!isTestStarted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
                    if (hasPermissions) {
                        Text("READY", color = Color(0xFF00C853), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("Mode: ${testType.uppercase()}", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { if(hasPermissions) isTestStarted = true }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.White), modifier = Modifier.height(40.dp).fillMaxWidth(0.9f)) {
                            Text("START TEST", color = Color.Black)
                        }
                        Text ("At the sound of the beep, the audio or scenario will begin.", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                    } else {
                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }, colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFCF6679)), modifier = Modifier.height(40.dp)) {
                            Text("GRANT SENSORS", color = Color.Black, fontSize = 10.sp)
                        }
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    if (!isSensorsReady) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), indicatorColor = Color(0xFF29B6F6))
                        Text("Acquiring Signal...", color = Color.LightGray, fontSize = 12.sp)
                    } else {
                        Text(phaseLabel, color = Color.LightGray, fontSize = 9.sp)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = if(currentHeartRate > 0) "${currentHeartRate.toInt()}" else "--", color = Color.White, style = MaterialTheme.typography.display3, fontSize = 32.sp, modifier = Modifier.offset(y = 2.dp))
                            Text("BPM", color = Color.Gray, fontSize = 14.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),horizontalArrangement = Arrangement.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if(calculatedHrv > 0) "${calculatedHrv}ms" else "--", color = Color(0xFFFFAB40), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("HRV", color = Color.Gray, fontSize = 12.sp)
                            }
                            if (testType == "audio") {
                                Spacer(Modifier.width(32.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(String.format(Locale.US, "%.1f", currentMotion), color = Color(0xFFAB47BC), fontWeight = FontWeight.Bold,fontSize = 14.sp)
                                    Text("MOVT", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                        if (testType == "live") {
                            Button(onClick = { forceStop = true }, colors = ButtonDefaults.buttonColors(backgroundColor = customRed), modifier = Modifier.height(30.dp).width(100.dp)) {
                                Text("STOP TEST", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun getAudioResource(scenarioName: String): Int {
    val name = scenarioName.lowercase()
    return when {
        name.contains("women") -> R.raw.gender_w
        name.contains("male") -> R.raw.gender_m
        name.contains("white") -> R.raw.race_w
        name.contains("black") -> R.raw.race_b
        name.contains("christianity") -> R.raw.faith_c
        name.contains("judaism") -> R.raw.faith_j
        name.contains("islam") -> R.raw.faith_i
        else -> 0
    }
}

suspend fun syncOfflineRecordings(context: Context) {
    val db = FirebaseFirestore.getInstance()
    val storage = FirebaseStorage.getInstance()

    val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return
    val orphanedFiles = musicDir.listFiles { file ->
        file.extension == "m4a" && file.name.startsWith("rec_")
    } ?: return

    if (orphanedFiles.isEmpty()) return
    Log.d("SyncEngine", "Found ${orphanedFiles.size} offline recordings. Starting sync...")

    for (file in orphanedFiles) {
        try {
            val parts = file.name.split("_")
            if (parts.size >= 2) {
                val extractedCode = parts[1]

                val storageRef = storage.reference.child("recordings/${file.name}")
                val uri = Uri.fromFile(file)
                val metadata = StorageMetadata.Builder().setContentType("audio/mp4").build()

                storageRef.putFile(uri, metadata).await()
                val downloadUri = storageRef.downloadUrl.await()

                val querySnapshot = db.collectionGroup("tests")
                    .whereEqualTo("code", extractedCode)
                    .get()
                    .await()

                if (!querySnapshot.isEmpty) {
                    val docRef = querySnapshot.documents[0].reference
                    docRef.update("audioUrl", downloadUri.toString()).await()
                }

                file.delete()
                Log.d("SyncEngine", "Successfully synced & deleted: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e("SyncEngine", "Sync failed for ${file.name}. Will retry later.", e)
        }
    }
}
