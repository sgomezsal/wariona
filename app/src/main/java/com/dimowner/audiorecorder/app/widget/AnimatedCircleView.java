/*
 * Copyright 2024
 * Minimalist Audio Recorder - Animated Circle View
 */

package com.dimowner.audiorecorder.app.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

import com.dimowner.audiorecorder.R;

public class AnimatedCircleView extends View {

	private Paint circlePaint;
	private Paint outerCirclePaint;
	private float centerX;
	private float centerY;
	private float baseRadius;
	private float currentRadius;
	private float outerRadius;
	private ValueAnimator pulseAnimator;
	private ValueAnimator outerCircleAnimator;
	private boolean isRecording = false;
	private float audioAmplitude = 0f; // 0.0 to 1.0

	public AnimatedCircleView(Context context) {
		super(context);
		init();
	}

	public AnimatedCircleView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public AnimatedCircleView(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	private void init() {
		// Main circle paint
		circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		circlePaint.setColor(ContextCompat.getColor(getContext(), R.color.md_black_1000));
		circlePaint.setStyle(Paint.Style.FILL);

		// Outer circle paint (for pulse effect)
		outerCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		outerCirclePaint.setColor(ContextCompat.getColor(getContext(), R.color.md_black_1000));
		outerCirclePaint.setStyle(Paint.Style.STROKE);
		outerCirclePaint.setStrokeWidth(4f);
		outerCirclePaint.setAlpha(128);

		baseRadius = 80f; // Base radius in dp, will be converted to pixels
		currentRadius = baseRadius;
		outerRadius = baseRadius;

		// Convert dp to pixels
		float density = getContext().getResources().getDisplayMetrics().density;
		baseRadius = baseRadius * density;
		currentRadius = baseRadius;
		outerRadius = baseRadius;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		centerX = w / 2f;
		centerY = h / 2f;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);

		// Draw outer pulse circle (when recording)
		if (isRecording && outerRadius > baseRadius) {
			float alpha = 128 * (1f - (outerRadius - baseRadius) / (baseRadius * 0.5f));
			outerCirclePaint.setAlpha((int) Math.max(0, Math.min(255, alpha)));
			canvas.drawCircle(centerX, centerY, outerRadius, outerCirclePaint);
		}

		// Draw main circle with audio-reactive size
		float radius = currentRadius;
		if (isRecording && audioAmplitude > 0) {
			// Scale radius based on audio amplitude (5% variation)
			radius = baseRadius * (1f + audioAmplitude * 0.05f);
		}
		canvas.drawCircle(centerX, centerY, radius, circlePaint);
	}

	public void setRecording(boolean recording) {
		if (isRecording != recording) {
			isRecording = recording;
			if (recording) {
				startPulseAnimation();
			} else {
				stopPulseAnimation();
				currentRadius = baseRadius;
				audioAmplitude = 0f;
				invalidate();
			}
		}
	}

	public void updateAmplitude(float amplitude) {
		// Normalize amplitude to 0.0-1.0 range
		// Audio amplitude typically ranges from 0 to 32767
		audioAmplitude = Math.min(1.0f, amplitude / 32767f);
		invalidate();
	}

	private void startPulseAnimation() {
		stopPulseAnimation();

		// Gentle breathing animation
		pulseAnimator = ValueAnimator.ofFloat(0.95f, 1.05f);
		pulseAnimator.setDuration(2000);
		pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
		pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
		pulseAnimator.setInterpolator(new LinearInterpolator());
		pulseAnimator.addUpdateListener(animation -> {
			float scale = (float) animation.getAnimatedValue();
			currentRadius = baseRadius * scale;
			invalidate();
		});
		pulseAnimator.start();

		// Outer circle pulse animation
		outerCircleAnimator = ValueAnimator.ofFloat(baseRadius, baseRadius * 1.3f);
		outerCircleAnimator.setDuration(1500);
		outerCircleAnimator.setRepeatCount(ValueAnimator.INFINITE);
		outerCircleAnimator.setRepeatMode(ValueAnimator.RESTART);
		outerCircleAnimator.setInterpolator(new LinearInterpolator());
		outerCircleAnimator.addUpdateListener(animation -> {
			outerRadius = (float) animation.getAnimatedValue();
			invalidate();
		});
		outerCircleAnimator.start();
	}

	private void stopPulseAnimation() {
		if (pulseAnimator != null) {
			pulseAnimator.cancel();
			pulseAnimator = null;
		}
		if (outerCircleAnimator != null) {
			outerCircleAnimator.cancel();
			outerCircleAnimator = null;
		}
		outerRadius = baseRadius;
	}

	public void setCircleColor(int color) {
		circlePaint.setColor(color);
		outerCirclePaint.setColor(color);
		invalidate();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		stopPulseAnimation();
	}
}

