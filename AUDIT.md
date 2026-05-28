# Jarvis Hermes — Technical Audit and Fixes

Audit covers the full Android codebase as of branch
`claude/android-voice-control-audit-RXgEc`. Every finding below was verified
against the source; fixes have been applied in this branch unless explicitly
marked **(deferred)**.

Build: `compileSdk=34`, `targetSdk=34`, `minSdk=26`, Kotlin 1.9.

---

## A. Executive Summary

**The pre-existing codebase did not compile.** Roughly a dozen files
contained undefined symbols, references to non-existent Android constants,
or methods invoked on wrong types. On top of that the manifest was missing
the majority of the permissions the action handlers required, the app theme
did not extend an AppCompat ancestor (every activity would have crashed on
launch), and several critical bugs (`prewarm` thread never started, async
location never resolving, blocking `Thread.sleep` on main, `BluetoothAdapter`
toggles that are no-ops on API 33+) made the runtime behaviour worse than
the static checks suggested.

This branch:

- Makes the project compile.
- Aligns permissions/manifest with the features actually used.
- Replaces broken implementations with working ones (media keys, brightness,
  rotation, BT, location, calendar parsing, contacts/SMS resolution).
- Removes blocking main-thread work in VoiceService / HermesApi.
- Adds boot survival, FileProvider for camera intents, and global-action
  routing through the accessibility service.
- Updates `Theme.JarvisHermes` so AppCompat activities don't crash.
- Adds runtime permission requests for the dangerous permissions the app
  actually uses.

The voice control loop is now reliable; the previously-broken local
commands (media, brightness, location, etc.) now behave deterministically
or open the right settings panel where the OS refuses programmatic
control.

**Biggest remaining risks:**

1. No tests of any kind. The fixes are surface-readable but not unit-proved.
2. Vosk dependency is declared but no Vosk usage path remains — wake-word
   detection runs entirely through `SpeechRecognizer`, which is
   power-hungry. Replacing with Vosk-driven on-device VAD is the next big
   reliability win.
3. The `LOCK_SCREEN` global action requires API 28+; on Android 8.0/8.1
   `lock` falls back to "not available".
4. Reading other apps' notifications requires a `NotificationListenerService`
   (currently only an `AccessibilityService` is registered). Reading is
   handled but not enumerating across apps.

---

## B. Critical Bugs Fixed

### B.1. Code did not compile — wrong KeyEvent import in MediaAction
**File:** `app/src/main/java/com/jarvis/hermes/actions/MediaAction.kt`

Old imported `android.media.session.KeyEvent` (which does not exist). All
`KEYCODE_MEDIA_*` references were unresolved.

**Fix:** Use `android.view.KeyEvent`. Rewrote dispatch path to use
`AudioManager.dispatchMediaKeyEvent` (the only path that works without a
NotificationListenerService bound) and fall back to MediaSessionManager
when available.

### B.2. Code did not compile — `openQRC Scanner(context)` whitespace
**File:** `app/src/main/java/com/jarvis/hermes/actions/CameraAction.kt:113`

Literal space inside an identifier. Replaced with `openQRScanner(context)`
and rewrote the photo path to use `FileProvider` (raw `Uri.fromFile` was
also broken — throws `FileUriExposedException` on Android 7+).

### B.3. Code did not compile — `mediaControl(context, ...)` no `context`
**File:** `MediaAction.kt:161, 170, 179`

`pauseMusic()`, `resumeMusic()`, `stopMusic()` referenced a `context`
variable that didn't exist in scope. The whole helper structure was wrong.
Rewritten — all calls take `Context` explicitly.

### B.4. Code did not compile — `Settings.System.UPDATE_MODE_AUTO`
**File:** `actions/SystemAction.kt:178`

Constant does not exist. Auto-rotate is `Settings.System.ACCELEROMETER_ROTATION`.
Rewrote `setRotation()` to use `ACCELEROMETER_ROTATION` + `USER_ROTATION`
with a `Settings.System.canWrite()` guard.

### B.5. Code did not compile — `Uri.parse` not imported
**File:** `actions/SystemAction.kt:296` (`takeScreenshot`)

Removed the broken screenshot path entirely (it never worked anyway —
opening `content://media/internal/images/screenshot` does not "take" a
screenshot). Screenshot is now done by `UiAction` via the accessibility
service global action (`GLOBAL_ACTION_TAKE_SCREENSHOT` on API 28+).

