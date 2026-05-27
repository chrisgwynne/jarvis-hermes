# Jarvis Hermes

Android voice app with wake word activation. Says "okay jarvis" and it permanently listens — no button pressing required.

## How it works

1. **Wake word** — say "okay jarvis" to activate. Phone shows 👂 and says "yes?"
2. **Always listening** — after wake, Jarvis listens continuously until you stop talking
3. **Streaming TTS** — text responses stream in real-time and are spoken back as they arrive
4. **Interrupt** — tap screen or say something while Jarvis is talking to interrupt

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

### 2. Configure app

In `MainActivity.kt`:
```kotlin
private val hermesBaseUrl = "http://YOUR_MACHINE_IP:8642"
private val apiKey = "YOUR_API_KEY"
private val wakePhrase = "okay jarvis"  // change to whatever you want
```

### 3. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
Wake word detected → "yes?" TTS
         ↓
User speaks → Android STT → Hermes /v1/chat/completions (stream)
         ↓                              ↓
   Android TTS ← streaming text chunks ←
         ↓
   User hears response in real-time
         ↓
   Auto-resume listening after TTS completes
```

STT and TTS both run on-device. Only text goes to Hermes.

## Wake word options

- **Built-in Android SpeechRecognizer** — detects "okay jarvis" in partial results
- Change `wakePhrase` in `MainActivity.kt` to any phrase
- Wake word detection is done by checking partial results against the phrase

## Remote access (Tailscale)

Install Tailscale on the machine running Hermes:
```bash
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up
```

Use your Tailscale IP as `hermesBaseUrl` in the app.

## Requirements

- Android 8.0+ (API 26)
- Hermes Agent with API server enabled
- Device on same network (or via Tailscale)