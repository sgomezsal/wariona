/*
 * Copyright 2024
 * GTD Assistant Service
 * Handles voice interaction with GTD assistant backend
 */

package com.dimowner.audiorecorder.app.gtd;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Service for interacting with the GTD Assistant backend.
 * 
 * Flow:
 * 1. Record audio (file provided)
 * 2. Send to backend API
 * 3. Receive binary audio/mpeg response
 * 4. Save to temporary file
 * 5. Play audio
 * 6. Delete temporary file
 */
public class GtdAssistantService {

	private static final String TAG = "GtdAssistantService";
	
	// Backend API endpoint - CONFIGURE YOUR SERVER IP HERE
	// TODO: Make this configurable via settings or build config
	private static final String GTD_API_URL = "http://65.21.54.108:8000/talk";
	
	// Request timeout settings (in seconds)
	private static final int CONNECT_TIMEOUT = 60;
	private static final int READ_TIMEOUT = 120;
	private static final int WRITE_TIMEOUT = 120;
	
	// Multipart form field name for the file
	private static final String FILE_FIELD_NAME = "file";
	
	// Media type for audio files
	private static final MediaType AUDIO_MEDIA_TYPE = MediaType.parse("audio/*");
	
	// Response headers
	private static final String HEADER_TRANSCRIPTION = "X-Transcription";
	private static final String HEADER_RESPONSE_TEXT = "X-Response-Text";
	
	private final OkHttpClient httpClient;
	private final Handler mainHandler;
	private MediaPlayer mediaPlayer;
	private File tempAudioFile;
	private GtdAssistantListener listener;
	
	/**
	 * Listener interface for GTD Assistant events
	 */
	public interface GtdAssistantListener {
		/**
		 * Called when the service starts recording
		 */
		void onListening();
		
		/**
		 * Called when the audio is being sent and processed
		 */
		void onProcessing();
		
		/**
		 * Called when the response audio starts playing
		 */
		void onSpeaking();
		
		/**
		 * Called when the interaction completes
		 */
		void onComplete();
		
		/**
		 * Called when an error occurs
		 */
		void onError(String message);
		
		/**
		 * Called with transcription and response text from headers
		 */
		void onTextResponse(String transcription, String responseText);
	}
	
	/**
	 * Constructor
	 */
	public GtdAssistantService() {
		// Create OkHttpClient with extended timeouts for processing
		httpClient = new OkHttpClient.Builder()
				.connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
				.readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
				.writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
				.build();
		
		// Handler for posting to main thread
		mainHandler = new Handler(Looper.getMainLooper());
	}
	
	/**
	 * Set the listener for GTD Assistant events
	 */
	public void setListener(GtdAssistantListener listener) {
		this.listener = listener;
	}
	
	/**
	 * Send audio file to GTD Assistant and play the response
	 * 
	 * @param context Application context
	 * @param audioFile The recorded audio file to send
	 */
	public void sendAudioAndPlayResponse(Context context, File audioFile) {
		if (audioFile == null || !audioFile.exists()) {
			notifyError("Audio file not found");
			return;
		}
		
		if (!audioFile.canRead()) {
			notifyError("Cannot read audio file");
			return;
		}
		
		Timber.d("Sending audio to GTD Assistant: %s (size: %d bytes)", 
				audioFile.getName(), audioFile.length());
		
		// Notify listening state
		notifyListening();
		
		// Create multipart request body
		RequestBody fileBody = RequestBody.create(audioFile, AUDIO_MEDIA_TYPE);
		RequestBody requestBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart(FILE_FIELD_NAME, audioFile.getName(), fileBody)
				.build();
		
		// Create HTTP request
		Request request = new Request.Builder()
				.url(GTD_API_URL)
				.post(requestBody)
				.build();
		
		// Execute request asynchronously
		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				Timber.e(e, "GTD Assistant request failed");
				notifyError("Connection failed: " + getErrorMessage(e));
			}
			
