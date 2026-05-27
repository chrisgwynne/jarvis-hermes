# Jarvis Hermes

Android foreground service voice app for talking to Hermes Agent. Starts a conversation, runs in background through app switches and screen locks. Mic on/off, sessions saved.

## How it works

1. **Start** → foreground notification appears, says "Yes?"
2. **Talk** → responses stream via TTS as they arrive
3. **Mic Off/Resume** → via notification button or say "mic off" / "mic on"
4. **End** → via notification button or say "end conversation"
5. **Sessions** → saved automatically, viewable in Sessions tab

Works through:
- App switching
- Screen lock
- Backgrounding
- Device sleep

## Setup

### 1. Enable Hermes API Server

In `~/.hermes/.env`:
```
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=your-secret-key
API_SERVER_CORS_ORIGINS=*
```

```bash
hermes gateway restart
```

### 2. Get your Tailscale IP

```bash
tailscale up
ip addr show tailscale0 | grep inet
```

### 3. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Configure

Open the app → Settings → enter your Tailscale URL (e.g. `http://100.x.x.x:8642`) and API key → Save

## Voice Commands

| Command | Action |
|---------|--------|
| `mic off` | Pause listening, stays active in background |
| `mic on` | Resume listening |
| `end conversation` | End session and save |

Or use the notification action buttons.

## Architecture

```
User presses Start → Foreground notification starts
         ↓
VoiceService runs as STICKY foreground service
         ↓
User talks → Android STT → Hermes /v1/chat/completions (SSE)
         ↓                         ↓
   Notification actions ← SSE chunks ← response
         ↓
   Android TTS speaks chunks in real-time
         ↓
   Auto-resume listening after each response
         ↓
   Sessions saved to SharedPreferences on end
```

STT and TTS on-device. Only text to Hermes.

## Notification

Shows "Listening...", "Thinking...", "Paused — say mic on" with:
- **Mic Off** / **Resume** button
- **End** button
- Tap notification to return to app

## Requirements

- Android 8.0+ (API 26)
- Hermes Agent with API server enabled
- Tailscale on the machine running Hermes