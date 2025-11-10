/*
 * Copyright 2024
 * Button Pattern Detection Service
 * Detects volume button press patterns to trigger recording
 */

package com.dimowner.audiorecorder.app.buttonpattern;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import com.dimowner.audiorecorder.app.RecordingService;

import timber.log.Timber;

public class ButtonPatternService extends AccessibilityService {

	private static final int PATTERN_PRESS_COUNT = 4;
	private static final long PATTERN_TIME_WINDOW_MS = 2000; // 2 seconds
	private static final int VOLUME_DOWN_KEY_CODE = 25;
	private static final int VOLUME_UP_KEY_CODE = 24;

	private final ButtonPatternDetector patternDetector = new ButtonPatternDetector(
			PATTERN_PRESS_COUNT,
			PATTERN_TIME_WINDOW_MS
	);

	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
		// AccessibilityService doesn't directly receive key events
		// We need to use onKeyEvent instead
	}

	@Override
	public void onInterrupt() {
		Timber.d("ButtonPatternService interrupted");
	}

	@Override
	protected boolean onKeyEvent(android.view.KeyEvent event) {
		if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
			int keyCode = event.getKeyCode();
			
			// Only detect volume buttons
			if (keyCode == VOLUME_DOWN_KEY_CODE || keyCode == VOLUME_UP_KEY_CODE) {
				Timber.d("Volume button pressed: %d", keyCode);
				
				if (patternDetector.addPress(System.currentTimeMillis())) {
					// Pattern detected!
					Timber.d("Button pattern detected! Toggling recording...");
					toggleRecording();
				}
			}
		}
		
		// Return false to allow other apps to handle the event
		// Return true to consume the event (we don't want to block volume control)
		return false;
	}

	private void toggleRecording() {
		Intent intent = new Intent(this, RecordingService.class);
		intent.setAction(RecordingService.ACTION_TOGGLE_RECORDING);
		startService(intent);
	}

	@Override
	public void onServiceConnected() {
		super.onServiceConnected();
		Timber.d("ButtonPatternService connected");
	}
}

