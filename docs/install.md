# Installing the hub from scratch

This is the single linear path from a clean machine to an arm64 Android phone
running the hub with the **HKSL sequence recogniser** (Shuwa), plus a camera peer
streaming at 15 fps.

The other documents remain the reference detail:

- [`docs/ai-model-provisioning.md`](ai-model-provisioning.md) — exact model tree,
  every pinned SHA-256, push fallbacks.
- [`docs/three-end-demo.md`](three-end-demo.md) — the full three-end demo runbook,
  including the glasses app and the emulator harness.
- [`docs/protocol.md`](protocol.md) — the frozen wire contract.

If you only read one document to get running, read this one.

> Commands below are bash. On Windows, run them in Git Bash or WSL; PowerShell
> equivalents are shown where they differ.

## 0. What you need

| Requirement | Why |
| --- | --- |
| An **arm64-v8a** Android phone, Android 10 or newer | ai-android's native build is arm64 only; the app's `minSdk` is 29. An x86 emulator always gets the fakes. |
| A PC with **JDK 21** | Gradle 8.13 does not run on Java 25. The repo pins the daemon to Java 21. |
| **Android SDK** platform 36 and build-tools 36.0.0 | The app compiles against `compileSdk 36`. |
| `git`, `adb`, and a bash shell | Checkout, install, and the model fetch script. On Windows use Git Bash or WSL for the script. |
| An `ai-android` checkout at the pinned commit | Consumed as a composite build, never as a released artifact. |
| The **Shuwa model bundle** | Three files that are deliberately **not** in this repository. |

### The model bundle

The bundle contains the three runtime files for HKSL:

```text
holistic_landmarker.task
shuwa-translator.tflite
shuwa-knn-hksl.bin
```

Get it from the project owner. The translator and the KNN table are not
downloadable from Google, GitHub, or Maven: they are local exports built from
the Shuwa checkpoint and the project's reference embeddings, so the bundle is
distributed separately from this repo. Keep it private; do not commit it.

## 1. Lay out the checkouts

`settings.gradle.kts` resolves the engine at the fixed relative path
`../../ai-android`. It is not configurable, so the directory layout must be:

```text
<workspace>/
├── ai-android/                  <- pinned engine checkout
└── Glass-Storm/
    └── phone-manager/           <- this repo
```

On a fresh machine:

```bash
mkdir -p ~/Code/Glass-Storm
cd ~/Code/Glass-Storm

git clone https://github.com/Glass-Storm/phone-manager.git
cd ..

git clone https://github.com/Glass-Storm/ai-android.git
git -C ai-android checkout 8570a3a65525f7b18d43e3c453749289cc02f97d

git -C ai-android rev-parse HEAD
```

The last command must print `8570a3a65525f7b18d43e3c453749289cc02f97d`.
`settings.gradle.kts` asserts this on every build and fails with a named error if
the checkout is missing or drifted. That commit is the source of truth; do not
replace it with `main` unless you also bump `pinnedAiAndroidCommit` deliberately.

## 2. Install JDK 21 and the Android SDK

