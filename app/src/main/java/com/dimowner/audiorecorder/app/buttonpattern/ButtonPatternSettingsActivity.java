/*
 * Copyright 2024
 * Button Pattern Settings Activity
 * Helps user enable the accessibility service
 */

package com.dimowner.audiorecorder.app.buttonpattern;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import com.dimowner.audiorecorder.R;

import java.util.List;

public class ButtonPatternSettingsActivity extends Activity {

	private static final String ACCESSIBILITY_SERVICE_ID = 
			"com.dimowner.audiorecorder/com.dimowner.audiorecorder.app.buttonpattern.ButtonPatternService";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_button_pattern_settings);

		TextView instructions = findViewById(R.id.txt_instructions);
		Button btnEnable = findViewById(R.id.btn_enable_service);
		Button btnOpenSettings = findViewById(R.id.btn_open_settings);
		TextView statusText = findViewById(R.id.txt_status);

		instructions.setText(R.string.button_pattern_instructions);

		updateStatus(statusText);

		btnEnable.setOnClickListener(v -> {
			if (!isAccessibilityServiceEnabled()) {
				openAccessibilitySettings();
			} else {
				Toast.makeText(this, "Service is already enabled", Toast.LENGTH_SHORT).show();
			}
		});

		btnOpenSettings.setOnClickListener(v -> openAccessibilitySettings());
	}

	@Override
	protected void onResume() {
		super.onResume();
		TextView statusText = findViewById(R.id.txt_status);
		updateStatus(statusText);
	}

	private void updateStatus(TextView statusText) {
		if (isAccessibilityServiceEnabled()) {
			statusText.setText("Status: Enabled ✓");
			statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
		} else {
			statusText.setText("Status: Disabled");
			statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
		}
	}

	private boolean isAccessibilityServiceEnabled() {
		AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
		if (am == null) {
			return false;
		}

		List<AccessibilityServiceInfo> enabledServices = 
				am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

		for (AccessibilityServiceInfo service : enabledServices) {
			String serviceId = service.getResolveInfo().serviceInfo.packageName + "/" + 
					service.getResolveInfo().serviceInfo.name;
			if (TextUtils.equals(serviceId, ACCESSIBILITY_SERVICE_ID)) {
				return true;
			}
		}
		return false;
	}

	private void openAccessibilitySettings() {
		Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
		startActivity(intent);
		Toast.makeText(this, "Please enable 'Button Pattern Service' in the list", Toast.LENGTH_LONG).show();
	}
}