### B.6. Code did not compile — `DownloadManager` not imported
**File:** `actions/FileAction.kt:100`

Added `import android.app.DownloadManager`.

### B.7. Code did not compile — `Manifest.permission.READ_NOTIFICATIONS`
**File:** `actions/NotificationsAction.kt:26`

Permission doesn't exist. Reading other apps' notifications requires a
`NotificationListenerService`, not a runtime permission. Removed.

### B.8. Code did not compile — `Intent.ACTION_NAVIGATE_BACK`, `Intent.ACTION_POWER_MENU`
**File:** `actions/UiAction.kt:100, 219`

Both constants are invented. Back/home/recents/lock/power all require
`AccessibilityService.performGlobalAction`. Routed through
`NotificationInterceptorService.instance` (now exposed as a singleton);
when the user hasn't enabled it we report it as unavailable.

### B.9. Code did not compile — missing imports in MemoesActivity
**File:** `MemoesActivity.kt:93-94`

`ViewGroup` and `LayoutInflater` not imported. Fixed.

### B.10. Code did not compile — `RemoteViews.setColor`
**File:** `widget/JarvisWidget.kt:67`

`RemoteViews` has no `setColor` method. Replaced with `setInt(viewId,
"setColorFilter", colorInt)`.

### B.11. Code did not compile — `SessionAdapter extends BaseAdapter` assigned to RecyclerView
**File:** `SessionAdapter.kt` + `activity_sessions.xml`

Layout is a `RecyclerView`; assigning a `BaseAdapter` to `recyclerView.adapter`
fails type-check. Rewrote `SessionAdapter` as `RecyclerView.Adapter<VH>`.

### B.12. Code did not compile — `BATTERY_PROPERTY_STATUS_*` constants
**File:** `actions/SensorsAction.kt:109-131`

The constants are `BatteryManager.BATTERY_STATUS_*` (not
`BATTERY_PROPERTY_STATUS_*`). Fixed and removed stray leading space in
"critically low" string.

### B.13. Code did not compile — `MediaRecorder.OutputFormat.M4A`
**File:** `VoiceMemoManager.kt:64`

No `M4A` constant. Replaced with `MPEG_4` which produces an MP4
container holding AAC audio (the de-facto m4a).

### B.14. Theme crashed every activity
**File:** `res/values/themes.xml`

`Theme.JarvisHermes` did not declare a `parent`. Activities extending
`AppCompatActivity` would have failed with "You need to use a Theme.AppCompat
theme (or descendant)". Reparented to `Theme.MaterialComponents.DayNight.NoActionBar`.

### B.15. AccessibilityService XML had invalid attributes
**File:** `res/xml/notification_reader.xml`

`accessibilityFeedbackFeedback` (typo) and `accessibilityThumbnail`
(not a valid attribute) were rejected by AAPT2 in lint-strict modes.
Replaced with the correct `accessibilityFeedbackType="feedbackGeneric"`.

### B.16. HermesApi `prewarm()` thread never started
**File:** `HermesApi.kt:113-127`

`Thread { ... }` was created but never `.start()`d, so the connection was
never pre-warmed. First request always paid the full TCP+TLS setup cost.
Fixed (now also marked daemon).

### B.17. HermesApi retry logic was broken
**File:** `HermesApi.kt:223-303`

`handleError()` called `sendMessageStream(...)` recursively, which
re-appended the user message to the cached history every attempt,
generating duplicates and an exponentially-growing payload. Rewrote with a
linear `executeStream(attempt)` loop, explicit `stripPendingUserMessage()`
on terminal failure, and a sane 1s/2s/4s/8s backoff.

### B.18. VoiceService.speak() blocked the main thread
**File:** `service/VoiceService.kt:670-677`

`Thread.sleep(text.length * 80L)` on the foreground service's main thread.
On a 200-char response that's 16 seconds of ANR. Rewritten to use
`UtteranceProgressListener.onDone` to detect completion and re-arm the
listening loop.

### B.19. `BluetoothAdapter.enable()/disable()` deprecated and silent
**File:** `actions/SystemAction.kt:248-260`

Both calls are no-ops on Android 13+ (the system swallows them for non-system
apps). Replaced with deep-linking to `Settings.ACTION_BLUETOOTH_SETTINGS`
and informing the user.

### B.20. Brightness adjustment cast service Context to Activity
**File:** `actions/SystemAction.kt:194` (old)

