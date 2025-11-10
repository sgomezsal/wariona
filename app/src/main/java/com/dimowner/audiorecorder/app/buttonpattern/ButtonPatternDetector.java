/*
 * Copyright 2024
 * Button Pattern Detector
 * Detects button press patterns within a time window
 */

package com.dimowner.audiorecorder.app.buttonpattern;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import timber.log.Timber;

public class ButtonPatternDetector {

	private final int requiredPressCount;
	private final long timeWindowMs;
	private final List<Long> pressTimestamps = new ArrayList<>();

	public ButtonPatternDetector(int requiredPressCount, long timeWindowMs) {
		this.requiredPressCount = requiredPressCount;
		this.timeWindowMs = timeWindowMs;
	}

	/**
	 * Add a button press timestamp and check if pattern is detected
	 * @param timestampMs Current timestamp in milliseconds
	 * @return true if pattern is detected, false otherwise
	 */
	public boolean addPress(long timestampMs) {
		// Remove old presses outside the time window
		cleanupOldPresses(timestampMs);
		
		// Add new press
		pressTimestamps.add(timestampMs);
		
		Timber.d("Press added. Total presses in window: %d/%d", 
				pressTimestamps.size(), requiredPressCount);
		
		// Check if we have enough presses
		if (pressTimestamps.size() >= requiredPressCount) {
			// Pattern detected! Reset for next detection
			pressTimestamps.clear();
			return true;
		}
		
		return false;
	}

	private void cleanupOldPresses(long currentTimeMs) {
		Iterator<Long> iterator = pressTimestamps.iterator();
		long cutoffTime = currentTimeMs - timeWindowMs;
		
		while (iterator.hasNext()) {
			Long timestamp = iterator.next();
			if (timestamp < cutoffTime) {
				iterator.remove();
			}
		}
	}

	/**
	 * Reset the detector (clear all stored presses)
	 */
	public void reset() {
		pressTimestamps.clear();
	}
}

