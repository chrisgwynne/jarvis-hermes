# Jarvis Hermes

Android voice app — press Start Conversation, talk, Jarvis responds, keeps listening until you end it.

## How it works

1. **Settings** → enter your Tailscale URL (e.g. `http://100.x.x.x:8642`) and optional API key
2. **Start Conversation** → press the button, Jarvis says "Yes?"
3. **Always listening** → talk, Jarvis responds, listens again automatically
4. **End Conversation** → press to stop

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

You'll get something like `100.x.x.x:8642`

### 3. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. Configure the app

Open the app → tap ⚙️ → enter your Tailscale URL and API key → Save

## Architecture

```
Start Conversation → Jarvis: "Yes?" → You talk
         ↓
Android STT → Hermes /v1/chat/completions (stream)
         ↓                              ↓
   Android TTS ← SSE chunks ← response ←
         ↓
   Auto-resume listening
         ↓
   End Conversation → stops
```

STT and TTS run on-device. Only text goes to Hermes.

## Requirements

- Android 8.0+ (API 26)
- Hermes Agent with API server enabled
- Tailscale on the machine running Hermes