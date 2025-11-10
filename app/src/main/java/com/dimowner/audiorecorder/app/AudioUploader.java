/*
 * Copyright 2024
 * Audio Uploader
 * Handles automatic upload of recorded audio files to backend API
 */

package com.dimowner.audiorecorder.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Utility class for uploading audio files to the backend API.
 * 
 * Uploads are performed in the background using OkHttp.
 * Success/error messages are shown via Toast on the main thread.
 */
public class AudioUploader {

	private static final String TAG = "AudioUploader";
	
	// Backend API endpoint
	private static final String UPLOAD_URL = "http://65.21.54.108:8000/upload-audio";
	
	// Request timeout settings (in seconds)
	private static final int CONNECT_TIMEOUT = 30;
	private static final int READ_TIMEOUT = 60;
	private static final int WRITE_TIMEOUT = 60;
	
	// Multipart form field name for the file
	private static final String FILE_FIELD_NAME = "file";
	
	// Media type for audio files
	private static final MediaType AUDIO_MEDIA_TYPE = MediaType.parse("audio/*");
	
	private final OkHttpClient httpClient;
	private final Handler mainHandler;
	
	/**
	 * Constructor - creates a new AudioUploader instance
	 */
	public AudioUploader() {
		// Create OkHttpClient with timeout settings
		httpClient = new OkHttpClient.Builder()
				.connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
				.readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
				.writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
				.build();
		
		// Handler for posting to main thread
		mainHandler = new Handler(Looper.getMainLooper());
	}
	
	/**
	 * Upload an audio file to the backend API.
	 * 
	 * @param context Application context (for showing Toast messages)
	 * @param audioFile The audio file to upload
	 */
	public void uploadAudioFile(Context context, File audioFile) {
		if (audioFile == null || !audioFile.exists()) {
			Timber.e("Audio file is null or does not exist");
			showToast(context, "Upload failed: File not found");
			return;
		}
		
		if (!audioFile.canRead()) {
			Timber.e("Cannot read audio file: %s", audioFile.getAbsolutePath());
			showToast(context, "Upload failed: Cannot read file");
			return;
		}
		
		Timber.d("Starting upload for file: %s (size: %d bytes)", 
				audioFile.getName(), audioFile.length());
		
		// Create multipart request body
		RequestBody fileBody = RequestBody.create(audioFile, AUDIO_MEDIA_TYPE);
		RequestBody requestBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart(FILE_FIELD_NAME, audioFile.getName(), fileBody)
				.build();
		
		// Create HTTP request
		Request request = new Request.Builder()
				.url(UPLOAD_URL)
				.post(requestBody)
				.build();
		
		// Execute request asynchronously
		httpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				Timber.e(e, "Upload failed for file: %s", audioFile.getName());
				String errorMsg = "Upload failed: " + getErrorMessage(e);
				showToast(context, errorMsg);
			}
			
			@Override
			public void onResponse(Call call, Response response) throws IOException {
				try {
					if (response.isSuccessful()) {
						// Success - file uploaded
						String responseBody = response.body() != null ? 
								response.body().string() : "";
						Timber.d("Upload successful for file: %s. Response: %s", 
								audioFile.getName(), responseBody);
						showToast(context, "Audio uploaded successfully");
					} else {
						// Server error
						String errorBody = response.body() != null ? 
								response.body().string() : "";
						Timber.e("Upload failed with status %d: %s", 
								response.code(), errorBody);
						showToast(context, "Upload failed: Server error (" + 
								response.code() + ")");
					}
				} catch (IOException e) {
					Timber.e(e, "Error reading response");
					showToast(context, "Upload failed: Error reading response");
				} finally {
					if (response.body() != null) {
						response.body().close();
					}
				}
			}
		});
	}
	
	/**
	 * Get a user-friendly error message from an exception
	 */
	private String getErrorMessage(IOException e) {
		String message = e.getMessage();
		if (message == null) {
			return "Network error";
		}
		
		// Provide more specific error messages
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
	 * Show a Toast message on the main thread
	 */
	private void showToast(Context context, String message) {
		mainHandler.post(() -> {
			Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
		});
	}
}

