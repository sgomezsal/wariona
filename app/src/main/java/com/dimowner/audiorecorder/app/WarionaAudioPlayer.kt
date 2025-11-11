/*
 * Copyright 2024
 * Wariona Audio Player
 * Kotlin coroutines-based audio response handler for Wariona backend
 */

package com.dimowner.audiorecorder.app

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Callback interface for handling playback completion and conversation continuation.
 */
interface WarionaPlaybackCallback {
    /**
     * Called when audio playback completes.
     * @param shouldContinue true if the backend signaled to continue the conversation (X-Continue-Conversation: true)
     */
    fun onPlaybackCompleted(shouldContinue: Boolean)
}

/**
 * Kotlin-based audio player for Wariona backend responses.
 * Uses coroutines for async file I/O operations.
 */
object WarionaAudioPlayer {
    
    private const val TAG = "Wariona"
    private const val API_URL = "http://65.21.54.108:8000/talk"
    private const val HEADER_TRANSCRIPTION = "X-Transcription"
    private const val HEADER_RESPONSE_TEXT = "X-Response-Text"
    private const val HEADER_CONTINUE_CONVERSATION = "X-Continue-Conversation"
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var currentPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null
    private var playbackCallback: WarionaPlaybackCallback? = null
    private var shouldContinueConversation: Boolean = false
    
    /**
     * Set callback for playback completion events.
     */
    fun setPlaybackCallback(callback: WarionaPlaybackCallback?) {
        playbackCallback = callback
    }
    
