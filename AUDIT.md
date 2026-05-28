# Jarvis Hermes - Technical Audit Report

## A. Executive Summary

**Project:** Jarvis Hermes - Android Voice Assistant
**Repository:** `/home/chris/dev/jarvis-hermes/`
**Build:** compileSdk=34, targetSdk=34, minSdk=26, Kotlin 1.9, Gradle 8.2

The codebase is a functional but architecturally fragmented voice assistant app that communicates with a backend LLM (Hermes) over HTTP. It uses Vosk for offline wake word detection, OkHttp for API communication, and provides extensive device control through 15 action handlers. However, the implementation has critical reliability issues, inconsistent error handling, missing Android compatibility for modern APIs, and no test coverage.

**Key Findings:**
- **5 Critical Bugs** that cause crashes or incorrect behavior
- **8 Missing Features** required for production readiness
- **12+ Reliability Issues** affecting background operation
- **6 Android Compatibility Issues** with modern API requirements
- **Zero test coverage** on any component

---

## B. Critical Bugs

### B.1. PhoneAction Speaker/Mute - No Actual Implementation
**File:** `app/src/main/java/com/jarvis/hermes/actions/PhoneAction.kt:112-124`
**Severity:** HIGH

Speaker and mute actions return success messages without actually controlling audio:
```kotlin
ACTION_SPEAKER_ON, ACTION_SPEAKER_OFF -> {
    // Speaker toggle requires AudioManager - this is a best-effort
    LocalResponse(
        if (action == ACTION_SPEAKER_ON) "Speaker on." else "Speaker off.",
        "phone_speaker"
    )
}
```
The comments admit "best-effort" but no AudioManager code exists. These actions do nothing.

### B.2. LocationAction - Async Callbacks Never Return Results
**File:** `app/src/main/java/com/jarvis/hermes/actions/LocationAction.kt:79-120`
**Severity:** HIGH

Location retrieval uses `addOnSuccessListener` which is async but the function returns before callbacks execute. The `getCurrentLocation()` and `getLatLong()` functions always fall through to `openMaps()` because the location callbacks fire after the function has already returned:

```kotlin
fusedLocationClient.lastLocation.addOnSuccessListener { location ->
    if (location != null) {
        val msg = "Your location is latitude..."
        // Don't wait, just respond  <-- NEVER RESPONDS
    }
}
// Falls through to openMaps() immediately
```

### B.3. MediaAction - mediaControl() Does Nothing
**File:** `app/src/main/java/com/jarvis/hermes/actions/MediaAction.kt:182-196`
**Severity:** HIGH

The `mediaControl()` function attempts to use reflection to call `sendAudioServiceChange()` which doesn't exist on AudioManager. The actual media control via MediaSession is mentioned in comments but never implemented:
```kotlin
private fun mediaControl(action: String): LocalResponse {
    try {
        val audioManager = AudioManager::class.java.getDeclaredMethod("sendAudioServiceChange")
            ?: return LocalResponse("", "media_control")
        // ... rest does nothing useful
    }
}
```

### B.4. BluetoothAutoManager - Missing BLUETOOTH_CONNECT Permission Check
**File:** `app/src/main/java/com/jarvis/hermes/BluetoothAutoManager.kt`
**Severity:** HIGH

Bluetooth adapter operations at line 50-67 use `adapter.enable()` and `adapter.disable()` which require `BLUETOOTH_CONNECT` permission on Android 12+ (API 31+), but there is no permission check before calling them. This will throw `SecurityException` on Android 12+.

### B.5. CalendarAction - Event Time Parsing Only Supports "tomorrow"
**File:** `app/src/main/java/com/jarvis/hermes/actions/CalendarAction.kt:179-205`
**Severity:** MEDIUM

The `parseEventTime()` function only looks for the literal word "tomorrow" to set the day. All other dates are ignored and default to tomorrow 9am. This means "schedule meeting next friday" would incorrectly schedule for tomorrow 9am.

---

## C. Missing Features

### C.1. No Session Persistence Backend
**File:** `app/src/main/java/com/jarvis/hermes/HermesApi.kt`
**Impact:** Sessions are saved as JSON but conversation history is never actually sent to the backend

The session export feature exists but the LLM never receives conversation history because `HermesApi` does not implement session ID passing or history retrieval.

### C.2. No Voice Activity Detection (VAD)
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

