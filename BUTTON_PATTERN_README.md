# Button Pattern Recording Feature

## Overview
This feature allows you to start and stop audio recording using a hardware button pattern, even when the app is closed. Press the volume button (up or down) 4 times quickly (within 2 seconds) to toggle recording.

## Setup Instructions

### 1. Enable Accessibility Service
1. Open the app
2. Tap the small settings icon in the top-left corner (subtle gray icon)
3. Tap "Enable Button Pattern Detection"
4. You'll be taken to Android Accessibility Settings
5. Find "Button Pattern Service" in the list
6. Toggle it ON
7. Confirm any warning dialogs

### 2. Grant Permissions
The app will request:
- **RECORD_AUDIO**: Required for audio recording
- **Notification Permission**: Required for foreground service (Android 13+)

### 3. Usage
- **Start Recording**: Press volume button (up or down) 4 times quickly (within 2 seconds)
- **Stop Recording**: Press volume button 4 times quickly again
- **Works in Background**: The service runs even when the app is closed
- **Notification**: A persistent notification shows when recording is active

## Technical Details

### Pattern Detection
- **Pattern**: 4 button presses
- **Time Window**: 2 seconds
- **Buttons**: Volume Up or Volume Down (either works)
- **Reset**: Pattern resets after detection or timeout

### Service Architecture
- **ButtonPatternService**: AccessibilityService that detects button presses
- **ButtonPatternDetector**: Detects the 4-press pattern within time window
- **RecordingService**: Handles recording start/stop via ACTION_TOGGLE_RECORDING
- **ForegroundService**: Ensures recording continues in background with notification

### Permissions Required
- `RECORD_AUDIO`: Audio recording
- `FOREGROUND_SERVICE`: Background service (Android 9+)
- `FOREGROUND_SERVICE_MICROPHONE`: Microphone foreground service (Android 13+)
- `WAKE_LOCK`: Keep device awake during recording
- `RECEIVE_BOOT_COMPLETED`: Restart service after reboot (optional)

### Limitations
- Requires Accessibility Service to be enabled manually
- Some device manufacturers may restrict volume button interception
- Volume buttons will still control volume (we don't consume the events)
- Pattern must be completed within 2 seconds

## Troubleshooting

### Service Not Detecting Button Presses
1. Verify Accessibility Service is enabled in Android Settings
2. Restart the app
3. Check if your device manufacturer allows volume button interception
4. Try both volume up and volume down buttons

### Recording Doesn't Start
1. Check if RECORD_AUDIO permission is granted
2. Verify there's available storage space
3. Check the notification - it will show recording status
4. Try using the UI button first to test recording functionality

### Service Disabled After Reboot
- Some devices may disable accessibility services after reboot
- Re-enable the service in Accessibility Settings if needed

## Files Added
- `ButtonPatternService.java`: Accessibility service for button detection
- `ButtonPatternDetector.java`: Pattern detection logic
- `ButtonPatternSettingsActivity.java`: Settings UI to enable service
- `button_pattern_service_config.xml`: Accessibility service configuration
- `activity_button_pattern_settings.xml`: Settings activity layout

## Integration
The feature is integrated into the minimalist UI:
- Small settings icon in top-left corner of main screen
- Works alongside the existing tap-to-record functionality
- Uses the same RecordingService for consistency