    /**
     * Send audio file to Wariona backend and play the response.
     * Uses Kotlin coroutines for async operations.
     * Can be called from Java code using CoroutineScope.launch.
     */
    suspend fun sendAndPlayAudio(context: Context, audioFile: File) = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists() || !audioFile.canRead()) {
                Log.e(TAG, "Audio file not found or cannot be read: ${audioFile.absolutePath}")
                return@withContext
            }
            
            Log.d(TAG, "Sending audio to Wariona: ${audioFile.name} (${audioFile.length()} bytes)")
            
            // Create multipart request
            val fileBody = audioFile.asRequestBody("audio/*".toMediaType())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, fileBody)
                .build()
            
            val request = Request.Builder()
                .url(API_URL)
                .post(requestBody)
                .build()
            
            // Execute request
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Request failed with status ${response.code}: $errorBody")
                return@withContext
            }
            
            // Extract and log headers
            val transcription = response.header(HEADER_TRANSCRIPTION)
            val responseText = response.header(HEADER_RESPONSE_TEXT)
            
            // Check for conversation continuation header
            val continueHeader = response.header(HEADER_CONTINUE_CONVERSATION)
            shouldContinueConversation = continueHeader?.equals("true", ignoreCase = true) == true
            
            // Safely handle nullable header values
            if (!transcription.isNullOrEmpty()) {
                Log.d(TAG, "X-Transcription: $transcription")
            }
            if (!responseText.isNullOrEmpty()) {
                Log.d(TAG, "X-Response-Text: $responseText")
            }
            if (shouldContinueConversation) {
                Log.d(TAG, "X-Continue-Conversation: true - Conversation will continue after playback")
            } else {
                Log.d(TAG, "X-Continue-Conversation: false - Conversation will end after playback")
            }
            
            // Get response body
            val responseBody = response.body ?: run {
                Log.e(TAG, "Empty response from server")
                return@withContext
            }
            
            // Save and play audio
            saveAndPlayAudio(context, responseBody)
            
        } catch (e: IOException) {
            Log.e(TAG, "Network error sending audio", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
        }
    }
    
    /**
     * Save audio stream to temporary file and play it.
     * Ensures file is fully written before playback.
     */
    private suspend fun saveAndPlayAudio(context: Context, responseBody: ResponseBody) = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            // Create temporary file in cache directory
            tempFile = File.createTempFile("wariona_reply", ".mp3", context.cacheDir)
            currentTempFile = tempFile
            
            Log.d(TAG, "Saving audio response to: ${tempFile.absolutePath}")
            
            // Write audio data to file - ensure complete write
            responseBody.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    // Force sync to disk for Android 12+ compatibility
                    try {
                        output.fd?.sync()
                    } catch (e: Exception) {
                        // sync() may not be available on all Android versions
                        // flush() is sufficient for most cases
                        Log.d(TAG, "FileDescriptor.sync() not available, using flush only")
                    }
                }
            }
            
            // Verify file exists and has content before playing
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IOException("File was not written correctly")
            }
            
            Log.d(TAG, "Audio saved successfully: ${tempFile.length()} bytes")
            
            // Play audio on main thread only after file is fully written
            // Safely handle nullable tempFile
            val fileToPlay = tempFile
            if (fileToPlay != null) {
                withContext(Dispatchers.Main) {
                    playAudioFile(context, fileToPlay)
                }
            } else {
                Log.e(TAG, "Temp file is null, cannot play audio")
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error saving audio file", e)
            tempFile?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error saving audio", e)
            tempFile?.delete()
        }
    }
    
    /**
     * Play audio file using MediaPlayer.
     * Only starts playback after file is fully prepared.
     */
    private fun playAudioFile(context: Context, audioFile: File) {
        try {
            // Verify file exists before attempting playback
            if (!audioFile.exists() || audioFile.length() == 0L) {
                Log.e(TAG, "Cannot play: audio file is invalid or empty")
                return
            }
            
            // Get absolute path and verify it's not null or empty
            val audioPath = audioFile.absolutePath
            if (audioPath.isNullOrEmpty()) {
                Log.e(TAG, "Audio path is null or empty, skipping playback")
                return
            }
            
            // Stop any existing playback
            stopPlayback()
            
            Log.d(TAG, "Setting up MediaPlayer for file: $audioPath")
            
            // Create and configure MediaPlayer
            val player = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setDataSource(audioPath)
                
                // Set up completion listener to clean up and notify callback
                setOnCompletionListener {
                    Log.d(TAG, "Audio playback completed. Continue conversation: $shouldContinueConversation")
                    
                    // Notify callback about playback completion and continuation status
                    val callback = playbackCallback
                    if (callback != null) {
                        try {
                            callback.onPlaybackCompleted(shouldContinueConversation)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in playback callback", e)
                        }
                    }
                    
                    // Clean up resources (but don't reset shouldContinueConversation yet)
                    cleanup()
                }
                
                // Set up error listener
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    cleanup()
                    true
                }
                
                // Prepare asynchronously - playback starts only after preparation is complete
                prepareAsync()
                setOnPreparedListener { mp ->
                    Log.d(TAG, "MediaPlayer prepared, starting playback")
                    try {
                        mp.start()
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Error starting MediaPlayer", e)
                        cleanup()
                    }
                }
            }
            
            currentPlayer = player
            
        } catch (e: IOException) {
            Log.e(TAG, "Error setting up MediaPlayer", e)
            cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in playAudioFile", e)
            cleanup()
        }
    }
    
    /**
     * Stop current playback
     */
    fun stopPlayback() {
        currentPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping playback", e)
            }
            currentPlayer = null
        }
    }
    
    /**
     * Clean up temporary files and resources
     */
    fun cleanup() {
        stopPlayback()
        
        currentTempFile?.let { file ->
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Temporary audio file deleted: $deleted")
            }
            currentTempFile = null
        }
        
        // Reset continuation flag after cleanup
        shouldContinueConversation = false
    }
    
    /**
     * Java-friendly method to send and play audio.
     * Launches coroutine from the scope.
     */
    fun sendAndPlayAudioAsync(context: Context, audioFile: File) {
        scope.launch {
            sendAndPlayAudio(context, audioFile)
        }
    }
}

