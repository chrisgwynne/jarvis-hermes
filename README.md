# Jarvis Hermes

Android voice app that talks to Hermes Agent via its OpenAI-compatible API. Push-to-talk, streams text responses, plays them back via Android TTS.

## What it does

- Push the button to talk
- Speech-to-text runs on-device (Android SpeechRecognizer — no cloud STT)
- Text sent to Hermes via `/v1/chat/completions`
- Response played back via Android TextToSpeech

## Setup

### 1. Configure Hermes API Server

```bash
# In ~/.hermes/.env
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=your-secret-key
```

Then restart the gateway:
```bash
hermes gateway restart
```

### 2. Set your connection details

In `app/src/main/java/com/jarvis/hermes/MainActivity.kt`, update:
```kotlin
private val hermesBaseUrl = "http://YOUR_HERMES_IP:8642"
private val apiKey = "YOUR_API_KEY"
```

If on the same network, use the machine's LAN IP. For remote access, use Tailscale.

### 3. Build

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Tailscale (recommended for remote access)

Install Tailscale on the machine running Hermes:
```bash
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up
```

Get your Tailscale IP and use that as `hermesBaseUrl` in the app.

## Architecture

```
User speaks → Android STT (on-device) → Hermes /v1/chat/completions → Hermes responds → Android TTS (on-device) → User hears response
```

STT and TTS both run locally on the device. Only the text message goes to Hermes.

## Requirements

- Android 8.0+ (API 26)
- Hermes Agent running with API server enabled
- Device on same network as Hermes (or connected via Tailscale)