/*
 * Copyright 2020 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimowner.audiorecorder.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.dimowner.audiorecorder.ARApplication;
import com.dimowner.audiorecorder.AppConstants;
import com.dimowner.audiorecorder.BackgroundQueue;
import com.dimowner.audiorecorder.ColorMap;
import com.dimowner.audiorecorder.R;
import com.dimowner.audiorecorder.app.main.MainActivity;
import com.dimowner.audiorecorder.audio.player.PlayerContractNew;
import com.dimowner.audiorecorder.audio.recorder.RecorderContract;
import com.dimowner.audiorecorder.data.FileRepository;
import com.dimowner.audiorecorder.data.Prefs;
import com.dimowner.audiorecorder.data.RecordDataSource;
import com.dimowner.audiorecorder.data.database.LocalRepository;
import com.dimowner.audiorecorder.data.database.Record;
import com.dimowner.audiorecorder.exception.AppException;
import com.dimowner.audiorecorder.exception.CantCreateFileException;
import com.dimowner.audiorecorder.exception.ErrorParser;
import com.dimowner.audiorecorder.exception.RecorderInitException;
import com.dimowner.audiorecorder.util.AndroidUtils;
import com.dimowner.audiorecorder.util.ExtensionsKt;
import com.dimowner.audiorecorder.util.TimeUtils;

import java.io.File;
import java.io.IOException;

import timber.log.Timber;

public class RecordingService extends Service {

	private final static String CHANNEL_NAME = "Default";
	private final static String CHANNEL_ID = "com.dimowner.audiorecorder.NotificationId";

	private final static String CHANNEL_NAME_ERRORS = "Errors";
	private final static String CHANNEL_ID_ERRORS = "com.dimowner.audiorecorder.Errors";

	public static final String EXTRAS_KEY_RECORD_PATH = "EXTRAS_KEY_RECORD_PATH";
	public static final String ACTION_START_RECORDING_SERVICE = "ACTION_START_RECORDING_SERVICE";

	public static final String ACTION_STOP_RECORDING_SERVICE = "ACTION_STOP_RECORDING_SERVICE";

	public static final String ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING";
	public static final String ACTION_PAUSE_RECORDING = "ACTION_PAUSE_RECORDING";
	public static final String ACTION_TOGGLE_RECORDING = "ACTION_TOGGLE_RECORDING";

	private static final int NOTIF_ID = 101;
	private NotificationManager notificationManager;
	private RemoteViews remoteViewsSmall;
	private RemoteViews remoteViewsBig;
	private PendingIntent contentPendingIntent;

	private AppRecorder appRecorder;
	private PlayerContractNew.Player audioPlayer;
	private BackgroundQueue recordingsTasks;
	private LocalRepository localRepository;
	private Prefs prefs;
	private RecordDataSource recordDataSource;
	private RecorderContract.Recorder recorder;
	private AppRecorderCallback appRecorderCallback;
	private ColorMap colorMap;
	private boolean started = false;
	private FileRepository fileRepository;
	private SilenceDetector silenceDetector;

	public RecordingService() {
	}

	@Override
	public IBinder onBind(Intent intent) {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		appRecorder = ARApplication.getInjector().provideAppRecorder(getApplicationContext());
		audioPlayer = ARApplication.getInjector().provideAudioPlayer();
		recordingsTasks = ARApplication.getInjector().provideRecordingTasksQueue();
		localRepository = ARApplication.getInjector().provideLocalRepository(getApplicationContext());
		prefs = ARApplication.getInjector().providePrefs(getApplicationContext());
		recorder = ARApplication.getInjector().provideAudioRecorder(getApplicationContext());
		recordDataSource = ARApplication.getInjector().provideRecordDataSource(getApplicationContext());

		colorMap = ARApplication.getInjector().provideColorMap(getApplicationContext());
		fileRepository = ARApplication.getInjector().provideFileRepository(getApplicationContext());

		// Initialize silence detector
		silenceDetector = new SilenceDetector();

		appRecorderCallback = new AppRecorderCallback() {
			boolean checkHasSpace = true;

			@Override public void onRecordingStarted(File file) {
				// Enable silence detection when recording starts
				silenceDetector.enable();
				updateNotificationResume();
			}
			@Override public void onRecordingPaused() {
				// Reset silence detector on pause
				silenceDetector.reset();
				updateNotificationPause();
			}
			@Override public void onRecordingResumed() {
				// Reset silence detector on resume
				silenceDetector.reset();
				updateNotificationResume();
			}
			@Override public void onRecordingStopped(File file, Record rec) {
				// Disable silence detection when recording stops
				silenceDetector.disable();
				
				// ===== AUTOMATIC FILE UPLOAD AND PLAYBACK =====
				// Send the recorded file to Wariona backend and play the response
				// Uses Kotlin coroutines for async operations (Dispatchers.IO)
				// The playback callback will handle conversation continuation automatically
				if (file != null && file.exists()) {
					// Use WarionaAudioPlayer with coroutines for better async handling
					WarionaAudioPlayer.INSTANCE.sendAndPlayAudioAsync(getApplicationContext(), file);
					// Don't stop the service yet - wait for playback completion callback
					// The callback will decide whether to continue or stop based on X-Continue-Conversation header
				} else {
					Timber.w("Recording stopped but file is null or doesn't exist");
					stopForegroundService();
				}
				// ===== END AUTOMATIC FILE UPLOAD AND PLAYBACK =====
				
				if (rec != null && rec.getDuration()/1000 < AppConstants.DECODE_DURATION && !rec.isWaveformProcessed()) {
					DecodeService.Companion.startNotification(getApplicationContext(), rec.getId());
				}
				// Note: stopForegroundService() is now called from the playback callback
				// if the conversation should not continue (X-Continue-Conversation: false)
			}

			@Override
			public void onRecordingProgress(long mills, int amp) {
				// ===== SILENCE DETECTION =====
				// Check if silence is detected and stop recording automatically
				// This monitors audio amplitude continuously during recording
				// Adjust threshold and duration in SilenceDetector.java
				if (silenceDetector.checkSilence(amp, System.currentTimeMillis())) {
					Timber.d("Automatic stop due to silence detection");
					stopRecording();
					AndroidUtils.runOnUIThread(() -> {
						Toast.makeText(getApplicationContext(), 
								"Recording stopped due to silence", 
								Toast.LENGTH_SHORT).show();
					});
					return;
				}
				// ===== END SILENCE DETECTION =====

				try {
					if (mills % 10000 < 1000) {
						if (checkHasSpace && !fileRepository.hasAvailableSpace(getApplicationContext())) {
							stopRecording();
							AndroidUtils.runOnUIThread(() -> {
								Toast.makeText(getApplicationContext(), R.string.error_no_available_space, Toast.LENGTH_LONG).show();
							});
							showNoSpaceNotification();
						}
						checkHasSpace = false;
					} else {
						checkHasSpace = true;
					}
				} catch (IllegalArgumentException e) {
					stopRecording();
					AndroidUtils.runOnUIThread(() -> {
						Toast.makeText(getApplicationContext(), R.string.error_failed_access_to_storage, Toast.LENGTH_LONG).show();
					});
					showNoSpaceNotification();
				}
			}

			@Override public void onError(AppException throwable) {
				showError(ErrorParser.parseException(throwable));
				stopForegroundService();
			}
		};
		appRecorder.addRecordingCallback(appRecorderCallback);
		
		// Set up Wariona playback callback for multi-turn conversation support
		WarionaAudioPlayer.INSTANCE.setPlaybackCallback(new WarionaPlaybackCallback() {
			@Override
			public void onPlaybackCompleted(boolean shouldContinue) {
				Timber.d("Playback completed. Should continue conversation: %s", shouldContinue);
				
				if (shouldContinue) {
					// Automatically restart recording for next turn in conversation
					AndroidUtils.runOnUIThread(() -> {
						Toast.makeText(getApplicationContext(), 
								R.string.wariona_listening_clarification, 
								Toast.LENGTH_SHORT).show();
					});
					
					// Ensure service is still running (may have been stopped after previous recording)
					if (!started) {
						startForegroundService();
					}
					
					// Start a new recording session
					try {
						String path = fileRepository.provideRecordFile().getAbsolutePath();
						Timber.d("Starting new recording for conversation continuation: %s", path);
						startRecording(path);
					} catch (CantCreateFileException e) {
						Timber.e(e, "Failed to start recording for conversation continuation");
						showError(ErrorParser.parseException(e));
						stopForegroundService();
					}
				} else {
					// Conversation ended normally - stop the service
					Timber.d("Conversation ended normally");
					stopForegroundService();
				}
			}
		});
	}

	public void showNoSpaceNotification() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel(CHANNEL_ID_ERRORS, CHANNEL_NAME_ERRORS);
		}
		NotificationCompat.Builder builder =
				new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_record_rec)
						.setContentTitle(getApplicationContext().getString(R.string.app_name))
						.setContentText(getApplicationContext().getString(R.string.error_no_available_space))
						.setContentIntent(createContentIntent())
						.setVibrate(new long[] { 1000, 1000, 1000, 1000, 1000 })
						.setLights(Color.RED, 500, 500)
						.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
						.setAutoCancel(true)
						.setPriority(NotificationCompat.PRIORITY_MAX);

		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getApplicationContext());
		notificationManager.notify(303, builder.build());
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			String action = intent.getAction();
			if (action != null && !action.isEmpty()) {
				switch (action) {
					case ACTION_START_RECORDING_SERVICE:
						if (!started) {
							startForegroundService();
							if (intent.hasExtra(EXTRAS_KEY_RECORD_PATH)) {
								startRecording(intent.getStringExtra(EXTRAS_KEY_RECORD_PATH));
							} else {
								showError(ErrorParser.parseException(new RecorderInitException()));
								stopForegroundService();
							}
						}
						break;
					case ACTION_STOP_RECORDING_SERVICE:
						stopForegroundService();
						break;
					case ACTION_STOP_RECORDING:
						stopRecording();
						break;
					case ACTION_PAUSE_RECORDING:
						if (appRecorder.isPaused()) {
							appRecorder.resumeRecording();
						} else {
							appRecorder.pauseRecording();
						}
						break;
					case ACTION_TOGGLE_RECORDING:
						toggleRecording();
						break;
				}
			}
		}
		return super.onStartCommand(intent, flags, startId);
	}

	private void toggleRecording() {
		if (appRecorder.isRecording()) {
			// Stop recording
			stopRecording();
		} else {
			// Start recording
			if (!started) {
				startForegroundService();
			}
			try {
				String path = fileRepository.provideRecordFile().getAbsolutePath();
				startRecording(path);
			} catch (CantCreateFileException e) {
				Timber.e(e, "Failed to start recording from button pattern");
				showError(ErrorParser.parseException(e));
				if (started) {
					stopForegroundService();
				}
			}
		}
	}

	private void stopRecording() {
		appRecorder.stopRecording();
	}

	private void startForegroundService() {
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel(CHANNEL_ID, CHANNEL_NAME);
		}

		boolean isNightMode = ExtensionsKt.isUsingNightModeResources(getApplicationContext());

		remoteViewsSmall = new RemoteViews(getPackageName(), R.layout.layout_record_notification_small);
		remoteViewsSmall.setOnClickPendingIntent(R.id.btn_recording_stop, getPendingSelfIntent(getApplicationContext(), ACTION_STOP_RECORDING));
		remoteViewsSmall.setOnClickPendingIntent(R.id.btn_recording_pause, getPendingSelfIntent(getApplicationContext(), ACTION_PAUSE_RECORDING));
		remoteViewsSmall.setTextViewText(R.id.txt_recording_progress, getResources().getString(R.string.recording_is_on));
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			remoteViewsSmall.setInt(R.id.container, "setBackgroundColor", this.getResources().getColor(colorMap.getPrimaryColorRes()));
			remoteViewsSmall.setInt(R.id.app_logo, "setVisibility", View.VISIBLE);
		} else {
			remoteViewsSmall.setInt(R.id.container, "setBackgroundColor", this.getResources().getColor(R.color.transparent));
			remoteViewsSmall.setInt(R.id.app_logo, "setVisibility", View.GONE);
			if (isNightMode) {
				remoteViewsSmall.setInt(R.id.txt_name, "setTextColor", this.getResources().getColor(R.color.text_primary_light));
				remoteViewsSmall.setInt(R.id.txt_app_label, "setTextColor", this.getResources().getColor(R.color.text_primary_light));
				remoteViewsSmall.setInt(R.id.txt_recording_progress, "setTextColor", this.getResources().getColor(R.color.text_primary_light));
				remoteViewsSmall.setInt(R.id.btn_recording_stop, "setImageResource", R.drawable.ic_stop);
				remoteViewsSmall.setInt(R.id.btn_recording_pause, "setImageResource", R.drawable.ic_pause_light);
			} else {
				remoteViewsSmall.setInt(R.id.txt_name, "setTextColor", this.getResources().getColor(R.color.text_primary_dark));
				remoteViewsSmall.setInt(R.id.txt_app_label, "setTextColor", this.getResources().getColor(R.color.text_primary_dark));
				remoteViewsSmall.setInt(R.id.txt_recording_progress, "setTextColor", this.getResources().getColor(R.color.text_primary_dark));
				remoteViewsSmall.setInt(R.id.btn_recording_stop, "setImageResource", R.drawable.ic_stop_dark);
				remoteViewsSmall.setInt(R.id.btn_recording_pause, "setImageResource", R.drawable.ic_pause_dark);
			}
		}

		remoteViewsBig = new RemoteViews(getPackageName(), R.layout.layout_record_notification_big);
		remoteViewsBig.setOnClickPendingIntent(R.id.btn_recording_stop, getPendingSelfIntent(getApplicationContext(), ACTION_STOP_RECORDING));
		remoteViewsBig.setOnClickPendingIntent(R.id.btn_recording_pause, getPendingSelfIntent(getApplicationContext(), ACTION_PAUSE_RECORDING));
		remoteViewsBig.setTextViewText(R.id.txt_recording_progress, TimeUtils.formatTimeIntervalMinSecMills(0));
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			remoteViewsBig.setInt(R.id.container, "setBackgroundColor", this.getResources().getColor(colorMap.getPrimaryColorRes()));
			remoteViewsBig.setInt(R.id.app_logo, "setVisibility", View.VISIBLE);
		} else {
			remoteViewsBig.setInt(R.id.container, "setBackgroundColor", this.getResources().getColor(R.color.transparent));
			remoteViewsBig.setInt(R.id.app_logo, "setVisibility", View.GONE);
			if (isNightMode) {
				remoteViewsBig.setInt(R.id.txt_app_label, "setTextColor", this.getResources().getColor(R.color.text_primary_light));
				remoteViewsBig.setInt(R.id.txt_recording_progress, "setTextColor", this.getResources().getColor(R.color.text_primary_light));
				remoteViewsBig.setInt(R.id.btn_recording_stop, "setImageResource", R.drawable.ic_stop);
				remoteViewsBig.setInt(R.id.btn_recording_pause, "setImageResource", R.drawable.ic_pause_light);
			} else {
				remoteViewsBig.setInt(R.id.txt_app_label, "setTextColor", this.getResources().getColor(R.color.text_primary_dark));
				remoteViewsBig.setInt(R.id.txt_recording_progress, "setTextColor", this.getResources().getColor(R.color.text_primary_dark));
				remoteViewsBig.setInt(R.id.btn_recording_stop, "setImageResource", R.drawable.ic_stop_dark);
				remoteViewsBig.setInt(R.id.btn_recording_pause, "setImageResource", R.drawable.ic_pause_dark);
			}
		}

		contentPendingIntent = createContentIntent();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			startForeground(NOTIF_ID, buildNotification());
		} else {
			startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
		}
		started = true;
	}

	private Notification buildNotification() {
		// Create notification builder.
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID);

		builder.setWhen(System.currentTimeMillis());
		builder.setSmallIcon(R.drawable.ic_record_rec);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			builder.setPriority(NotificationManager.IMPORTANCE_MAX);
		} else {
			builder.setPriority(Notification.PRIORITY_MAX);
		}
		// Make head-up notification.
		builder.setContentIntent(contentPendingIntent);
		builder.setCustomContentView(remoteViewsSmall);
		builder.setCustomBigContentView(remoteViewsBig);
		builder.setOnlyAlertOnce(true);
		builder.setDefaults(0);
		builder.setSound(null);
		return builder.build();
	}

	@SuppressLint("WrongConstant")
	private PendingIntent createContentIntent() {
		// Create notification default intent.
		try {
			Intent intent = new Intent(getApplicationContext(), 
					Class.forName("com.dimowner.audiorecorder.app.main.MainActivityMinimal"));
			intent.setFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
			return PendingIntent.getActivity(getApplicationContext(), 0, intent, AppConstants.PENDING_INTENT_FLAGS);
		} catch (ClassNotFoundException e) {
			// Fallback to original MainActivity if MainActivityMinimal doesn't exist
			Intent intent = new Intent(getApplicationContext(), MainActivity.class);
			intent.setFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
			return PendingIntent.getActivity(getApplicationContext(), 0, intent, AppConstants.PENDING_INTENT_FLAGS);
		}
	}

	private void stopForegroundService() {
		appRecorder.removeRecordingCallback(appRecorderCallback);
		// Clean up Wariona audio player
		WarionaAudioPlayer.INSTANCE.cleanup();
		stopForeground(true);
		stopSelf();
		started = false;
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		// Clear callback to prevent memory leaks
		WarionaAudioPlayer.INSTANCE.setPlaybackCallback(null);
		// Clean up Wariona audio player
		WarionaAudioPlayer.INSTANCE.cleanup();
	}

	@SuppressLint("WrongConstant")
	protected PendingIntent getPendingSelfIntent(Context context, String action) {
		Intent intent = new Intent(context, StopRecordingReceiver.class);
		intent.setAction(action);
		return PendingIntent.getBroadcast(context, 10, intent, AppConstants.PENDING_INTENT_FLAGS);
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private void createNotificationChannel(String channelId, String channelName) {
		NotificationChannel channel = notificationManager.getNotificationChannel(channelId);
		if (channel == null) {
			NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
			chan.setLightColor(Color.BLUE);
			chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
			chan.setSound(null, null);
			chan.enableLights(false);
			chan.enableVibration(false);

			notificationManager.createNotificationChannel(chan);
		}
	}

	private void updateNotificationPause() {
		if (started && remoteViewsSmall != null) {
			boolean isNightMode = ExtensionsKt.isUsingNightModeResources(getApplicationContext());
			remoteViewsSmall.setTextViewText(R.id.txt_recording_progress, getResources().getString(R.string.recording_paused));
			if (isNightMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
				remoteViewsSmall.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_recording_light);
			} else {
				remoteViewsSmall.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_recording_dark);
			}

			if (remoteViewsBig != null) {
				remoteViewsBig.setTextViewText(R.id.txt_recording_progress, getResources().getString(R.string.recording_paused));
				if (isNightMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
					remoteViewsBig.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_recording_light);
				} else {
					remoteViewsBig.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_recording_dark);
				}
			}

			notificationManager.notify(NOTIF_ID, buildNotification());
		}
	}

	private void updateNotificationResume() {
		if (started && remoteViewsSmall != null) {
			boolean isNightMode = ExtensionsKt.isUsingNightModeResources(getApplicationContext());
			remoteViewsSmall.setTextViewText(R.id.txt_recording_progress, getResources().getString(R.string.recording_is_on));
			if (isNightMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
				remoteViewsSmall.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_pause_light);
			} else {
				remoteViewsSmall.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_pause_dark);
			}

			if (remoteViewsBig != null) {
				remoteViewsBig.setTextViewText(R.id.txt_recording_progress, getResources().getString(R.string.recording_is_on));
				if (isNightMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
					remoteViewsBig.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_pause_light);
				} else {
					remoteViewsBig.setImageViewResource(R.id.btn_recording_pause, R.drawable.ic_pause_dark);
				}
			}

			notificationManager.notify(NOTIF_ID, buildNotification());
		}
	}

	private void updateNotification(long mills) {
		if (started && remoteViewsSmall != null) {
			remoteViewsSmall.setTextViewText(R.id.txt_recording_progress,
					getResources().getString(R.string.recording, TimeUtils.formatTimeIntervalHourMinSec2(mills)));

			if (remoteViewsBig != null) {
				remoteViewsBig.setTextViewText(R.id.txt_recording_progress,
						getResources().getString(R.string.recording, TimeUtils.formatTimeIntervalHourMinSec2(mills)));
			}

			notificationManager.notify(NOTIF_ID, buildNotification());
		}
	}

	private void startRecording(String path) {
		appRecorder.setRecorder(recorder);
		try {
			if (fileRepository.hasAvailableSpace(getApplicationContext())) {
//				if (appRecorder.isPaused()) {
//					appRecorder.resumeRecording();
//				} else
				if (!appRecorder.isRecording()) {
					if (audioPlayer.isPlaying() || audioPlayer.isPaused()) {
						audioPlayer.stop();
					}
					recordingsTasks.postRunnable(() -> {
						try {
							Record record = localRepository.insertEmptyFile(path);
							prefs.setActiveRecord(record.getId());
							recordDataSource.setRecordingRecord(record);
							AndroidUtils.runOnUIThread(() -> appRecorder.startRecording(
									path,
									prefs.getSettingChannelCount(),
									prefs.getSettingSampleRate(),
									prefs.getSettingBitrate()
							));
						} catch (IOException | OutOfMemoryError | IllegalStateException | NullPointerException e) {
							Timber.e(e);
							showError(R.string.error_failed_to_start_recording);
						}
					});
				}
//				else {
//					appRecorder.pauseRecording();
//				}
			} else {
				showError(R.string.error_no_available_space);
				stopForegroundService();
			}
		} catch (IllegalArgumentException e) {
			showError(R.string.error_failed_access_to_storage);
			stopForegroundService();
		}
	}

	public void showError(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	public static class StopRecordingReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Intent stopIntent = new Intent(context, RecordingService.class);
			stopIntent.setAction(intent.getAction());
			context.startService(stopIntent);
		}
	}
}
