/*
 * Copyright 2024
 * Minimalist Audio Recorder - Simplified Main Activity
 */

package com.dimowner.audiorecorder.app.main;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.dimowner.audiorecorder.ARApplication;
import com.dimowner.audiorecorder.ColorMap;
import com.dimowner.audiorecorder.R;
import com.dimowner.audiorecorder.app.AppRecorder;
import com.dimowner.audiorecorder.app.AppRecorderCallback;
import com.dimowner.audiorecorder.app.RecordingService;
import com.dimowner.audiorecorder.app.buttonpattern.ButtonPatternSettingsActivity;
import com.dimowner.audiorecorder.app.gtd.GtdAssistantService;
import com.dimowner.audiorecorder.app.records.RecordsActivityMinimal;
import com.dimowner.audiorecorder.app.widget.AnimatedCircleView;
import com.dimowner.audiorecorder.data.FileRepository;
import com.dimowner.audiorecorder.data.database.Record;
import com.dimowner.audiorecorder.exception.AppException;
import com.dimowner.audiorecorder.exception.CantCreateFileException;
import com.dimowner.audiorecorder.exception.ErrorParser;
import com.dimowner.audiorecorder.util.TimeUtils;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.dimowner.audiorecorder.app.wakeword.AriWakeWordService;

public class MainActivityMinimal extends Activity implements MainContract.View {

	public static final int REQ_CODE_RECORD_AUDIO = 303;
	public static final int REQ_CODE_WRITE_EXTERNAL_STORAGE = 404;
	public static final int REQ_CODE_POST_NOTIFICATIONS = 408;

	private AnimatedCircleView animatedCircle;
	private TextView txtRecordingTime;
	private TextView txtHint;
	private TextView txtGtdTranscription;
	private TextView txtGtdResponse;
	private ImageButton btnRecords;
	private ImageButton btnButtonPatternSettings;
	private ImageButton btnGtdMode;

	private MainContract.UserActionsListener presenter;
	private ColorMap colorMap;
	private FileRepository fileRepository;
	private GtdAssistantService gtdAssistantService;
	private boolean isRecording = false;
	private boolean isGtdMode = false;
	private File currentRecordingFile;

	public static Intent getStartIntent(Context context) {
		return new Intent(context, MainActivityMinimal.class);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		colorMap = ARApplication.getInjector().provideColorMap(getApplicationContext());
		// Use white theme for minimalist design
		setTheme(R.style.AppTheme);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main_minimal);

		animatedCircle = findViewById(R.id.animated_circle);
		txtRecordingTime = findViewById(R.id.txt_recording_time);
		txtHint = findViewById(R.id.txt_hint);
		txtGtdTranscription = findViewById(R.id.txt_gtd_transcription);
		txtGtdResponse = findViewById(R.id.txt_gtd_response);
		btnRecords = findViewById(R.id.btn_records);
		btnButtonPatternSettings = findViewById(R.id.btn_button_pattern_settings);
		btnGtdMode = findViewById(R.id.btn_gtd_mode);

		// Set white background
		getWindow().getDecorView().setBackgroundColor(getResources().getColor(android.R.color.white));

		// Setup click listeners
		animatedCircle.setOnClickListener(v -> {
			if (isRecording) {
				stopRecording();
			} else {
				startRecording();
			}
		});

		btnRecords.setOnClickListener(v -> {
			startActivity(RecordsActivityMinimal.getStartIntent(getApplicationContext()));
		});

		btnButtonPatternSettings.setOnClickListener(v -> {
			startActivity(new Intent(getApplicationContext(), ButtonPatternSettingsActivity.class));
		});

		// Initialize GTD Assistant Service
		gtdAssistantService = new GtdAssistantService();
		gtdAssistantService.setListener(new GtdAssistantService.GtdAssistantListener() {
			@Override
			public void onListening() {
				runOnUiThread(() -> {
					txtHint.setText(R.string.gtd_listening);
				});
			}

			@Override
			public void onProcessing() {
				runOnUiThread(() -> {
					txtHint.setText(R.string.gtd_processing);
				});
			}

			@Override
			public void onSpeaking() {
				runOnUiThread(() -> {
					txtHint.setText(R.string.gtd_speaking);
				});
			}

			@Override
			public void onComplete() {
				runOnUiThread(() -> {
					updateGtdModeHint();
					// Clean up recording file if in GTD mode
					if (isGtdMode && currentRecordingFile != null && currentRecordingFile.exists()) {
						currentRecordingFile.delete();
						currentRecordingFile = null;
					}
				});
			}

			@Override
			public void onError(String message) {
				runOnUiThread(() -> {
					showError(message);
					updateGtdModeHint();
				});
			}

			@Override
			public void onTextResponse(String transcription, String responseText) {
				runOnUiThread(() -> {
					if (transcription != null && !transcription.isEmpty()) {
						txtGtdTranscription.setText(getString(R.string.gtd_transcription) + " " + transcription);
						txtGtdTranscription.setVisibility(View.VISIBLE);
					}
					if (responseText != null && !responseText.isEmpty()) {
						txtGtdResponse.setText(getString(R.string.gtd_response) + " " + responseText);
						txtGtdResponse.setVisibility(View.VISIBLE);
					}
				});
			}
		});

