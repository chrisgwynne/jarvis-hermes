# Jarvis Hermes

Android voice app for talking to Hermes Agent. Press Start, talk, respond, keep going. Sessions saved, mic on/off, connection status.

## Features

- **Always listening** — Start Conversation, talk, Jarvis responds, auto-resumes listening
- **Mic on/off** — say "mic off" to pause listening (stays awake, says "Mic off"). Say "mic on" to resume
- **End conversation** — say "end conversation" or press the button
- **Sessions** — past conversations saved, viewable with timestamps and message counts
- **Connection status** — dot indicator shows Hermes connected (blue) / disconnected (red) / unknown (grey)
- **Streaming TTS** — responses spoken in real-time as chunks arrive
- **Screen awake** — display stays on during conversation
- **Settings** — configure Tailscale URL + API key, persisted across app restarts

## Voice Commands

| Command | Action |
|---------|--------|
| `mic off` | Pause listening, stays in wake-ready state |
| `mic on` | Resume listening |
| `end conversation` | End session and save |
| `settings` | Open settings page |

## Setup

### 1. Enable Hermes API Server

In `~/.hermes/.env`:
```
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=your-secret-key
API_SERVER_CORS_ORIGINS=*
```

Then:
```bash
hermes gateway restart
```

### 2. Get your Tailscale IP

On the machine running Hermes:
```bash
tailscale up
ip addr show tailscale0 | grep inet
```

### 3. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Configure the app

Open the app → tap Settings → enter your Tailscale URL (e.g. `http://100.x.x.x:8642`) and API key → Save

## Architecture

```
User presses Start → Jarvis: "Yes?" → You talk
         ↓
Android STT → Hermes /v1/chat/completions (SSE stream)
         ↓                              ↓
   Android TTS ← SSE chunks ← response ←
         ↓
   Auto-resume listening
         ↓
User: "mic off" → Listening pauses → "Mic off. Say mic on to resume."
User: "mic on" → Listening resumes
User: "end conversation" → Session saved, returns to idle
```

STT and TTS run on-device. Only text goes to Hermes.

## Requirements

- Android 8.0+ (API 26)
- Hermes Agent with API server enabled
- Tailscale on the machine running Hermes