			@Override
			public void onResponse(Call call, Response response) throws IOException {
				try {
					if (response.isSuccessful()) {
						// Extract headers
						String transcription = response.header(HEADER_TRANSCRIPTION, "");
						String responseText = response.header(HEADER_RESPONSE_TEXT, "");
						
						// Notify text response
						if (listener != null) {
							mainHandler.post(() -> {
								listener.onTextResponse(transcription, responseText);
							});
						}
						
						// Get binary audio response
						okhttp3.ResponseBody responseBody = response.body();
						if (responseBody == null) {
							notifyError("Empty response from server");
							return;
						}
						
						// Check content type
						String contentType = response.header("Content-Type", "");
						if (!contentType.contains("audio")) {
							Timber.w("Unexpected content type: %s", contentType);
						}
						
						// Save to temporary file and play
						saveAndPlayAudio(context, responseBody.byteStream(), responseBody.contentLength());
						
					} else {
						// Server error
						String errorBody = response.body() != null ? 
								response.body().string() : "";
						Timber.e("GTD Assistant request failed with status %d: %s", 
								response.code(), errorBody);
						notifyError("Server error (" + response.code() + ")");
					}
				} catch (IOException e) {
					Timber.e(e, "Error processing response");
					notifyError("Error processing response: " + e.getMessage());
				} finally {
					if (response.body() != null) {
						response.body().close();
					}
				}
			}
		});
	}
	
	/**
	 * Save audio stream to temporary file and play it
	 */
	private void saveAndPlayAudio(Context context, InputStream audioStream, long contentLength) {
		mainHandler.post(() -> notifyProcessing());
		
		// Run file operations in background
		new Thread(() -> {
			try {
				// Create temporary file in cache directory
				File cacheDir = context.getCacheDir();
				tempAudioFile = File.createTempFile("gtd_response_", ".mp3", cacheDir);
				
				Timber.d("Saving audio response to: %s", tempAudioFile.getAbsolutePath());
				
				// Write audio data to file
				FileOutputStream fos = new FileOutputStream(tempAudioFile);
				byte[] buffer = new byte[8192];
				int bytesRead;
				long totalBytes = 0;
				
				while ((bytesRead = audioStream.read(buffer)) != -1) {
					fos.write(buffer, 0, bytesRead);
					totalBytes += bytesRead;
				}
				
				fos.flush();
				fos.close();
				audioStream.close();
				
				Timber.d("Audio saved: %d bytes", totalBytes);
				
				// Play audio on main thread
				mainHandler.post(() -> {
					playAudioFile(tempAudioFile);
				});
				
			} catch (IOException e) {
				Timber.e(e, "Error saving audio file");
				mainHandler.post(() -> {
					notifyError("Error saving audio: " + e.getMessage());
				});
			}
		}).start();
	}
	
	/**
	 * Play audio file using MediaPlayer
	 */
	private void playAudioFile(File audioFile) {
		try {
			// Stop any existing playback
			stopPlayback();
			
			// Create and configure MediaPlayer
			mediaPlayer = new MediaPlayer();
			mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mediaPlayer.setDataSource(audioFile.getAbsolutePath());
			
			// Set up completion listener to clean up
			mediaPlayer.setOnCompletionListener(mp -> {
				Timber.d("Audio playback completed");
				cleanup();
				if (listener != null) {
					listener.onComplete();
				}
			});
			
			// Set up error listener
			mediaPlayer.setOnErrorListener((mp, what, extra) -> {
				Timber.e("MediaPlayer error: what=%d, extra=%d", what, extra);
				cleanup();
				notifyError("Playback error");
				return true;
			});
			
			// Prepare and play
			mediaPlayer.prepareAsync();
			mediaPlayer.setOnPreparedListener(mp -> {
				Timber.d("Starting audio playback");
				mp.start();
				notifySpeaking();
			});
			
		} catch (IOException e) {
			Timber.e(e, "Error setting up MediaPlayer");
			cleanup();
			notifyError("Playback setup error: " + e.getMessage());
		}
	}
	
	/**
	 * Stop current playback
	 */
	public void stopPlayback() {
		if (mediaPlayer != null) {
			try {
				if (mediaPlayer.isPlaying()) {
					mediaPlayer.stop();
				}
				mediaPlayer.release();
			} catch (Exception e) {
				Timber.e(e, "Error stopping playback");
			}
			mediaPlayer = null;
		}
	}
	
	/**
	 * Clean up temporary files and resources
	 */
	public void cleanup() {
		stopPlayback();
		
		if (tempAudioFile != null && tempAudioFile.exists()) {
			boolean deleted = tempAudioFile.delete();
			Timber.d("Temporary audio file deleted: %s", deleted);
			tempAudioFile = null;
		}
	}
	
	/**
	 * Get user-friendly error message
	 */
	private String getErrorMessage(IOException e) {
		String message = e.getMessage();
		if (message == null) {
			return "Network error";
		}
		
		if (message.contains("Unable to resolve host")) {
			return "No internet connection";
		} else if (message.contains("timeout")) {
			return "Connection timeout";
		} else if (message.contains("Connection refused")) {
			return "Server unavailable";
		} else {
			return message;
		}
	}
	
	/**
	 * Notify listener on main thread
	 */
	private void notifyListening() {
		if (listener != null) {
			mainHandler.post(() -> listener.onListening());
		}
	}
	
	private void notifyProcessing() {
		if (listener != null) {
			listener.onProcessing();
		}
	}
	
	private void notifySpeaking() {
		if (listener != null) {
			listener.onSpeaking();
		}
	}
	
	private void notifyError(String message) {
		if (listener != null) {
			mainHandler.post(() -> listener.onError(message));
		}
	}
}

