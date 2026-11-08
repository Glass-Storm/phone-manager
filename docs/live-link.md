# Original INMO UI with live gRPC

The `app` launcher now uses `ui/original/PhoneServerScreen.kt`,
`GlassesClientScreen.kt`, `MediaComponents.kt` and `ChatMessage.kt`, copied from
`test1-main.zip`. Layout, controls and message rendering are retained. The agreed
exception is role selection: it is saved on first launch and changed through the
header settings button, rather than permanent bottom role tabs. The UI has a
fixed dark palette matching the supplied screenshots. Formatting/package names,
lifecycle cleanup and transcript scroll-following were adapted for this project.

## Phone and glasses

Install the SAME new debug APK on both devices. Choose phone on the phone and
glasses on the glasses. Keep both apps visible during this first integration;
background service hosting is not implemented for this conversation endpoint.

1. Manually enable the phone's Wi-Fi hotspot and join it from the glasses (or put
   both devices on the same LAN without client isolation). Internet is not used
   for communication or recognition after the models are installed.
2. Start Server on the phone (default port 8080). Expand its status row to read
   and copy the actual hotspot IP. Opening the server does not create a hotspot.
3. On the glasses, the default address can be resolved from the hotspot gateway.
   If automatic detection fails (or both devices use a normal Wi-Fi router),
   expand the connection row, enter the phone IP and port manually, and connect.
   The connected state requires a real gRPC bootstrap reply, not just a dial.
4. Send text in either direction. The remote device displays each received line
   and keeps earlier messages. Local outgoing lines mean enqueued for transport;
   there is no read-receipt or durable delivery promise.
5. Photo and low-rate live JPEG preview use the original camera controls and a
   gRPC image frame; no gesture inference is invoked. Denied camera/mic permission
   must be granted in Android's app settings before those controls can work.

The optional conversation socket uses the frozen `StreamService/OpenStream` RPC
on its own server at port 8080: transcript carries typed text, result carries
recognized subtitles, audio carries PCM16 mono 16 kHz, image carries JPEG, and an
empty audio packet flushes an utterance when recording stops. The initial
stream_open reply is a connection acknowledgement. These are the same-APK link's
semantics; the existing ecosystem hub relay on its own port is unchanged and
ignores peer transcript frames. Existing external ecosystem clients must continue
using that relay. No proto fields, numbers, PIN or authentication rules change.
The link is local plaintext and unauthenticated, as in the repository's LAN
contract. Only the transport module imports generated ecosys.v1 types.

## Actual offline speech to subtitles

Both devices can record; the PHONE always performs recognition. Tap the original
record control (now labelled speech subtitles) and speak Cantonese or English.
Audio chunks travel over gRPC from glasses to phone. SenseVoice produces an
utterance after a pause; complete results appear in both devices' message lists.
Stopping recording flushes the final buffered utterance. This is transcription,
not translation into another spoken language. Recognition quality and latency
need real-device validation.

The phone must be arm64 and have all ASR and diarization models installed BEFORE
starting the app. Missing models, unsupported ABI and recognition failures are
reported. The prototype never displays the fallback fake speech engine's output
as real subtitles. The microphone will not run until connected.

### Download only the speech models (Windows Git Bash)

From the phone-manager checkout:

```bash
PHONE_MANAGER_SPEECH_ONLY=1 bash scripts/fetch-ai-models.sh
```

This reuses the repository's pinned SHA-256 values and downloads ASR, speaker
segmentation and speaker embedding only, without TTS or gesture models.

### Install onto the phone (PowerShell)

Connect the phone over USB, enable USB debugging, and install/run the app once.
List devices first and replace `PHONE_SERIAL` with the PHONE's serial, so models
are not accidentally pushed to the glasses or an emulator:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb -s PHONE_SERIAL shell mkdir -p /sdcard/Android/data/com.glassstorm.phonemanager/files/models
& $adb -s PHONE_SERIAL push .\build\ai-models\asr /sdcard/Android/data/com.glassstorm.phonemanager/files/models/
& $adb -s PHONE_SERIAL push .\build\ai-models\diarization /sdcard/Android/data/com.glassstorm.phonemanager/files/models/
& $adb -s PHONE_SERIAL shell am force-stop com.glassstorm.phonemanager
```

Then reopen the phone app, start Server, reconnect the glasses and test the mic.
See `docs/ai-model-provisioning.md` for the exact model tree and native engine
requirements. Downloading models on a PC does not install them on the phone.

## Emulator testing

A single emulator cannot prove two-device communication or arm64 speech. For two
Android emulators, forward the phone emulator's server port through the PC:

```powershell
& $adb -s PHONE_EMULATOR_SERIAL forward tcp:18080 tcp:8080
```

Then connect from the glasses emulator to `10.0.2.2:18080`. Do not use one
emulator's own loopback address as the other device's IP. x86 emulators support
text/connection tests but explicitly reject local model inference.

## Verification scope

ConversationLinkTest uses real loopback TCP sockets and generated gRPC stubs to
check handshake, bidirectional text, PCM transport (including flush), JPEG,
subtitle delivery and disconnect cleanup. A test subtitle verifies transport,
not real model recognition. APK build and architecture/style checks run in cloud.
Two physical devices, microphone hardware and native arm64 inference require
on-device testing; they are not established by JVM test results.