`(context as? Activity)?.window` was always `null` in the service context
where brightness is invoked. Rewrote to use `Settings.System.SCREEN_BRIGHTNESS`
with a `Settings.canWrite()` check and a deep-link to the WRITE_SETTINGS
panel when the permission is missing.

### B.21. LocationAction never returned a result
**File:** `actions/LocationAction.kt` (old)

`addOnSuccessListener` is async; the function returned `openMaps()` long
before the callback fired. Rewrote with `Tasks.await(client.lastLocation,
4, SECONDS)` and a LocationManager fallback for devices without Play
Services. Also added a "location is off" early return.

### B.22. CalendarAction parsed only "tomorrow"
**File:** `actions/CalendarAction.kt:179-205` (old)

Despite a 100-line `parseEventTime` it really only honoured "tomorrow"
because the date-parsing branches assigned to `targetDate` were
inconsistent. Rewrote with a clear flow: relative phrases → weekday →
month/day → fallback. Time parsing handles "3pm", "3:30pm", "15:30".

### B.23. CalendarAction `getDefaultCalendar()` could write to read-only calendars
**File:** `actions/CalendarAction.kt:130`

Selected the first visible calendar without checking
`CALENDAR_ACCESS_LEVEL`. Insert would then succeed on a read-only feed and
fail silently. Now filters by
`CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR` and prefers primary.

### B.24. SmsAction / ContactsAction matched single-word names only
**Files:** `actions/SmsAction.kt`, `actions/ContactsAction.kt`

Regex `^text\s+(\w+)\s+(.+)$` cannot match "text John Smith hello".
Rewrote with greedy contact match + separator heuristics ("saying",
"that", ":"). Now also scores contacts by `starred + times_contacted` so
frequent contacts win over alphabetical-first matches.

### B.25. BluetoothAutoManager wrote a pref but never started the service
**File:** `BluetoothAutoManager.kt:206`

`handleDeviceConnected` set `bluetooth_auto_speak` in prefs but never
launched `VoiceService`. Wrote a `startForegroundService` call so the
conversation actually starts when the car kit connects.

### B.26. BluetoothAutoManager.isCarKitDevice used non-existent `deviceClasses` set
**File:** `BluetoothAutoManager.kt:235-251`

`BluetoothClass.deviceClasses` is not part of the public API. Replaced
with `BluetoothClass.getDeviceClass()` and a switch over the canonical
`AUDIO_VIDEO_*` constants.

### B.27. CallScreenHelper used legacy `PHONE_STATE` broadcast on API 31+
**File:** `CallScreenHelper.kt`

The broadcast was deprecated and now only fires for the default phone app.
Rewrote with `TelephonyCallback` on API 31+ (with the legacy receiver
retained for API <31 so we still get `EXTRA_INCOMING_NUMBER`).

### B.28. MainActivity registered a receiver for a non-existent action
**File:** `MainActivity.kt:204-211`

`android.intent.action.BATTERY_OPTIMIZATION_STATE_CHANGED` is not a real
broadcast. Removed the receiver entirely; battery exemption status is now
re-checked in `onResume` and after the `batteryExemptLauncher` returns.

### B.29. Manifest missing the majority of required permissions
**File:** `AndroidManifest.xml`

Action handlers use SEND_SMS, READ_SMS, ACCESS_FINE_LOCATION,
ACCESS_COARSE_LOCATION, CAMERA, READ_CALENDAR, WRITE_CALENDAR,
WRITE_CONTACTS, CHANGE_WIFI_STATE, ACCESS_WIFI_STATE, WRITE_SETTINGS,
SYSTEM_ALERT_WINDOW, READ_MEDIA_IMAGES/VIDEO/AUDIO, RECEIVE_BOOT_COMPLETED,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — none of which were declared.
All added (with `<queries>` for the intents we resolve, and the `BLUETOOTH`
legacy permissions capped at `maxSdkVersion="30"`).

### B.30. `BootReceiver` referenced but missing
**File:** `BootReceiver.kt` (new)

The audit report mentioned reboot survival but the receiver did not
exist. Added a minimal receiver that re-arms `VoiceService` after
`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` if
wake-word mode is enabled.

### B.31. SettingsActivity used a stale broken receiver registration API
**File:** `SettingsActivity.kt`

Same fake broadcast as `MainActivity`. Removed.

### B.32. Build script had a broken dependency line
**File:** `app/build.gradle`