		// GTD Mode toggle
		btnGtdMode.setOnClickListener(v -> {
			isGtdMode = !isGtdMode;
			updateGtdModeHint();
			// Clear text responses when toggling
			txtGtdTranscription.setVisibility(View.GONE);
			txtGtdResponse.setVisibility(View.GONE);
		});

		presenter = ARApplication.getInjector().provideMainPresenter(getApplicationContext());
		fileRepository = ARApplication.getInjector().provideFileRepository(getApplicationContext());
		
		// Add callback to intercept recording stop for GTD mode
		AppRecorder appRecorder = ARApplication.getInjector().provideAppRecorder(getApplicationContext());
		appRecorder.addRecordingCallback(new AppRecorderCallback() {
			@Override
			public void onRecordingStarted(File file) {
				currentRecordingFile = file;
			}

			@Override
			public void onRecordingPaused() {}

			@Override
			public void onRecordingResumed() {}

			@Override
			public void onRecordingProgress(long mills, int amp) {}

			@Override
			public void onRecordingStopped(File file, Record rec) {
				// If in GTD mode, send to GTD assistant instead of saving
				if (isGtdMode && file != null && file.exists()) {
					gtdAssistantService.sendAudioAndPlayResponse(getApplicationContext(), file);
				}
			}

			@Override
			public void onError(AppException throwable) {}
		});