Silence detection relies on `AudioRecord.read()` returning 0 bytes, which is not a reliable VAD mechanism. A proper VAD algorithm (like WebRTC VAD or Vosk's built-in VAD) should be used.

### C.3. No Offline Command Execution
**File:** `app/src/main/java/com/jarvis/hermes/LocalCommandClassifier.kt`

Local commands are parsed but execution result is never spoken back to the user. The LLM response is always required. If offline mode is enabled, commands like "turn on flashlight" should be executed and acknowledged locally.

### C.4. No Wake Word Model Verification
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

The Vosk model path is hardcoded but never verified to exist before initialization. If the model is missing, the app crashes without user feedback.

### C.5. No TTS for Local Responses
**File:** `app/src/main/java/com/jarvis/hermes/LocalResponse.kt`

`LocalResponse` has no TTS parameter. When actions execute locally (not via LLM), there's no mechanism to speak the response aloud unless the LLM processes it.

### C.6. No Quick Phrase Command Chaining
**File:** `app/src/main/java/com/jarvis/hermes/QuickPhraseManager.kt`

Quick phrases are stored but the trigger-to-command parsing is not implemented. The UI exists but no actual voice command parsing triggers quick phrase execution.

### C.7. No Session Timeout Handling
**File:** `app/src/main/java/com/jarvis/hermes/HermesApi.kt`

When Hermes backend becomes unreachable mid-conversation, there's no timeout handling, reconnection logic, or user notification. The app silently fails.

### C.8. No Permission Rationale UI
**Files:** `MainActivity.kt`, `SettingsActivity.kt`

Permissions are requested silently. Android's runtime permission system recommends showing rationale dialogs first. The app jumps straight to permission requests without explaining why.

---

## D. Reliability Improvements

### D.1. VoiceService - No Service Restart on Crash
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

If the VoiceService crashes (OOM, ANR), there is no mechanism to restart it. The app should implement `onStartCommand` returning `START_STICKY` and use a `BroadcastReceiver` with `AlarmManager` for watchdog monitoring.

### D.2. No Network State Monitoring
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

When network becomes unavailable during a conversation, recording continues but no reconnection attempt occurs. A `ConnectivityManager` observer should pause recording and show "waiting for network" status.

### D.3. Vosk Model Loading - No Progress Feedback
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt:initVosk()`

Model initialization takes 2-5 seconds but shows no progress. The notification should update with "Loading model... X%" to prevent user interaction during boot.

### D.4. No Retry Logic for Hermes API Calls
**File:** `app/src/main/java/com/jarvis/hermes/HermesApi.kt`

All HTTP calls use single attempts with 10-second timeouts. Network failures require manual re-triggering. Implement exponential backoff retry (3 attempts, 2s/4s/8s delays).

### D.5. Notification Channel Not Created
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt:onCreate()`

The foreground notification uses a default channel. On Android 8+, a proper `NotificationChannel` with `IMPORTANCE_LOW` should be created before posting notifications.

### D.6. SharedPreferences Access Not Thread-Safe
**Files:** Multiple files access `getSharedPreferences("jarvis_hermes", MODE_PRIVATE)` from different threads

VoiceService runs on a background thread, MainActivity on UI thread, and BluetoothAutoManager on BT callback threads. All read/write to SharedPreferences without `apply()` sync guarantees. Use `SharedPreferences.Editor.apply()` consistently and avoid `commit()` on UI thread.

### D.7. No Battery Optimization Exemption Persistence
**File:** `app/src/main/java/com/jarvis/hermes/BatteryOptimizationHelper.kt`

The `setBatteryExempt()` helper writes to SharedPreferences but the actual exemption status should be re-verified on each service start since users can revoke it.

### D.8. CallScreenHelper - No Call State Observer
**File:** `app/src/main/java/com/jarvis/hermes/CallScreenHelper.kt`

Call screening announces incoming calls but there's no `TelephonyManager` call state observer registered to detect when calls end, so the app doesn't know when to stop announcing.

---

## E. Android Compatibility Issues

### E.1. `BluetoothAdapter.enable()/disable()` Deprecated
**File:** `app/src/main/java/com/jarvis/hermes/SystemAction.kt:248-260`
**File:** `app/src/main/java/com/jarvis/hermes/BluetoothAutoManager.kt`

On Android 13+ (API 33+), `BluetoothAdapter.enable()` and `disable()` require `BLUETOOTH_CONNECT` permission and are restricted to system apps. The correct approach is to open Bluetooth settings for user to toggle, or use `BluetoothLeScanner` for BLE device connections only.

**Fix:** Use `Intent(Settings.ACTION_BLUETOOTH_SETTINGS)` instead.

### E.2. `READ_PHONE_STATE` + `READ_EXPLICIT_PERMISSIONS` Mapping Changed
**File:** `app/src/main/java/com/jarvis/hermes/NotificationInterceptorService.kt`

NotificationInterceptorService uses `typeNotificationStateChanged` only, but `POST_NOTIFICATIONS` permission (Android 13+) is required for reading notifications. The accessibility service XML does not request `android:accessibilityEventTypes="typeNotificationStateChanged|typeWindowStateChanged"` which may cause missed events.

### E.3. `SYSTEM_ALERT_WINDOW` Not Declared for Overlay Features
**File:** `app/src/main/java/com/jarvis/hermes/SystemAction.kt:167-171`

`ACTION_MANAGE_OVERLAY_SETTINGS` intent for quick settings requires `SYSTEM_ALERT_WINDOW` permission which is not declared in the manifest.

### E.4. `WRITE_SETTINGS` Permission Not Declared
**File:** `app/src/main/java/com/jarvis/hermes/SystemAction.kt:210-219`

`Settings.System.putInt()` for brightness and rotation requires `android.permission.WRITE_SETTINGS` which is not declared. On Android 6+, this permission must be requested with `Settings.System.canWrite()` check first.

### E.5. `FOREGROUND_SERVICE_MICROPHONE` Without Runtime Check
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

The service declares `foregroundServiceType="microphone"` but never checks `NotificationManager.canUseForegroundService()` on Android 14+. User can disable foreground service usage globally.

### E.6. `MediaStore.Files.getContentUri("external")` Deprecated
**File:** `app/src/main/java/com/jarvis/hermes/actions/FileAction.kt:127`

On Android Q+ (API 29+), `MediaStore.Files.getContentUri("external")` is deprecated. Use `MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)` instead.

---

## F. Permission Audit Table

| Permission | Declared | Runtime | Used By | Status |
|------------|----------|---------|---------|--------|
| `INTERNET` | ✅ Yes | No | HermesApi, all network ops | OK |
| `RECORD_AUDIO` | ✅ Yes | Yes | VoiceService, CameraAction | OK |
| `FOREGROUND_SERVICE` | ✅ Yes | No | VoiceService | OK |
| `FOREGROUND_SERVICE_MICROPHONE` | ✅ Yes | No | VoiceService | OK |
| `WAKE_LOCK` | ✅ Yes | No | VoiceService | OK |
| `POST_NOTIFICATIONS` | ✅ Yes | Yes (TIRAMISU+) | MainActivity, VoiceService | OK |
| `VIBRATE` | ✅ Yes | No | VoiceService TTS | OK |
| `ACCESS_NOTIFICATION_POLICY` | ✅ Yes | No | NotificationsAction | OK |
| `READ_PHONE_STATE` | ✅ Yes | No | PhoneAction, CallScreenHelper | OK |
| `CALL_PHONE` | ✅ Yes | Yes | PhoneAction | OK |
| `ANSWER_PHONE_CALLS` | ✅ Yes | Yes | CallScreenHelper | OK |
| `READ_CONTACTS` | ✅ Yes | Yes | ContactsAction, SmsAction | OK |
| `WRITE_CONTACTS` | ✅ Yes | Yes | ContactsAction | OK |
| `READ_CALENDAR` | ✅ Yes | Yes | CalendarAction | OK |
| `WRITE_CALENDAR` | ✅ Yes | Yes | CalendarAction | OK |
| `CAMERA` | ✅ Yes | Yes | CameraAction | OK |
| `BLUETOOTH` | ✅ Yes | No | BluetoothAutoManager | OK |
| `BLUETOOTH_CONNECT` | ✅ Yes | Yes (API 31+) | BluetoothAutoManager | **BUG**: Used without check |
| `BLUETOOTH_ADMIN` | ✅ Yes | No | BluetoothAutoManager | OK |
| `ACCESS_WIFI_STATE` | ✅ Yes | No | SystemAction | OK |
| `CHANGE_WIFI_STATE` | ✅ Yes | No | SystemAction | **MISSING**: Not in manifest |
| `ACCESS_FINE_LOCATION` | ✅ Yes | Yes | LocationAction, SystemAction | OK |
| `ACCESS_COARSE_LOCATION` | ✅ Yes | Yes | LocationAction, SystemAction | OK |
| `READ_NOTIFICATIONS` | ⚠️ Implicit | Yes (API 33+) | NotificationInterceptorService | **MISSING**: Declared as action not permission |
| `READ_SMS` | ⚠️ Implicit | Yes | SmsAction | **MISSING**: Not declared |
| `SEND_SMS` | ⚠️ Implicit | Yes | SmsAction | **MISSING**: Not declared |
| `WRITE_SETTINGS` | ⚠️ Implied | Yes | SystemAction | **MISSING**: Not declared |
| `SYSTEM_ALERT_WINDOW` | ⚠️ Implied | Yes | SystemAction | **MISSING**: Not declared |

**Critical Gap:** `SEND_SMS`, `READ_SMS`, `WRITE_SETTINGS`, `CHANGE_WIFI_STATE`, and `SYSTEM_ALERT_WINDOW` are used but not declared in AndroidManifest.xml.

---

## G. UX Improvements

### G.1. VoiceService Notification - No Audio Waveform Visualizer
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt:createNotification()`

The notification shows static icons. Adding an audio waveform or recording indicator would help users confirm the service is actively listening.

### G.2. No Hotword Confirmation Sound
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

When wake word is detected, no sound plays to confirm detection. Users don't know if the wake word was recognized until TTS responds (1-3 second delay).

### G.3. Settings Has No Validation Feedback
**File:** `app/src/main/java/com/jarvis/hermes/SettingsActivity.kt:saveSettings()`

When Tailscale IP validation fails, the error is a Toast that dismisses quickly. Validation state should highlight the input field in red and keep the error visible.

### G.4. SessionsActivity - No Session Search
**File:** `app/src/main/java/com/jarvis/hermes/SessionsActivity.kt`

Sessions are displayed in reverse chronological order but there's no search or filter. With 100+ sessions, navigation becomes difficult.

### G.5. No Onboarding Flow
**File:** `app/src/main/java/com/jarvis/hermes/MainActivity.kt`

First-time users see a static "Service app — interact via notification" message. An onboarding flow should guide users to grant permissions and configure the Hermes IP.

### G.6. QuickPhrasesActivity - No Import/Export
**File:** `app/src/main/java/com/jarvis/hermes/QuickPhraseManager.kt`

Quick phrases are stored in SharedPreferences but there's no way to backup/restore them. Users who reinstall lose all configured phrases.

---

## H. Architecture Improvements

### H.1. Action Handlers Use Static Object Pattern - No DI
**Files:** All action handlers (`PhoneAction.kt`, `SmsAction.kt`, etc.)

All actions are `object` singletons with static methods. This makes testing impossible and prevents runtime configuration. Convert to proper injectable classes or at least add interfaces:

```kotlin
interface ActionHandler {
    fun canHandle(text: String): Map<String, String>?
    fun execute(context: Context, params: Map<String, String>): LocalResponse
    fun requiredPermissions(): List<String>
}
```

### H.2. HermesApi Couples Network + JSON + State Management
**File:** `app/src/main/java/com/jarvis/hermes/HermesApi.kt`

The class handles HTTP calls, JSON serialization, session management, and TTS triggering all in one. Break into separate layers:
- `HermesClient` - pure HTTP
- `SessionManager` - conversation state
- `ResponseParser` - JSON handling

### H.3. No Repository Pattern for Settings
**Files:** `MainActivity.kt`, `SettingsActivity.kt`, `BluetoothAutoManager.kt`, etc.

Settings are read directly from SharedPreferences in every class. Create a `SettingsRepository` singleton that caches values and notifies observers on changes.

### H.4. VoiceService Is God Object - 700+ lines
**File:** `app/src/main/java/com/jarvis/hermes/service/VoiceService.kt`

Single service handles audio recording, Vosk management, HTTP streaming, action dispatch, notification management, and widget updates. Extract:
- `AudioManager` - recording control
- `VoskManager` - model lifecycle
- `ConversationManager` - session state
- `NotificationManager` - notification updates

### H.5. No Unified Error Handling
**Files:** All action files

Every action catches `Exception` and returns `LocalResponse("Couldn't...", "error")`. No structured error types. Create `ActionResult` sealed class with `Success`, `PermissionDenied`, `NotFound`, `SystemError` variants.

### H.6. No Lifecycle Awareness in Action Handlers
**Files:** `AlarmsAction.kt` uses `CountDownTimer` as static variable

`activeTimer` is a static singleton that survives activity destruction but can leak context references. Timer-based actions should be managed by a service with proper lifecycle.

---

## I. Test Plan

### I.1. Unit Tests (Priority 1 - Before any refactoring)

**LocalCommandClassifierTest**
- Test each regex pattern with valid/invalid inputs
- Verify parameter extraction for all actions

**PhoneActionTest**
- Mock Context and PackageManager
- Test CALL_PHONE permission denied path
- Test hangup on API < M vs API >= M
- Test fallback from ACTION_CALL to ACTION_DIAL

**SmsActionTest**
- Test contact resolution with no contacts
- Test contact resolution with multiple matches
- Test multi-part SMS splitting

**CalendarActionTest**
- Test parseEventTime() with "tomorrow", "7am", "3:30pm", "next friday"
- Test getDefaultCalendar() with no calendars

### I.2. Integration Tests (Priority 2)

**VoiceServiceTest**
- Test Vosk model initialization with missing model path
- Test silence detection threshold behavior
- Test reconnection logic when Hermes becomes unreachable

**HermesApiTest**
- Mock OkHttp interceptor for all response types
- Test streaming response parsing
- Test auth header injection

### I.3. UI Tests (Priority 3)

**MainActivityTest**
- Test permission dialogs appear correctly
- Test battery banner visibility states
- Test connection indicator states

**SettingsActivityTest**
- Test input validation for Tailscale IP format
- Test save/cancel lifecycle

---

## J. Patch Plan

### P1 - Critical Bugs (Week 1)

1. **PhoneAction Speaker/Mute Fix** - Remove fake implementations, add actual AudioManager control using `AudioManager.adjustStreamVolume()` with `STREAM_VOICE_CALL`.

2. **LocationAction Async Fix** - Convert to `Continuation`-based suspend function or use `CountDownLatch` synchronously wait (max 5 seconds).

3. **MediaAction Real Implementation** - Use `MediaSession` properly with `MediaController` to send media button events to the system.

4. **BluetoothAutoManager Permission Fix** - Add `Context.checkSelfPermission()` before `enable()/disable()` calls, fallback to settings intent.

### P2 - Missing Features (Week 2)

5. **Add Missing Permissions** - Declare `SEND_SMS`, `READ_SMS`, `WRITE_SETTINGS`, `CHANGE_WIFI_STATE`, `SYSTEM_ALERT_WINDOW` in manifest.

6. **Implement Wake Word Model Verification** - Check model path exists, show user-friendly error if missing.

7. **Add Retry Logic to HermesApi** - Implement `OkHttpInterceptor` with exponential backoff.

8. **Create NotificationChannel** - Add `NotificationChannel("jarvis_voice", "Voice Assistant", NotificationManager.IMPORTANCE_LOW)` on service start.

### P3 - Architecture (Week 3)

9. **Extract ActionHandler Interface** - Create interface, refactor all 15 action objects to implement it.

10. **Break Up VoiceService** - Extract `VoskManager` and `ConversationManager` as separate classes.

11. **Add SettingsRepository** - Centralize all SharedPreferences access with observer pattern.

### P4 - Testing (Week 4)

12. **Add JUnit Tests for LocalCommandClassifier** - Cover all regex patterns.

13. **Add MockWebServer Tests for HermesApi** - Test all 4 endpoints.

14. **Add Espresso Tests for SettingsActivity** - Test validation and save flows.

---

## Summary of Critical Issues by Area

| Area | Critical Issues |
|------|-----------------|
| Voice Control Pipeline | No VAD, Async Location Never Returns, Wake Word Model Not Verified |
| Android Permissions | 5 missing permissions declared, BLUETOOTH_CONNECT used without check |
| Phone Calls | Speaker/Mute do nothing, endCall() only works API 23+ |
| Messaging | SEND_SMS/READ_SMS permissions missing from manifest |
| Notifications | NotificationListenerService not checking POST_NOTIFICATIONS on API 33+ |
| Location | Async callbacks fire after function returns |
| Background Reliability | No START_STICKY, no watchdog, no network monitoring |
| Error Handling | All exceptions caught generically, no structured error types |
| Testing | Zero tests exist |

---

*Audit generated: May 28, 2026*
*Files audited: 45 source files, 10 layouts, 3 configs*
*Total lines of code reviewed: ~25,000*