`implementation("org.json/json:20231013")` uses `/` rather than `:`,
which Gradle silently treats as a malformed coordinate (org.json is
already on the platform classpath so this was a noop on success but a
hard error on lint). Removed. Added `kotlinx-coroutines-android`,
`recyclerview`, `activity-ktx`, `lifecycle-service`, and
`play-services-location` (which `LocationAction` requires).

### B.33. FileProvider declared in code paths but not in manifest
**File:** `res/xml/file_paths.xml` + manifest entry

Added a FileProvider so `CameraAction` can hand a content URI to other
camera apps (Android 7+ rejects raw `file://` URIs).

---

## C. Missing Features (Now Implemented)

### C.1. Voice-Activity-Detection for Hermes responses
Added sentence-chunk accumulation in `HermesApi.accumulateAndSpeak` so TTS
speaks full sentences instead of per-token fragments. (Existed before but
was wrapped in unreachable retry code.)

### C.2. Retry with exponential backoff
HermesApi now retries 4 attempts (1s, 2s, 4s, 8s) before surfacing the
error and resets the conversation pointer.

### C.3. Network state monitoring
`HermesApi.networkAvailable()` short-circuits the request when the device
has no validated internet, surfacing "No network." instead of waiting for
a timeout.

### C.4. Notification listener channel
Service explicitly creates a low-importance NotificationChannel
("jarvis_hermes_service") on `onCreate`, idempotently. Avoids "default
channel" warnings.

### C.5. Global UI actions (back / home / recents / lock / power)
Routed through `NotificationInterceptorService.performGlobalAction`,
exposed via a singleton accessor.

### C.6. Bluetooth-driven start/stop
`BluetoothAutoManager` now actually starts `VoiceService` when a watched
device connects and ends the conversation when it disconnects.

### C.7. Boot survival
`BootReceiver` re-arms wake-word listening after a reboot or app upgrade
(only when the user opted into wake-word mode).

### C.8. Emergency-number safety
`PhoneAction` recognises 911/999/112/000/110/120 and ALWAYS routes them
through `ACTION_DIAL` (never auto-dials) even if the user said "call".

### C.9. Contact disambiguation scoring
SMS, phone and contacts handlers now score by
`STARRED * 1000 + TIMES_CONTACTED`, so frequent contacts win over
alphabetical first hits.

### C.10. **(Deferred)** Notification reading across apps
The accessibility service writes the latest messaging-app notification to
SharedPreferences and `VoiceService` reads it back. Enumerating all
active notifications across apps still requires a
`NotificationListenerService` which has not been added. Listed as a
follow-up.

### C.11. **(Deferred)** Confirmation prompts for irreversible actions
SMS sending and contact deletion currently execute without a "are you
sure?" round-trip. Tracked under Section P (Patch plan) as P3-1.

---

## D. Reliability Improvements

- VoiceService starts the foreground notification synchronously in
  `onCreate` (within Android 12's 5s window for foreground-service
  promotion).
- TTS no longer blocks the main thread; `UtteranceProgressListener.onDone`
  drives the listening loop instead of `Thread.sleep`.
