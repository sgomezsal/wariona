/*
 * Copyright 2024
 * Silence Detector
 * Detects sustained silence during audio recording
 */

package com.dimowner.audiorecorder.app;

import timber.log.Timber;

/**
 * Detects when audio amplitude stays below a threshold for a sustained duration.
 * 
 * CONFIGURATION:
 * - SILENCE_AMPLITUDE_THRESHOLD: Amplitude threshold (0-32767). Values below this are considered silence.
 *   Default: 500 (approximately 40 dB relative to max amplitude)
 *   Adjust this value to change sensitivity (lower = more sensitive to silence)
 * 
 * - SILENCE_DURATION_MS: Duration in milliseconds that silence must persist before triggering.
 *   Default: 2500ms (2.5 seconds)
 *   Adjust this value to change how long silence must last (higher = requires longer silence)
 */
public class SilenceDetector {

	// ===== CONFIGURATION - Adjust these values to customize silence detection =====
	
	/**
	 * Amplitude threshold below which audio is considered silence.
	 * MediaRecorder.getMaxAmplitude() returns 0-32767.
	 * Typical values:
	 *   - 0-100: Complete silence
	 *   - 100-500: Very quiet background noise
	 *   - 500-1000: Quiet room
	 *   - 1000-5000: Normal speech
	 *   - 5000+: Loud audio
	 * 
	 * Default: 500 (approximately equivalent to 40 dB relative to max)
	 */
	private static final int SILENCE_AMPLITUDE_THRESHOLD = 500;
	
	/**
	 * Duration in milliseconds that silence must persist before detection.
	 * Default: 2500ms (2.5 seconds)
	 * 
	 * Adjust this to:
	 *   - Lower values (1000-2000): More sensitive, stops recording faster
	 *   - Higher values (3000-5000): Less sensitive, requires longer silence
	 */
	private static final long SILENCE_DURATION_MS = 2500;
	
	// ===== END CONFIGURATION =====

	private Long silenceStartTime = null;
	private boolean enabled = false;

	/**
	 * Enable silence detection
	 */
	public void enable() {
		enabled = true;
		reset();
		Timber.d("Silence detection enabled. Threshold: %d, Duration: %dms", 
				SILENCE_AMPLITUDE_THRESHOLD, SILENCE_DURATION_MS);
	}

	/**
	 * Disable silence detection
	 */
	public void disable() {
		enabled = false;
		reset();
	}

	/**
	 * Reset the silence detector (clear current silence tracking)
	 */
	public void reset() {
		silenceStartTime = null;
	}

	/**
	 * Process an amplitude reading and check if silence is detected
	 * 
	 * @param amplitude Current audio amplitude (0-32767 from MediaRecorder.getMaxAmplitude())
	 * @param currentTimeMs Current timestamp in milliseconds
	 * @return true if sustained silence is detected, false otherwise
	 */
	public boolean checkSilence(int amplitude, long currentTimeMs) {
		if (!enabled) {
			return false;
		}

		// Check if current amplitude is below threshold (silence)
		if (amplitude < SILENCE_AMPLITUDE_THRESHOLD) {
			// If we're not already tracking silence, start tracking
			if (silenceStartTime == null) {
				silenceStartTime = currentTimeMs;
				Timber.d("Silence started. Amplitude: %d (threshold: %d)", 
						amplitude, SILENCE_AMPLITUDE_THRESHOLD);
			} else {
				// Check if silence has persisted long enough
				long silenceDuration = currentTimeMs - silenceStartTime;
				if (silenceDuration >= SILENCE_DURATION_MS) {
					Timber.d("Silence detected! Duration: %dms (threshold: %dms), Amplitude: %d", 
							silenceDuration, SILENCE_DURATION_MS, amplitude);
					reset();
					return true;
				}
			}
		} else {
			// Audio is above threshold, reset silence tracking
			if (silenceStartTime != null) {
				Timber.d("Silence interrupted. Amplitude: %d (threshold: %d)", 
						amplitude, SILENCE_AMPLITUDE_THRESHOLD);
				silenceStartTime = null;
			}
		}

		return false;
	}

	/**
	 * Get the current amplitude threshold
	 * @return Threshold value (0-32767)
	 */
	public int getThreshold() {
		return SILENCE_AMPLITUDE_THRESHOLD;
	}

	/**
	 * Get the silence duration threshold
	 * @return Duration in milliseconds
	 */
	public long getSilenceDurationMs() {
		return SILENCE_DURATION_MS;
	}
}