		// Request permissions if needed
		checkPermissions();
		startWakeWordServiceIfReady();
	}

	@Override
	protected void onStart() {
		super.onStart();
		presenter.bindView(this);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			presenter.storeInPrivateDir(getApplicationContext());
		}
		presenter.checkFirstRun();
		presenter.setAudioRecorder(ARApplication.getInjector().provideAudioRecorder(getApplicationContext()));
		presenter.updateRecordingDir(getApplicationContext());
		presenter.loadActiveRecord();
		startWakeWordServiceIfReady();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (presenter != null) {
			presenter.unbindView();
		}
		if (gtdAssistantService != null) {
			gtdAssistantService.cleanup();
		}
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (gtdAssistantService != null) {
			gtdAssistantService.cleanup();
		}
	}
	
	private void updateGtdModeHint() {
		if (isGtdMode) {
			if (!isRecording) {
				txtHint.setText(R.string.gtd_tap_to_talk);
			}
			btnGtdMode.setAlpha(1.0f);
		} else {
			if (!isRecording) {
				txtHint.setText(R.string.tap_to_record);
			}
			btnGtdMode.setAlpha(0.7f);
		}
	}

	private void startRecording() {
		if (checkRecordPermission()) {
			if (checkStoragePermission()) {
				startRecordingService();
				presenter.pauseUnpauseRecording(getApplicationContext());
			}
		}
	}

	private void stopRecording() {
		presenter.stopRecording();
	}

	public void startRecordingService() {
		try {
			String path = fileRepository.provideRecordFile().getAbsolutePath();
			currentRecordingFile = new File(path);
			Intent intent = new Intent(getApplicationContext(), RecordingService.class);
			intent.setAction(RecordingService.ACTION_START_RECORDING_SERVICE);
			intent.putExtra(RecordingService.EXTRAS_KEY_RECORD_PATH, path);
			startService(intent);
		} catch (CantCreateFileException e) {
			showError(ErrorParser.parseException(e));
		}
	}

	private boolean checkRecordPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD_AUDIO);
				return false;
			}
		}
		startWakeWordServiceIfReady();
		return true;
	}

	private boolean checkStoragePermission() {
		// For Android 10+, we don't need storage permission if using private directory
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			return true;
		}
		if (presenter.isStorePublic()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
					requestPermissions(
							new String[]{
									Manifest.permission.WRITE_EXTERNAL_STORAGE,
									Manifest.permission.READ_EXTERNAL_STORAGE},
							REQ_CODE_WRITE_EXTERNAL_STORAGE);
					return false;
				}
			}
		}
		return true;
	}

	private void checkNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_CODE_POST_NOTIFICATIONS);
			}
		}
	}

	private void checkPermissions() {
		checkNotificationPermission();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQ_CODE_RECORD_AUDIO && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			if (checkStoragePermission()) {
				startRecordingService();
			}
			startWakeWordServiceIfReady();
		} else if (requestCode == REQ_CODE_WRITE_EXTERNAL_STORAGE && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED
				&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
			if (checkRecordPermission()) {
				startRecordingService();
			}
			startWakeWordServiceIfReady();
		}
	}

	private void startWakeWordServiceIfReady() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
				return;
			}
		}
		Intent intent = new Intent(getApplicationContext(), AriWakeWordService.class);
		ContextCompat.startForegroundService(getApplicationContext(), intent);
	}

	// MainContract.View implementation
	@Override
	public void keepScreenOn(boolean on) {
		if (on) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
	}

	@Override
	public void showProgress() {
		// Not needed for minimalist UI
	}

	@Override
	public void hideProgress() {
		// Not needed for minimalist UI
	}

	@Override
	public void showError(String message) {
		Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showError(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showMessage(int resId) {
		Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();
	}

	@Override
	public void showRecordingStart() {
		isRecording = true;
		animatedCircle.setRecording(true);
		txtRecordingTime.setVisibility(View.VISIBLE);
		txtHint.setText(R.string.tap_to_stop);
		txtRecordingTime.setText(TimeUtils.formatTimeIntervalHourMinSec2(0));
	}

	@Override
	public void showRecordingStop() {
		isRecording = false;
		animatedCircle.setRecording(false);
		txtRecordingTime.setVisibility(View.GONE);
		txtHint.setText(R.string.tap_to_record);
	}

	@Override
	public void showRecordingPause() {
		// Not used in minimalist version
	}

	@Override
	public void showRecordingResume() {
		// Not used in minimalist version
	}

	@Override
	public void askRecordingNewName(long id, java.io.File file, boolean showCheckbox) {
		// Auto-generate name, no dialog needed
	}

	@Override
	public void onRecordingProgress(long mills, int amp) {
		runOnUiThread(() -> {
			txtRecordingTime.setText(TimeUtils.formatTimeIntervalHourMinSec2(mills));
			animatedCircle.updateAmplitude(amp);
		});
	}

	@Override
	public void startWelcomeScreen() {
		// Skip welcome screen in minimalist version
	}


	@Override
	public void startPlaybackService(String name) {
		// Not used in minimalist version
	}

	@Override
	public void showPlayStart(boolean animate) {
		// Not used in minimalist version
	}

	@Override
	public void showPlayPause() {
		// Not used in minimalist version
	}

	@Override
	public void showPlayStop() {
		// Not used in minimalist version
	}

	@Override
	public void showWaveForm(int[] waveForm, long duration, long playbackMills) {
		// Not used in minimalist version
	}

	@Override
	public void waveFormToStart() {
		// Not used in minimalist version
	}

	@Override
	public void showDuration(String duration) {
		// Not used in minimalist version
	}

	@Override
	public void showRecordingProgress(String progress) {
		// Not used, using onRecordingProgress instead
	}

	@Override
	public void showName(String name) {
		// Not used in minimalist version
	}

	@Override
	public void showInformation(String info) {
		// Not used in minimalist version
	}

	@Override
	public void decodeRecord(int id) {
		// Not used in minimalist version
	}

	@Override
	public void askDeleteRecord(String name) {
		// Not used in minimalist version
	}

	@Override
	public void showRecordInfo(com.dimowner.audiorecorder.app.info.RecordInfo info) {
		// Not used in minimalist version
	}

	@Override
	public void updateRecordingView(com.dimowner.audiorecorder.IntArrayList data, long durationMills) {
		// Not used in minimalist version
	}

	@Override
	public void showRecordsLostMessage(java.util.List<com.dimowner.audiorecorder.data.database.Record> list) {
		// Not used in minimalist version
	}

	@Override
	public void shareRecord(com.dimowner.audiorecorder.data.database.Record record) {
		// Not used in minimalist version
	}

	@Override
	public void openFile(com.dimowner.audiorecorder.data.database.Record record) {
		// Not used in minimalist version
	}

	@Override
	public void downloadRecord(com.dimowner.audiorecorder.data.database.Record record) {
		// Not used in minimalist version
	}

	@Override
	public void showMigratePublicStorageWarning() {
		// Not used in minimalist version
	}

	@Override
	public void showRecordFileNotAvailable(String path) {
		// Not used in minimalist version
	}

	@Override
	public void onPlayProgress(long mills, int percent) {
		// Not used in minimalist version
	}

	@Override
	public void showImportStart() {
		// Not used in minimalist version
	}

	@Override
	public void hideImportProgress() {
		// Not used in minimalist version
	}

	@Override
	public void showOptionsMenu() {
		// Not used in minimalist version
	}

	@Override
	public void hideOptionsMenu() {
		// Not used in minimalist version
	}

	@Override
	public void showRecordProcessing() {
		// Not used in minimalist version
	}

	@Override
	public void hideRecordProcessing() {
		// Not used in minimalist version
	}
}