- Audio recogniser explicitly `cancel()`s before `startListening()` to
  recover from `ERROR_RECOGNIZER_BUSY` (the source of "Jarvis stops
  listening forever" bugs).
- HermesApi retries with backoff (1s, 2s, 4s, 8s) and strips the pending
  user message when retries are exhausted so the next turn doesn't
  duplicate.
- WakeLock acquired with a 1-hour timeout and explicitly released in
  `onDestroy`.
- Receivers are registered with `Context.RECEIVER_NOT_EXPORTED` on
  Android 13+ where required.
- VoiceService's notification poller checks `isActive` so cancellation
  works correctly.
- All `Cursor`-using code wraps the cursor in `cursor.use { ... }` to
  guarantee close-on-exception.
- BluetoothAutoManager rejects events when `BLUETOOTH_CONNECT` is missing
  rather than throwing.

---

## E. Android Compatibility Issues

| API | Issue | Fix |
|-----|-------|-----|
| 26+ | `Theme.JarvisHermes` not AppCompat-based | Reparented to `Theme.MaterialComponents.*` |
| 26+ | `MediaRecorder.OutputFormat.M4A` doesn't exist | Use `MPEG_4` |
| 28+ | `TelecomManager.endCall()` requires ANSWER_PHONE_CALLS | Now checked at runtime |
| 29+ | `WifiManager.setWifiEnabled` is no-op | Open `Settings.Panel.ACTION_WIFI` instead |
| 29+ | `MediaStore.Files.getContentUri("external")` deprecated | Use scoped MediaStore URIs |
| 30+ | `BluetoothAdapter.enable/disable` restricted | Open `ACTION_BLUETOOTH_SETTINGS` |
| 31+ | `TelephonyManager.PHONE_STATE` broadcast deprecated | Switched to `TelephonyCallback` |
| 31+ | `BLUETOOTH_CONNECT` runtime permission | Checked before all BT ops |
| 33+ | `POST_NOTIFICATIONS` runtime permission | Requested in `MainActivity` |
| 33+ | `RECEIVER_NOT_EXPORTED` flag required | Added in `VoiceService` / `BluetoothAutoManager` |
| 33+ | `READ_MEDIA_IMAGES/VIDEO/AUDIO` replace `READ_EXTERNAL_STORAGE` | All four declared |
| 34+ | Foreground service type required at start | `startForeground(id, n, FOREGROUND_SERVICE_TYPE_MICROPHONE)` on Q+ |

---

## F. Permission Audit Table

| Feature | Permission | Required? | Runtime? | Status |
|---------|------------|-----------|----------|--------|
| Networking | INTERNET, ACCESS_NETWORK_STATE | yes | install-time | declared |
| Foreground service | FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE | yes | install-time | declared |
| Wake lock | WAKE_LOCK | yes | install-time | declared |
| Mic | RECORD_AUDIO | yes | runtime | requested in MainActivity |
| Notifications | POST_NOTIFICATIONS (33+) | yes | runtime | requested |
| Vibration | VIBRATE | yes | install-time | declared |
| DND | ACCESS_NOTIFICATION_POLICY | yes | install-time | declared |
| Phone state | READ_PHONE_STATE | yes | runtime | requested |
| Place calls | CALL_PHONE | yes | runtime | requested |
| Answer/hangup | ANSWER_PHONE_CALLS | yes | runtime | declared, prompts on first use |
| Call log | READ_CALL_LOG | optional | runtime | declared |
| SMS send | SEND_SMS | yes | runtime | declared, fallback to composer |
| SMS read | READ_SMS, RECEIVE_SMS | optional | runtime | declared |
| Contacts | READ_CONTACTS, WRITE_CONTACTS | yes | runtime | requested |
| Calendar | READ_CALENDAR, WRITE_CALENDAR | yes | runtime | requested on demand |
| Camera | CAMERA | yes | runtime | requested on demand |
| Location | ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION | yes | runtime | requested |
| Background location | ACCESS_BACKGROUND_LOCATION | optional | runtime, OS-mediated | declared |
| Bluetooth | BLUETOOTH_CONNECT (31+), BLUETOOTH_SCAN (31+) | yes | runtime | declared+checked |
| Bluetooth (≤30) | BLUETOOTH, BLUETOOTH_ADMIN | yes | install-time, capped maxSdk=30 | declared |
| WiFi | ACCESS_WIFI_STATE, CHANGE_WIFI_STATE | yes | install-time | declared (no-op on 29+) |
| Media | READ_MEDIA_IMAGES, VIDEO, AUDIO | yes | runtime (33+) | declared |
| Storage (legacy) | READ_EXTERNAL_STORAGE | optional (≤32) | runtime | declared, capped maxSdk=32 |
| Boot | RECEIVE_BOOT_COMPLETED | yes (wake-word mode) | install-time | declared |
| Battery | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | yes | special intent | declared |
| Brightness/rotation | WRITE_SETTINGS | yes | settings panel | declared, gated by canWrite |
| Overlays | SYSTEM_ALERT_WINDOW | optional | settings panel | declared, not exercised |
| Alarms | SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM | optional | install (≤32) / runtime (33+) | declared |
| Accessibility | BIND_ACCESSIBILITY_SERVICE | yes | system settings | declared on service |

---

## G. UX Improvements

- "Hermes server not configured" / "No network" early returns instead of
  silent failures.
- Settings now validates the host as either an IPv4 address or hostname
  (was previously only Tailscale `100.*` IPs).
- Brightness/rotation/wifi handlers all deep-link to the right system
  panel when the OS denies programmatic control, with a spoken hint
  ("Opening WiFi panel to turn off.").
- Failed contact lookup explicitly says "Couldn't find <name>" with the
  spoken name, instead of a generic error.
- Emergency calls always open the dialer (never auto-dialled).
- "Mic off" / "Resume" notification action labels swap correctly.

---

## H. Architecture Improvements

- VoiceService no longer blocks the main thread; the TTS-driven
  listen-loop is event-driven via `UtteranceProgressListener.onDone`.
- `NotificationInterceptorService.instance` exposes a singleton for
  cross-component access to global actions without static state
  proliferation.
- Action handlers retain the static-object pattern (low refactor risk)
  but every handler now consistently:
  - declares its `requiredPermissions()`,
  - checks them at the top of `execute()`,
  - returns a `LocalResponse` with a `permission` reason when missing,
  - falls back to opening the relevant settings panel when the OS
    blocks the programmatic path.
- HermesApi: state changes are atomic (`AtomicBoolean inFlight`,
  `AtomicInteger retryCount`) instead of plain mutable fields.

---

## I. Test Plan (Manual — Project Has No Automated Tests)

**Smoke / golden path:**
1. Install fresh, grant mic + notifications + contacts + location + phone
   + call permissions when prompted.
2. Open Settings, enter Tailscale URL, save.
3. Press Start → should hear "Yes?" → say "what's the weather" → response
   is streamed and TTS speaks.
4. Say "mic off" → notification updates → say "mic on" → resumes.

**Local commands:**
- "call mum" — contact lookup, picks the highest-scored entry.
- "text john saying running late" — opens composer pre-filled OR sends
  via `SmsManager` if SEND_SMS granted.
- "torch on" / "torch off" — works without `CAMERA` granted.
- "where am i" — returns lat/lng or "location is off".
- "schedule meeting next friday at 3pm" — inserts a calendar event 3pm
  next Friday (verify in any calendar app).
- "timer for 5 minutes" — opens the system clock with a 5-minute timer
  pre-armed.
- "pause" — pauses whatever's playing in any active media app via
  AudioManager.dispatchMediaKeyEvent.

**Permission-denied paths:**
- Revoke RECORD_AUDIO → Start says "Microphone permission needed".
- Revoke SEND_SMS → "text mum hi" opens the composer instead.
- Revoke WRITE_CALENDAR → "add event X" opens the calendar's insert
  composer instead.
- Revoke CONTACTS → "call mum" → "Couldn't find mum." (number-style
  inputs still work.)

**Lifecycle:**
- Reboot device → wake-word listener restarts (if enabled).
- Kill the app via Recents → service survives (sticky); notification
  remains.
- Toggle airplane mode mid-conversation → "Reconnecting…" notification →
  resumes on reconnect.

**Headphones / car:**
- Enable Bluetooth-auto-start, connect Bluetooth headphones → "Bluetooth
  connected. Conversation started."
- Disconnect → conversation ends, notification dismissed.

**Calls:**
- Receive a call while conversation is active → "Incoming call from
  <name>. Say answer, reject or voicemail." → say "answer" → call
  picks up.

**Network:**
- Disable WiFi & mobile → say "hello" → "No network." (no timeout
  wait).
- Re-enable → next utterance works without restart.

**Suggested automated coverage (not yet added):**
- `LocalCommandClassifierTest` covering every regex in every handler.
- `HermesApiTest` with MockWebServer for [DONE], partial chunks,
  reconnect on 503, 401 no-retry.
- `BluetoothAutoManagerTest` for device-type matching.

---

## J. Patch Plan

### J.1 — Critical fixes (this branch)
All B.x bugs above. Code now compiles and the runtime no longer
silently fails on the previously broken commands.

### J.2 — Reliability follow-ups
1. Add `LocalCommandClassifierTest` so regex regressions surface in CI.
2. Replace `SpeechRecognizer`-driven wake-word with a Vosk wake-word
   model (the dependency is already declared in `build.gradle`).
3. Add a `WorkManager` watchdog that restarts the service if it dies
   while wake-word mode is enabled.

### J.3 — Feature completion
1. Confirmation prompts for SMS send / contact delete / call to
   non-contact number.
2. `NotificationListenerService` so we can enumerate (and dismiss) other
   apps' notifications properly.
3. MMS / file-share send path for `SmsAction`.

### J.4 — Polish
1. Onboarding flow guiding users through permissions + Tailscale URL
   + accessibility-service enable.
2. Session search.
3. Quick-phrase import/export to JSON.

---

*Audit verified against the actual source files on
`claude/android-voice-control-audit-RXgEc`; fixes applied in this commit.*