Install a JDK 21 (Temurin, Android Studio's bundled JBR, or any 21 distribution)
and the Android SDK. The easiest SDK install is Android Studio, but the command
line tools work too.

Point Gradle at the SDK with either an environment variable:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"          # Linux
export ANDROID_HOME="$HOME/Library/Android/sdk"  # macOS
```

or a machine-local `local.properties` at the repo root:

```properties
# Forward slashes work on every platform.
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

`local.properties` is gitignored and must never be committed.

Check that the tools resolve:

```bash
java -version     # must report 21.x
adb version
```

If Gradle cannot find a JDK 21, add its path to `~/.gradle/gradle.properties`
(`%USERPROFILE%\.gradle\gradle.properties` on Windows):

```properties
# Use the path where JDK 21 is actually installed.
org.gradle.java.installations.paths=C:/Program Files/Eclipse Adoptium/jdk-21.0.9.1-hotspot
```

As a one-command fallback, set `JAVA_HOME` explicitly:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

## 3. Build and install the APK

From the phone-manager root:

```bash
./gradlew :app:assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Find the phone's
serial, install, and confirm the ABI:

```bash
adb devices
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell getprop ro.product.cpu.abilist
```

The ABI list must contain `arm64-v8a`. The real engines are never bound without
it, even when every model file is present. If `adb` is not on your `PATH`, call
the copy in the SDK's `platform-tools/` directory by its full path.

## 4. Fetch the public models

The four public engine trees are downloaded from stable upstream hosts and
verified against pinned hashes:

```bash
./scripts/fetch-ai-models.sh
```

The script writes to `build/ai-models/` and creates:

```text
build/ai-models/
├── asr/            SenseVoice int8
├── diarization/    pyannote segmentation + CAM++ embedding
├── gesture/        MediaPipe single-image gesture bundle
└── tts/            Cantonese VITS
```

It needs `bash`, `curl`, `tar`, and `sha256sum`; on Windows run it from Git Bash
or WSL. macOS does not ship `sha256sum`, so install GNU coreutils and put its
`gnubin` directory on `PATH`, or run the script in a Linux container. The script
is idempotent and fails loudly on a hash mismatch.

This step is **optional for HKSL only**. With no public models the speech, TTS,
and single-image gesture ports bind deterministic fakes, but Shuwa can still run.
For the full speech + gesture + TTS demo, run the script.

The script deliberately does **not** fetch `shuwa/`: those files are project
artifacts, not public upstream releases. That is the next step.

## 5. Add the Shuwa model bundle

Stage the three files inside the same `build/ai-models/` tree, using the exact
runtime names. The bundle may already use the runtime names, or it may contain
the toolkit's export names; the mapping is:

| Bundle / export file | Name on the phone | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `holistic_landmarker.task` | `holistic_landmarker.task` | 13,683,609 | `e2dab61191e2dcd0a15f943d8e3ed1dce13c82dfa597b9dd39f562975a50c3f8` |
| `shuwa-translator.tflite` or `shuwa-translator-f32.tflite` | `shuwa-translator.tflite` | 4,577,872 | `9f1c5017cdf95002e8c156308757b56d989db32c8a573c864030c4f053240449` |
| `shuwa-knn-hksl.bin` or `shuwa-knn-hksl-8-idle.bin` | `shuwa-knn-hksl.bin` | 1,311,743 | `336fb2d7d05de1c98fea06e8c599d8e133a71915159aa1976ea4689ca67d0a8a` |

These are the same values pinned in
[`docs/ai-model-provisioning.md`](ai-model-provisioning.md#pinned-files); update
both together if the bundle ever changes.

> **Do not use the old 71-gloss table.** A file named `shuwa-knn-hksl.bin` in
> the toolkit's `build/` directory is normally the old table: 14,277,113 bytes,
> SHA-256 `38cd07093eb3ded260bf0f6b3ed79fea113ec794b5213cc21edd37e49997d61a`.
> The current demo table is the **8-gloss + Idle** file, 1,311,743 bytes, and
> must be renamed to `shuwa-knn-hksl.bin` on the phone. The old table loads but
> does not match the app's eight-entry meaning table.

Stage with the runtime names:

```bash
mkdir -p build/ai-models/shuwa
cp <bundle>/holistic_landmarker.task build/ai-models/shuwa/
cp <bundle>/shuwa-translator.tflite build/ai-models/shuwa/
cp <bundle>/shuwa-knn-hksl.bin      build/ai-models/shuwa/
```

If the bundle keeps the export names:

```bash
cp <bundle>/shuwa-translator-f32.tflite  build/ai-models/shuwa/shuwa-translator.tflite
cp <bundle>/shuwa-knn-hksl-8-idle.bin    build/ai-models/shuwa/shuwa-knn-hksl.bin
```

Verify before pushing:

```bash
cd build/ai-models/shuwa
sha256sum holistic_landmarker.task shuwa-translator.tflite shuwa-knn-hksl.bin
```

PowerShell equivalent:

```powershell
Get-FileHash .\build\ai-models\shuwa\* -Algorithm SHA256
```

All three hashes must match the table above. The app checks presence, not hashes,
so a wrong-but-present file fails later as bad recognition rather than at startup.

## 6. Push the models to the phone

```bash
adb -s <serial> shell mkdir -p /sdcard/Android/data/com.glassstorm.phonemanager/files/models
adb -s <serial> push build/ai-models/. /sdcard/Android/data/com.glassstorm.phonemanager/files/models/
```

The trailing `/.` on the source is deliberate: it merges the tree into `models/`
instead of creating `models/ai-models/`.

Verify what landed:

```bash
adb -s <serial> shell 'cd /sdcard/Android/data/com.glassstorm.phonemanager/files/models/shuwa && sha256sum holistic_landmarker.task shuwa-translator.tflite shuwa-knn-hksl.bin'
```

If a direct push into `Android/data` is blocked by the device, use the app-uid
fallback in
[`docs/ai-model-provisioning.md`](ai-model-provisioning.md#push-to-the-device).
A hash mismatch after a push almost always means a truncated transfer: delete
the file and push it again.

## 7. Restart the app and confirm which engine is bound

The engine choice happens once, when the Dagger graph is built. Copying files
under a running app does not switch it; force-stop and start:

```bash
adb -s <serial> shell am force-stop com.glassstorm.phonemanager
adb -s <serial> shell am start -n com.glassstorm.phonemanager/.MainActivity
```

`DeviceAiModule` selects the gesture engine in this order:

1. **Shuwa sequence recogniser** — the device is arm64 **and** all three
   `models/shuwa/` files exist.
2. **Single-image MediaPipe gesture** — the device is arm64 and
   `models/gesture/gesture_recognizer.task` exists.
3. **Deterministic fake** — nothing else.

The same decision also selects the relay tuning: Shuwa needs a window of stills,
while the single-image engine wants only the newest frame. A partial `shuwa/`
tree is treated as absent by design, so one missing file silently falls through
to the next available engine (single-image if its bundle is present, otherwise
the fake).

## 8. Start the hotspot and the hub

1. On the phone, enable the system hotspot **manually** in Android Settings.
   OEMs block programmatic `LocalOnlyHotspot`, so the app cannot do this for you.
2. Launch the hub:
   ```bash
   adb -s <serial> shell am start -n com.glassstorm.phonemanager/.MainActivity
   ```
3. On the overview screen, tap the start-hub button. The dashboard should show a
   bound port of `9000`. If it stays stopped, check the notification permission
   and whether the screen is on.

## 9. Connect the camera peer

### Raspberry Pi over the phone hotspot (recommended path)

Clone the Pi client and sync its environment:

```bash
git clone https://github.com/Glass-Storm/necklace-data-processing-raspberrypi-side-.git
cd necklace-data-processing-raspberrypi-side-
uv venv --system-site-packages
uv sync                    # USB webcam (the demo path)
# or, for the Pi CSI camera:
# uv sync --extra pi
```

`--system-site-packages` is required by the Pi's smoke test. The `pi` extra is
only needed for the CSI camera, because `picamera2` lives in the Debian system
site-packages.

Install the Pi OS packages once:

```bash
sudo apt-get update
sudo apt-get install -y alsa-utils ffmpeg v4l-utils rsync curl ca-certificates

# CSI camera only: the Debian libcamera bindings, plus headers for python-prctl.
# sudo apt-get install -y python3-picamera2 libcap-dev
```

The Pi repo's README lists the full prerequisites and the audio fallback.

Join the hotspot with the Pi's additive join script. It writes under `/etc`, so
run it as root:

```bash
sudo env ECOSYS_COUNTRY=HK ./scripts/join-hotspot.sh "<hotspot-ssid>" "<hotspot-password>" wpa3
```

Use `wpa2` instead of `wpa3` if that is the phone hotspot's mode, and set
`ECOSYS_COUNTRY` to your own region if it is not Hong Kong. The script is
multi-network: it updates or adds the target network and leaves every other
remembered network in place, which is what makes auto-join at boot work.

If the Pi cannot see the hotspot at all, set the phone hotspot band to
**2.4 GHz or Auto**. A 5 GHz-only access point is invisible to 2.4 GHz-only Pi
radios.

Create `config.json` from the example:

```bash
cp config.example.json config.json
```

`config.json` is gitignored; never commit the real credentials. Set the SSID,
password, and security mode, and **leave `hub` blank**:

```json
{
  "hub": "",
  "ssid": "<hotspot-ssid>",
  "password": "<hotspot-password>",
  "wifi_security": "wpa3"
}
```

A blank `hub` is what makes the client join the hotspot and then discover the
hub from the default gateway. If `hub` (or `--hub`) is set, the client **skips
the join** and dials that address directly, so only use it when Wi-Fi is already
connected or the peer is on Ethernet.

Run the camera:

```bash
uv run python -m ecosys_pi --mode video
```

The client defaults to 15 fps and requests the camera source automatically. Force
the USB webcam with:

```bash
PI_ECOSYS_VIDEO_SOURCE=uvc uv run python -m ecosys_pi --mode video
```

Expected log lines:

```text
[Pi Camera] ... @ 15.0 fps
[Pi Pace] fps=15.0 pending=0 ...
```

`pending=0` is the pass condition. If `pending` grows, the link cannot carry the
stream; do not raise the frame rate, improve the Wi-Fi or move the Pi closer.

### Jetson Nano through the PC

The Jetson has no Wi-Fi in this setup. Connect its Ethernet to the PC, run your
PC-side TCP relay for the hub port, and start the same client on the Jetson with
an explicit `--hub <pc-ip>:<relay-port>`. An explicit hub also stops the client
from trying to join a hotspot, which is correct for the wired peer.

The client, the 15 fps cadence, and the model path are identical; only the
network hop differs. The PC relay adds a local TCP forward, not a second decode
or inference stage.

## 10. Prove the demo

Run through this checklist after any fresh install:

- [ ] `adb shell getprop ro.product.cpu.abilist` contains `arm64-v8a`.
- [ ] `models/shuwa/` contains exactly the three files with the pinned hashes.
- [ ] The hub dashboard shows a bound port (`9000` by default).
- [ ] The Pi logs `fps=15.0 pending=0`.
- [ ] Signing one of the eight demo glosses produces its mapped meaning text. The
      reference labels are `Hksl_cash`, `Hksl_credit_cards`, `Hksl_how_are_you`,
      `Hksl_soft_drink`, `Hksl_tomato`, `Hksl_understand`, `Hksl_welcome`, and
      `Hksl_write`.
- [ ] Holding an idle/neutral pose produces no gesture (`Idle` is a reject class).
- [ ] Thumbs-up / palm / fist are **not** being recognised. If they are, the app
      is still on the single-image engine; see below.

## 11. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `ai-android composite build not found at ...` | The engine is not at `../../ai-android`. | Clone it at the fixed relative path from section 1. |
| `ai-android is pinned to ... but the checkout ... is at ...` | The engine checkout is on the wrong commit. | `git -C ai-android checkout 8570a3a65525f7b18d43e3c453749289cc02f97d` |
| `What went wrong: 25.0.4.1` | Gradle ran on Java 25. | Install JDK 21 and point `JAVA_HOME` or `org.gradle.java.installations.paths` at it. |
| `SDK location not found` | No `ANDROID_HOME` and no `local.properties`. | Set one of them; see section 2. |
| `adb devices` shows `unauthorized` or no device | USB debugging is off, the cable is charge-only, or the PC has not been trusted. | Enable USB debugging, use a data cable, unlock the phone, and accept the RSA prompt. |
| The app still recognises thumbs-up / palm / fist | At least one Shuwa file is missing, the table is the old 71-gloss file, the APK predates Shuwa, or the app was not restarted. | Check all three files and hashes, confirm the arm64 ABI, install the current APK, then force-stop and restart. |
| `models/shuwa/` looks complete but Shuwa never runs | A partial tree is treated as absent; the app checks presence only, not content. | `ls -l` the three exact filenames; remove stray names such as `shuwa-translator-f32.tflite` on the phone. |
| Signs are never reported | The engine skips windows whose pose barely changed, and waits out a short quiet window at the end of a sign. | Sign the full movement instead of holding the final pose; keep the face, shoulders, and both hands in frame; wait for the quiet window. |
| `pending` grows or `fps` falls below 15 | The Wi-Fi link cannot carry 15 fps. | Move the peer closer, reduce interference, or use the UVC camera at a lower resolution. Do not raise the frame rate. |
| `adb push` fails on `Android/data` | The device restricts direct writes. | Use the `run-as` fallback in `ai-model-provisioning.md`. |
| A pushed file has the wrong hash | Truncated transfer. | Delete the device copy and push it again. |
| The Pi cannot find the hub | `hub` is set in `config.json`, so the client skipped the hotspot join, or the Pi is not associated. | Leave `hub` blank, or join Wi-Fi first and pass the correct `--hub`. Verify `wpa_state=COMPLETED` and a DHCP address on `wlan0`. |

### If the app is still on single-image gestures

Work through these in order; each one independently forces the fallback:

1. `adb shell ls -l /sdcard/Android/data/com.glassstorm.phonemanager/files/models/shuwa`
   must list `holistic_landmarker.task`, `shuwa-translator.tflite`, and
   `shuwa-knn-hksl.bin` with those exact names.
2. The KNN file must be 1,311,743 bytes with SHA-256 `336fb2d7...`, not the
   14,277,113-byte `38cd...` table.
3. `ro.product.cpu.abilist` must contain `arm64-v8a`.
4. The installed APK must be the current `:app:assembleDebug` output, not an old
   build from before the Shuwa merge.
5. Force-stop and restart the app after provisioning; the graph is built once.

## 12. What never goes into Git

- `local.properties` (machine-specific SDK path).
- `build/ai-models/` and every model file, including the Shuwa bundle.
- The Shuwa model bundle, the toolkit's `build/` outputs, and the reference
  dataset.
- Hotspot credentials, signing keys, and any personal sample recordings.

The repository carries only code, configuration, and the pinned hashes. Model
bytes are host-provisioned on every machine.

### Cleanup after a session

The hub relays media and does not persist it. Stop the Pi client and the hub,
turn off the hotspot, and delete any test captures or enrollment samples you
created outside the app. No captured images or voice recordings should be left
behind or committed.
