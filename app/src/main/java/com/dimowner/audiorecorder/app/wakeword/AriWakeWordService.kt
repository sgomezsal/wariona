package com.dimowner.audiorecorder.app.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.RecordingService
import com.dimowner.audiorecorder.app.main.MainActivityMinimal
import com.dimowner.audiorecorder.util.AndroidUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager

class AriWakeWordService : Service() {

    companion object {
        private const val TAG = "AriWakeWordService"
        private const val NOTIFICATION_ID = 402
        private const val CHANNEL_ID = "wake_word_channel"
        private const val KEYWORD_ASSET_PATH = "porcupine/Hey-Ari_en_android_v3_0_0.ppn"
        // Model base path for English language, compatible with Android ARM64 v3.0.0
        // The SDK will download this automatically if not provided, but specifying it ensures compatibility
        private const val MODEL_BASE_PATH = "porcupine/porcupine_params_en.pv"
        private const val ACCESS_KEY = "8FlqmodLJoMmRQfc19sMBEkqhEIt6Ga1/5gjqhSHsFp05e+belRy0Q=="
        private const val PERMANENT_WAKELOCK_TAG = "Wariona:WakeWordPartial"
        private const val PULSE_WAKELOCK_TAG = "Wariona:WakeWordPulse"
        private const val WAKELOCK_TIMEOUT_MS = 3_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var porcupineManager: PorcupineManager? = null
    private var isListening = false
    private var isRecording = false
    private var partialWakeLock: PowerManager.WakeLock? = null

    private lateinit var notificationManager: NotificationManager

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_RECORDING_STARTED_BROADCAST -> {
                    Timber.d("Recording started - pausing wake word detection")
                    isRecording = true
                    pauseListening()
                }
                RecordingService.ACTION_RECORDING_STOPPED_BROADCAST -> {
                    Timber.d("Recording stopped - resuming wake word detection")
                    isRecording = false
                    resumeListening()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.wake_word_listening_status)))
        
        // Register BroadcastReceiver to listen for recording state changes
        // IMPORTANT: Android 12+ (API 31) requires specifying RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
        // We use RECEIVER_NOT_EXPORTED because this receiver is only for internal app communication
        // and should not be accessible by other apps for security reasons.
        val intentFilter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED_BROADCAST)
            addAction(RecordingService.ACTION_RECORDING_STOPPED_BROADCAST)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+): Must specify RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
            // We use RECEIVER_NOT_EXPORTED because this receiver only handles internal broadcasts
            // from our own RecordingService, not from external apps.
            registerReceiver(recordingStateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // Android 11 and below: Old API without flags (backward compatibility)
            // The flag parameter doesn't exist in older APIs, so we use the old method signature
            registerReceiver(recordingStateReceiver, intentFilter)
        }
        
        acquirePartialWakeLock()
        serviceScope.launch { initializePorcupineAndStart() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(recordingStateReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Recording state receiver already unregistered")
        }
        releasePartialWakeLock()
        stopPorcupine()
        serviceScope.cancel()
        porcupineManager?.delete()
        porcupineManager = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Initializes PorcupineManager with the English keyword file and model base.
     * 
     * IMPORTANT: The keyword file (Hey-Ari_en_android_v3_0_0.ppn) is in English,
     * so we need to ensure the model base is also English and compatible with Android ARM64 v3.0.0.
     * 
     * The SDK will automatically download the model base if not provided, but specifying it
     * explicitly ensures compatibility and prevents INVALID_ARGUMENT errors.
     */
    private suspend fun initializePorcupineAndStart() {
        withContext(Dispatchers.IO) {
            try {
                // Prepare keyword file: copy from assets to filesDir if needed
                val keywordFilePath = prepareKeywordFile()
                Log.i(TAG, "Using keyword file at path: $keywordFilePath")
                
                // Prepare model base file if available in assets (optional but recommended for compatibility)
                // If the model base is not in assets, PorcupineManager will download it automatically
                val modelBasePath = try {
                    prepareModelBaseFile()
                } catch (e: Exception) {
                    // Model base not in assets - SDK will download it automatically
                    Timber.d("Model base not found in assets, SDK will download automatically")
                    null
                }
                
                // Build PorcupineManager with English keyword file
                // IMPORTANT: The keyword file name contains "_en_" indicating it's English,
                // so PorcupineManager will automatically detect the language and download
                // the corresponding English model base compatible with Android ARM64 v3.0.0.
                // 
                // The SDK automatically handles model base selection based on:
                // 1. The language detected from the keyword file name (en = English)
                // 2. The platform architecture (Android ARM64)
                // 3. The SDK version (v3.0.0 compatibility)
                val builder = PorcupineManager.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(keywordFilePath)
                
                // Try to set model base path if available (optional - SDK will download automatically if not provided)
                // This ensures we use a specific English model version compatible with Android ARM64 v3.0.0
                if (modelBasePath != null) {
                    try {
                        // Use reflection to check if setModelPath() method exists
                        val setModelPathMethod = builder.javaClass.getMethod("setModelPath", String::class.java)
                        setModelPathMethod.invoke(builder, modelBasePath)
                        Log.i(TAG, "Using English model base at path: $modelBasePath")
                    } catch (e: NoSuchMethodException) {
                        // Method not available in this SDK version - SDK will handle it automatically
                        Log.i(TAG, "setModelPath() not available in this SDK version, SDK will auto-detect English model base")
                    } catch (e: Exception) {
                        Timber.w(e, "Error setting model path via reflection, SDK will auto-detect")
                        Log.w(TAG, "Error setting model path: ${e.message}, SDK will auto-detect English model base")
                    }
                } else {
                    Log.i(TAG, "Model base not in assets - SDK will automatically download English model base for Android ARM64")
                }
                
                porcupineManager = builder.build(applicationContext) {
                    handleWakeWordDetected()
                }
                
                Log.i(TAG, "Porcupine initialized successfully with English keyword and model base")
                Timber.i("Porcupine initialized successfully with custom wake word")
                startPorcupine()
            } catch (throwable: Throwable) {
                when (throwable) {
                    is PorcupineException -> {
                        Timber.e(throwable, "Failed to initialize Porcupine wake word detection")
                        Log.e(TAG, "PorcupineException: ${throwable.message}", throwable)
                        notifyFailure()
                        stopSelf()
                    }
                    is IOException -> {
                        Timber.e(throwable, "IO error initializing Porcupine")
                        Log.e(TAG, "IOException: ${throwable.message}", throwable)
                        notifyFailure()
                        stopSelf()
                    }
                    else -> {
                        Timber.e(throwable, "Unexpected error initializing Porcupine")
                        Log.e(TAG, "Unexpected error: ${throwable.message}", throwable)
                        throw throwable
                    }
                }
            }
        }
    }

    private fun startPorcupine() {
        if (isRecording) {
            Timber.d("Skipping Porcupine start because recording is active")
            return
        }
        serviceScope.launch {
            try {
                porcupineManager?.let { manager ->
                    if (!isListening) {
                        manager.start()
                        isListening = true
                        updateNotification(getString(R.string.wake_word_listening_status))
                        Timber.d("Porcupine listening started")
                    }
                }
            } catch (e: PorcupineException) {
                Timber.e(e, "Unable to start Porcupine listening")
            }
        }
    }

    /**
     * Stops Porcupine synchronously to ensure microphone is released before starting recording.
     * This prevents conflicts when both Porcupine and MediaRecorder try to access the microphone.
     */
    private suspend fun stopPorcupineSync(): Boolean = withContext(Dispatchers.Default) {
        try {
            porcupineManager?.let { manager ->
                if (isListening) {
                    manager.stop()
                    isListening = false
                    updateNotification(getString(R.string.wake_word_paused_status))
                    Timber.d("Porcupine listening stopped synchronously")
                    // Small delay to ensure microphone is fully released
                    delay(100)
                    return@withContext true
                }
            }
            return@withContext false
        } catch (e: PorcupineException) {
            Timber.e(e, "Unable to stop Porcupine listening")
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error stopping Porcupine")
            return@withContext false
        }
    }

    /**
     * Stops Porcupine asynchronously (for non-critical operations).
     */
    private fun stopPorcupine() {
        serviceScope.launch {
            stopPorcupineSync()
        }
    }

    /**
     * Pauses listening synchronously to ensure microphone is released.
     */
    private suspend fun pauseListeningSync() {
        stopPorcupineSync()
    }

    /**
     * Pauses listening asynchronously (for non-critical operations).
     */
    private fun pauseListening() {
        serviceScope.launch {
            pauseListeningSync()
        }
    }

    private fun resumeListening() {
        if (porcupineManager == null) {
            serviceScope.launch { initializePorcupineAndStart() }
        } else {
            startPorcupine()
        }
    }

    /**
     * Handles wake word detection. This method:
     * 1. Stops Porcupine synchronously to release the microphone
     * 2. Waits a short delay to ensure microphone is fully released
     * 3. Triggers recording only if microphone is available
     * 4. Includes comprehensive error handling to prevent crashes
     */
    private fun handleWakeWordDetected() {
        Timber.i("Wake word detected - preparing to start recording")
        
        // Execute in coroutine to handle async operations safely
        serviceScope.launch {
            try {
                // Step 1: Stop Porcupine synchronously to release microphone
                val porcupineStopped = stopPorcupineSync()
                if (!porcupineStopped) {
                    Timber.w("Porcupine may not have stopped properly, but continuing anyway")
                }
                
                // Step 2: Additional delay to ensure microphone is fully released
                // This prevents conflicts when MediaRecorder tries to access the microphone
                delay(200)
                
                // Step 3: Check if recording is already active (double-check)
                if (isRecording) {
                    Timber.w("Recording already active; ignoring wake word trigger")
                    return@launch
                }
                
                // Step 4: Verify permissions before attempting to start recording
                if (!hasRecordAudioPermission()) {
                    Timber.e("RECORD_AUDIO permission not granted - cannot start recording")
                    AndroidUtils.runOnUIThread {
                        Toast.makeText(
                            applicationContext,
                            "Microphone permission required",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // Resume listening if permission is missing
                    resumeListening()
                    return@launch
                }
                
                // Step 5: Update UI and play confirmation
                pulseWakeLock()
                playConfirmationBeep()
                AndroidUtils.runOnUIThread {
                    updateNotification(getString(R.string.wake_word_detected_status))
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.wake_word_detected_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                // Step 6: Trigger recording with error handling
                triggerRecording()
                
            } catch (e: Exception) {
                Timber.e(e, "Error in handleWakeWordDetected - resuming wake word detection")
                // On error, resume listening to keep the service functional
                resumeListening()
                AndroidUtils.runOnUIThread {
                    Toast.makeText(
                        applicationContext,
                        "Error starting recording: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Checks if RECORD_AUDIO permission is granted.
     */
    private fun hasRecordAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission granted by default on older Android versions
        }
    }

    /**
     * Triggers recording by starting RecordingService.
     * Includes error handling to prevent crashes.
     */
    private fun triggerRecording() {
        try {
            if (isRecording) {
                Timber.d("Recording already active; ignoring wake word trigger")
                return
            }
            
            val startIntent = Intent(applicationContext, RecordingService::class.java)
                .setAction(RecordingService.ACTION_TOGGLE_RECORDING)
            
            Timber.d("Starting RecordingService via wake word")
            ContextCompat.startForegroundService(applicationContext, startIntent)
            
        } catch (e: IllegalStateException) {
            // Handle case where service cannot be started (e.g., app in background on Android 8+)
            Timber.e(e, "Failed to start RecordingService - IllegalStateException")
            AndroidUtils.runOnUIThread {
                Toast.makeText(
                    applicationContext,
                    "Cannot start recording: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // Resume listening on error
            resumeListening()
        } catch (e: SecurityException) {
            // Handle permission issues
            Timber.e(e, "Failed to start RecordingService - SecurityException (permission denied)")
            AndroidUtils.runOnUIThread {
                Toast.makeText(
                    applicationContext,
                    "Microphone permission denied",
                    Toast.LENGTH_LONG
                ).show()
            }
            resumeListening()
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error starting RecordingService")
            AndroidUtils.runOnUIThread {
                Toast.makeText(
                    applicationContext,
                    "Error starting recording",
                    Toast.LENGTH_SHORT
                ).show()
            }
            resumeListening()
        }
    }

    private fun playConfirmationBeep() {
        serviceScope.launch {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                delay(200)
            } finally {
                toneGenerator.release()
            }
        }
    }

    /**
     * Prepares the model base file (porcupine_params_en.pv) by copying it from assets if available.
     * This ensures compatibility with the English keyword file and Android ARM64 v3.0.0.
     * 
     * NOTE: If the model base is not in assets, PorcupineManager will download it automatically.
     * This function is optional but recommended to ensure version compatibility.
     * 
     * @return The absolute path to the model base file, or null if not found in assets
     * @throws IOException if the file cannot be copied (but returns null if file doesn't exist in assets)
     */
    private suspend fun prepareModelBaseFile(): String? = withContext(Dispatchers.IO) {
        val modelFileName = MODEL_BASE_PATH.substringAfterLast('/')
        val outputDir = File(filesDir, "porcupine")
        
        // Ensure directory exists
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        
        val outputFile = File(outputDir, modelFileName)
        val outputPath = outputFile.absolutePath
        
        // Check if file already exists
        if (outputFile.exists() && outputFile.length() > 0) {
            Log.i(TAG, "Model base file already exists at: $outputPath (size: ${outputFile.length()} bytes)")
            return@withContext outputPath
        }
        
        // Try to copy from assets
        try {
            // Check if model base exists in assets
            val assetManager = assets
            val assetList = assetManager.list("porcupine")
            if (assetList == null || !assetList.contains(modelFileName)) {
                Log.i(TAG, "Model base file not found in assets, SDK will download automatically")
                return@withContext null
            }
            
            Log.i(TAG, "Copying model base file from assets/$MODEL_BASE_PATH to: $outputPath")
            assets.open(MODEL_BASE_PATH).use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    outputStream.flush()
                    try {
                        outputStream.fd?.sync()
                    } catch (e: Exception) {
                        Timber.d("FileDescriptor.sync() not available for model base")
                    }
                }
            }
            
            // Verify file was copied
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "Model base file copied successfully to: $outputPath (size: ${outputFile.length()} bytes)")
                outputPath
            } else {
                Log.w(TAG, "Model base file copy failed, SDK will download automatically")
                null
            }
        } catch (e: IOException) {
            // File not found in assets - this is OK, SDK will download it
            Log.i(TAG, "Model base file not in assets (${e.message}), SDK will download automatically")
            null
        } catch (e: Exception) {
            Timber.w(e, "Error preparing model base file")
            Log.w(TAG, "Error preparing model base: ${e.message}, SDK will download automatically")
            null
        }
    }

    /**
     * Prepares the keyword file by copying it from assets to the internal files directory.
     * This ensures Porcupine can access the .ppn file from a valid filesystem path.
     * 
     * Steps:
     * 1. Creates porcupine directory in filesDir if it doesn't exist
     * 2. Checks if the file already exists (avoids unnecessary copy)
     * 3. Copies the file from assets/porcupine/ to filesDir/porcupine/
     * 4. Verifies the file was copied successfully
     * 5. Returns the absolute path to the file
     * 
     * @return The absolute path to the keyword file in the files directory
     * @throws IOException if the file cannot be copied or accessed
     */
    private suspend fun prepareKeywordFile(): String = withContext(Dispatchers.IO) {
        val keywordFileName = KEYWORD_ASSET_PATH.substringAfterLast('/')
        val outputDir = File(filesDir, "porcupine")
        
        // Create porcupine directory if it doesn't exist
        if (!outputDir.exists()) {
            val created = outputDir.mkdirs()
            Log.i(TAG, "Created porcupine directory: $created at ${outputDir.absolutePath}")
        }
        
        val outputFile = File(outputDir, keywordFileName)
        val outputPath = outputFile.absolutePath
        
        // Check if file already exists and has content
        if (outputFile.exists() && outputFile.length() > 0) {
            Log.i(TAG, "Keyword file already exists at: $outputPath (size: ${outputFile.length()} bytes)")
            return@withContext outputPath
        }
        
        // File doesn't exist or is empty, copy from assets
        Log.i(TAG, "Copying keyword file from assets/$KEYWORD_ASSET_PATH to: $outputPath")
        try {
            // Open asset file
            assets.open(KEYWORD_ASSET_PATH).use { inputStream ->
                // Create output file
                FileOutputStream(outputFile).use { outputStream ->
                    // Copy data
                    inputStream.copyTo(outputStream)
                    outputStream.flush()
                    // Force sync to ensure data is written to disk (important for Android 12+)
                    try {
                        outputStream.fd?.sync()
                    } catch (e: Exception) {
                        Timber.d("FileDescriptor.sync() not available, using flush only")
                    }
                }
            }
            
            // Verify file was copied successfully
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "Keyword file copied successfully to: $outputPath (size: ${outputFile.length()} bytes)")
            } else {
                throw IOException("Keyword file copy failed: file is empty or doesn't exist after copy")
            }
            
            outputPath
        } catch (e: IOException) {
            Timber.e(e, "Failed to copy keyword file from assets")
            Log.e(TAG, "Failed to copy keyword file: ${e.message}", e)
            // Clean up partial file if it exists
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw IOException("Cannot prepare keyword file: ${e.message}", e)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error preparing keyword file")
            Log.e(TAG, "Unexpected error preparing keyword file: ${e.message}", e)
            // Clean up partial file if it exists
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw IOException("Unexpected error preparing keyword file: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.wake_word_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.setShowBadge(false)
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivityMinimal::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_record_rec)
            .setContentTitle(getString(R.string.wake_word_service_title))
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun acquirePartialWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        partialWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, PERMANENT_WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            if (!isHeld) {
                acquire()
                Timber.d("Partial wake lock acquired for wake word service")
            }
        }
    }

    private fun releasePartialWakeLock() {
        partialWakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Timber.d("Partial wake lock released")
            }
        }
        partialWakeLock = null
    }

    private fun pulseWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                PULSE_WAKELOCK_TAG
            )
        } else {
            powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                PULSE_WAKELOCK_TAG
            )
        }
        try {
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to acquire temporary wake lock")
        }
    }

    private fun notifyFailure() {
        AndroidUtils.runOnUIThread {
            Toast.makeText(applicationContext, getString(R.string.wake_word_initialization_error), Toast.LENGTH_LONG).show()
        }
    }
}
