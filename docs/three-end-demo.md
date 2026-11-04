# The three-end demo runbook

This runbook reproduces the `ecosys.v1` three-end demo: one phone hub, one
smart-glasses peer, and one Raspberry Pi necklace peer, all connected at the same
time. It is written so a second person can run it without reading the source.
Every command block is meant to be copy-pasted from the repo root unless it says
otherwise.

The demo has two topologies. Use **Topology A** for the real page-14 setup. Use
**Topology B** only when the phone is unavailable; it was the harness used to
collect the evidence in this repo, and it is not page-14 evidence.

## 1. What the demo proves

The hub is the access point and the gRPC server. Peers open one
**unauthenticated** `StreamService/OpenStream` call and bind their identity with
a first `stream_open` frame. There is no PIN and no bearer token.

| Scenario    | Glasses role   | Sender of the images         | What the hub does                                                                             | What the user sees and hears                  |
| ----------- | -------------- | ---------------------------- | --------------------------------------------------------------------------------------------- | --------------------------------------------- |
| S1 gesture  | 聽障 (deaf)    | Raspberry Pi necklace camera | Recognises the gesture, sends the word to the glasses, sends TTS to the **image sender only** | Glasses show the word; the necklace speaks it |
| S2 subtitle | 聽障 (deaf)    | Glasses microphone           | Transcribes and attributes the speaker, returns a `result` frame                              | Glasses show the subtitle and speaker label   |
| S3 gesture  | 健全 (hearing) | Glasses camera               | Recognises the gesture, sends the word and TTS back to the glasses                            | Glasses show the word and play it             |

The Chinese strings in this runbook (聽障, 健全, 啟動中樞, 即時, and engine
output such as 謝謝) are verbatim UI labels or engine output, quoted so they
match the app; they are not prose. All document prose is English.

The routing rule is the page-14 split: a `DAEMON` image gets `gesture` to the
glasses and `tts_audio` to the daemon; a `GLASS` image gets both frames on the
glasses session. The hub never sends TTS for an image to the other peer.

The hub gRPC port is **9000**. Older material mentioning 50051 is stale.

## 2. Topology A: production page-14

```text
                 phone hub (AP + gRPC server)
                 SSID/PSK local-only, gateway 192.168.43.1:9000
                    /                         \
          Wi-Fi join by hand              wpa_supplicant join
                 /                             \
        smart-glasses app                   Raspberry Pi
        dials 192.168.43.1:9000             dials 192.168.43.1:9000
```

The phone is the DHCP server, the DNS-SD advertiser, and the hub. The peers reach
the hub at the hotspot's default gateway. mDNS is advertised but not required.

### 2.1 Credentials and variables (never commit these)

The SSID and PSK live only in the operator's local credential store. This repo
must not contain them. Create `~/.config/ecosys/demo.env` outside every checkout,
mode 600, and fill it in by hand:

```bash
install -d -m 700 "$HOME/.config/ecosys"
$EDITOR "$HOME/.config/ecosys/demo.env"   # ECOSYS_SSID=...  ECOSYS_PSK=...
chmod 600 "$HOME/.config/ecosys/demo.env"
```

For every shell that joins the network or starts a peer:

```bash
set -a; . "$HOME/.config/ecosys/demo.env"; set +a
export ECOSYS_HUB=192.168.43.1:9000
```

If the hotspot uses a different gateway, set `ECOSYS_HUB` from
`adb shell ip route` or the phone's hotspot screen. Do not guess.

### 2.2 Put the hub APK on the arm64 phone

From the hub repo:

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk \
  ./gradlew :app:assembleDebug
adb -s <hub-phone-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <hub-phone-serial> shell getprop ro.product.cpu.abilist
```

The last command must contain `arm64-v8a`. The real ai-android engines bind only
when the device reports `arm64-v8a` **and** every model file exists. Provision
them:

```bash
scripts/fetch-ai-models.sh
adb -s <hub-phone-serial> shell mkdir -p /sdcard/Android/data/com.glassstorm.phonemanager/files/models
adb -s <hub-phone-serial> push build/ai-models/. /sdcard/Android/data/com.glassstorm.phonemanager/files/models/
```

See [`docs/ai-model-provisioning.md`](ai-model-provisioning.md) for the exact
tree, SHA-256 values, and the low-space fallback through `/data/local/tmp`. A
build with missing models still runs, but `DeviceAiModule` binds the
deterministic `:adapter:jvm` fakes; that is not acceptable for the demo.

### 2.3 Start the hotspot and the hub on the phone

1. On the phone, open Android Settings and enable the system hotspot by hand.
   OEMs block programmatic `LocalOnlyHotspot`, so this step is manual.
2. Note the SSID and password in the local store from section 2.1.
3. Launch the hub:

    ```bash
    adb -s <hub-phone-serial> shell am start -n com.glassstorm.phonemanager/.MainActivity
    ```

4. Tap **啟動中樞** on the 總覽 screen. The dashboard should show a bound port of 9000. If it shows 未啟動, the foreground service did not start; check the
   notification permission and the screen-on state.

### 2.4 Join the glasses and start the app

The glasses app is a separate repo, checked out at
`/home/chaos/workplace/fyp/SpeakerDiarazation-Gesture` on branch
`feat/ecosys-client`.

1. Join the glasses to the phone hotspot from the app's manual Wi-Fi screen, or
   from Android Settings. The app cannot do this automatically.
2. Build and install:

    ```bash
    cd /home/chaos/workplace/fyp/SpeakerDiarazation-Gesture
    JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk \
      ./gradlew :app:assembleDebug
    adb -s YM00FCF87W0021 install -r app/build/outputs/apk/debug/app-debug.apk
    ```

3. Launch pointed at the gateway. The debug build accepts the extra and skips
   the active-network gate; a release build still requires a real network:

    ```bash
    adb -s YM00FCF87W0021 shell am start -W \
      -n hk.edu.hkmu.speakerdiarazationdemo/.MainActivity \
      --es extra_hub_host "${ECOSYS_HUB%:*}"
    ```

4. Choose the role under **Settings → 切換身份**: 聽障 for S1 and S2, 健全 for
   S3. Only one role at a time, and only one GLASS session at a time.

### 2.5 Deploy and join the Raspberry Pi

The Pi repo is `necklace-data-processing-raspberrypi-side-` on branch
`feat/ecosys-client`. Authenticate SSH from your local credential store; this
runbook never carries a password.

```bash
export PI_SSH="ssh -p 2222 root@127.0.0.1"
tar -C /home/chaos/workplace/fyp/necklace-data-processing-raspberrypi-side- \
    --exclude=.venv --exclude=__pycache__ --exclude=.git -czf - . \
  | $PI_SSH 'mkdir -p /root/necklace && tar -xzf - -C /root/necklace'
```

Create the venv and sync the locked dependencies. The
`--system-site-packages` flag is required by `tests/test_smoke.py`. That flag
makes the interpreter see the uv-managed CPython 3.12's own site-packages, which
are empty for this demo; it does not expose Debian's system packages (the Pi's
default interpreter is 3.14, and its packages are not on this 3.12 path). The
demo uses the UVC camera, not picamera2, so no system camera binding is needed.

```bash
$PI_SSH 'cd /root/necklace && \
  ~/.local/bin/uv venv --python 3.12 --system-site-packages && \
  ~/.local/bin/uv sync && \
  ~/.local/bin/uv run pytest -q'
```

Install the Pi OS tooling once:

```bash
$PI_SSH 'DEBIAN_FRONTEND=noninteractive apt-get update && \
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    alsa-utils ffmpeg v4l-utils rsync curl ca-certificates'
```

Join the hotspot with the Pi peer's `scripts/join-hotspot.sh`. It writes the
`wpa_supplicant` config, starts `wpa_supplicant@wlan0`, ensures a DHCP network
for `wlan0`, refuses to touch `usb0`/`eth0`, and verifies an IPv4 address before
exiting 0. The script ships with the Pi peer; on a checkout that predates it,
use the Pi branch that contains it.

```bash
$PI_SSH "cd /root/necklace && ./scripts/join-hotspot.sh '$ECOSYS_SSID' '$ECOSYS_PSK'"
```

The credentials are expanded on the local side and quoted for the remote shell.
They are never written to a repo file.

Confirm the join and the management link:

```bash
$PI_SSH 'wpa_cli -i wlan0 status | grep wpa_state && ip -4 addr show wlan0'
```

`wpa_state=COMPLETED` and a DHCP address on `wlan0` are the pass condition. The
`usb0` SSH session must still answer.

### 2.6 Run the Pi necklace camera

```bash
$PI_SSH 'cd /root/necklace && \
  PI_ECOSYS_VIDEO_SOURCE=uvc PI_ECOSYS_HUB='"${ECOSYS_HUB}"' \
  ~/.local/bin/uv run python -m ecosys_pi --mode video --hub '"${ECOSYS_HUB}"''
```

The Pi logs `[Pi Camera] source=uvc device=/dev/video0` on stderr. The hub's
即時 screen should list the Pi as `DAEMON`, not `UNSPECIFIED`; a `stream_open`
frame is the first frame on every connection and reconnect.

## 3. Topology B: the emulator harness used here

The phone was unavailable for the recorded run, so the hub ran in
`emulator-5554` while the glasses and the Pi stayed real hardware. This topology
proves the wire path, the real engines, and the clients. It is **not** evidence
that the page-14 phone-as-AP path works.

```text
real glasses --socat over adb shell--> emulator hub 127.0.0.1:9000
real Pi --ssh -R--> container pairer --emulator-initiated hubproxy--> emulator hub
```

Two failures of the obvious design matter:

- **adb `forward`/`reverse` are non-functional** against this forwarded adb
  server. The registrations appear in `--list`, but the host-side relay never
  connects: the emulator reaches `nc 127.0.0.1 9000` and receives the hub's
  HTTP/2 SETTINGS, while both forwards return zero bytes. Do not spend time on
  `adb forward` here.
- **The container cannot reach emulator ports.** The hubproxy is therefore
  emulator-initiated: the emulator dials out to the pairer, and the pairer
  splices that connection to the Pi's reverse tunnel.

All harness environment:

```bash
export ADB_SERVER_SOCKET=tcp:127.0.0.1:15037
export ANDROID_HOME=/home/chaos/Android/Sdk
export JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1
```

### 3.1 Start the hub in the emulator

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk \
  ./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

There are two install choices, and they are not interchangeable:

- `--abi arm64-v8a` runs the repo APK under the emulator's libndk translation.
  The real gesture engine works, but the recorded boot crashed inside
  `libonnxruntime.so` during TTS synthesis.
- `--abi x86_64` uses the harness APK with the shipped x86_64 native libraries.
  This is the recorded, reliable path for the full gesture and TTS loop. The
  artifact is `/tmp/hub-x86_64-signed.apk`; it is an installed artifact only,
  not a repo build product. The injection and debug-key signing steps are in
  `.omo/evidence/three-end/hub-fixture-test.txt`.

```bash
# reliable harness path: push the signed harness APK, then install its x86_64 slice
adb -s emulator-5554 push /tmp/hub-x86_64-signed.apk /data/local/tmp/hub-x86_64.apk
adb -s emulator-5554 shell 'pm install -r --abi x86_64 /data/local/tmp/hub-x86_64.apk'
adb -s emulator-5554 shell 'dumpsys package com.glassstorm.phonemanager | grep primaryCpuAbi'

# or the arm64 slice, if you accept the TTS crash risk
adb -s emulator-5554 push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/hub-arm64.apk
adb -s emulator-5554 shell 'pm install -r --abi arm64-v8a /data/local/tmp/hub-arm64.apk'
```

Provision the models exactly as in section 2.2, with `adb -s emulator-5554`.
Then launch the app and press **啟動中樞**. The recorded shortcut on this AVD is:

```bash
adb -s emulator-5554 shell am start -n com.glassstorm.phonemanager/.MainActivity
adb -s emulator-5554 shell input tap 202 873   # the 啟動中樞 button on this layout
```

Verify the hub directly before involving a peer:

```bash
adb -s emulator-5554 shell \
  "printf 'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n' | toybox nc -w 3 127.0.0.1 9000 | xxd | head -2"
```

A line beginning `0000 1204` is the HTTP/2 SETTINGS frame. If it is empty, the
hub is not listening; restart it from 啟動中樞.

### 3.2 Glasses userspace bridge

The glasses is off any network in this harness. The debug build accepts a hub
host extra and skips the active-network check when the APK is debuggable. Bridge
the glasses' loopback port to the emulator with `socat` over two `adb shell`
endpoints:

```bash
socat \
  EXEC:"adb -s YM00FCF87W0021 shell toybox nc -l -p 9000" \
  EXEC:"adb -s emulator-5554 shell toybox nc 127.0.0.1 9000"
```

Install and launch the glasses in another terminal:

```bash
cd /home/chaos/workplace/fyp/SpeakerDiarazation-Gesture
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk \
  ./gradlew :app:assembleDebug
adb -s YM00FCF87W0021 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s YM00FCF87W0021 shell am force-stop hk.edu.hkmu.speakerdiarazationdemo
adb -s YM00FCF87W0021 shell am start -W \
  -n hk.edu.hkmu.speakerdiarazationdemo/.MainActivity \
  --es extra_hub_host 127.0.0.1
```

A glasses-side `nc 127.0.0.1 9000` that receives the SETTINGS frame proves the
bridge; the same bridge carried the 31 image frames in the recorded S3 run.

### 3.3 Pi reverse tunnel and emulator-initiated bridge

The Pi reaches the harness through an SSH reverse tunnel to `127.0.0.1:9000` on
the container. Authenticate from your local store; no password appears here.

```bash
ssh -N -f -o ExitOnForwardFailure=yes -p 2222 -R 9000:127.0.0.1:9000 root@127.0.0.1
```

Build the two harness programs from `/tmp/ecosys-bridge`. They are dev-harness
only and are not part of either repo. Android/amd64 needs cgo, so the NDK clang
is used:

```bash
export PATH=$PATH:/usr/local/go/bin
cd /tmp/ecosys-bridge
go vet ./...
NDK=/home/chaos/Android/Sdk/ndk/27.0.12077973
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android21-clang
GOOS=android GOARCH=amd64 CGO_ENABLED=1 CC=$CC go build -o hubproxy-android-amd64 .
GOOS=linux   GOARCH=amd64 CGO_ENABLED=0      go build -o pairer-linux-amd64 .
```

Deploy the hub proxy into the emulator and start both halves:

```bash
adb -s emulator-5554 push /tmp/ecosys-bridge/hubproxy-android-amd64 /data/local/tmp/hubproxy
adb -s emulator-5554 shell chmod 755 /data/local/tmp/hubproxy
adb -s emulator-5554 shell 'nohup /data/local/tmp/hubproxy -mode hub \
  -peer 10.0.10.2:19000 -upstream 127.0.0.1:9000 -n 4 \
  >/data/local/tmp/hubproxy.log 2>&1 &'

nohup /tmp/ecosys-bridge/pairer-linux-amd64 -mode pair \
  -emu 0.0.0.0:19000 -pi 127.0.0.1:9000 \
  >/tmp/ecosys-bridge/pairer.log 2>&1 &
```

The hub closes idle gRPC TCP connections after about 17 seconds. The hubproxy
reconnects on its own; that is expected, not a failure.

### 3.4 Verify the Pi path

On the Pi, with the reverse tunnel up:

```bash
PI_SSH='ssh -p 2222 root@127.0.0.1'
$PI_SSH 'ss -ltn | grep :9000'
$PI_SSH 'python3 -c "import socket; s=socket.create_connection((\"127.0.0.1\", 9000), 5); print(\"pi->hub tcp ok\"); s.close()"'
```

Then send one synthetic frame through the full bridge:

```bash
$PI_SSH 'cd /root/necklace && PI_ECOSYS_HUB=127.0.0.1:9000 \
  PI_ECOSYS_VIDEO_SOURCE=synthetic \
  .venv/bin/python -m ecosys_pi --mode video --frames 1 --hub 127.0.0.1:9000'
```

The pairer log should show `pi: accepted` followed by `paired hub ... <-> pi ...`,
and the emulator logcat should show an inbound image from `role=DAEMON`.

## 4. The S1, S2, S3 scripts

### 4.1 Fixtures

| Fixture                         | Used by                       | Format                          | SHA-256                                                            |
| ------------------------------- | ----------------------------- | ------------------------------- | ------------------------------------------------------------------ |
| `tools/e2e-media/thumbs-up.jpg` | S1 and S3 gesture mock        | JPEG, 864 x 1234                | `6f05c99a19e98231104858e4a312c7977128afcec811a0bb1b1619fe56dbfa9f` |
| `tools/e2e-media/demo-yue.wav`  | S2 speech                     | WAV, 16 kHz mono PCM16, 5.148 s | `0960b2db54ae202071d250e6462fbf74a3c863f0e3e7f01273e4939c996875a0` |
| `/tmp/send_fixture.py`          | one JPEG still, GLASS session | harness client                  | dev-host only                                                      |
| `/tmp/send_speech.py`           | one WAV, GLASS session        | harness client                  | dev-host only                                                      |

The two harness clients live outside every repo. They are the exact clients used
for the recorded runs. The repo-native equivalents are
`go run ./tools/mockpeer` with `--image` and `--audio-file`; use those when the
harness clients are not present.

The blocks below reuse `PI_SSH` and `ECOSYS_HUB` from sections 2.1 and 2.5. If
this is a fresh shell, set them first:

```bash
set -a; . "$HOME/.config/ecosys/demo.env"; set +a
export ECOSYS_HUB=192.168.43.1:9000
export PI_SSH="ssh -p 2222 root@127.0.0.1"
```

The S2 and S3 harness clients dial `127.0.0.1:9000` because they assume the
Topology B reverse tunnel. In Topology A, replace `127.0.0.1:9000` with
`$ECOSYS_HUB` in those blocks.

Copy the fixtures to the Pi when the Pi is the sender:

```bash
$PI_SSH 'mkdir -p /root/fixture-test'
scp -P 2222 tools/e2e-media/thumbs-up.jpg tools/e2e-media/demo-yue.wav \
  root@127.0.0.1:/root/fixture-test/
```

### 4.2 S1: necklace camera, 聽障 glasses

On the Pi, with the real UVC webcam:

```bash
$PI_SSH 'cd /root/necklace && PI_ECOSYS_VIDEO_SOURCE=uvc \
  PI_ECOSYS_HUB='"$ECOSYS_HUB"' \
  ~/.local/bin/uv run python -m ecosys_pi --mode video --hub '"$ECOSYS_HUB"''
```

Present a thumbs-up to the webcam. Expected:

- hub logcat: `inbound image ... device=ecosys-pi role=DAEMON`;
- hub logcat: `AiGesturePort: recognize: label=Thumb_Up ... hands=1`;
- hub logcat: `AiTtsPort: synthesize '謝謝' ...`;
- glasses (聽障) shows the word; the Pi's `aplay` plays the synthesized word.

If the camera is unavailable, the mock fallback from the repo is:

```bash
go run ./tools/mockpeer --addr "${ECOSYS_HUB}" --role daemon \
  --image tools/e2e-media/thumbs-up.jpg --frames 1
```

### 4.3 S2: Cantonese speech, 聽障 glasses

Run the speech client on the Pi, which uses the Pi repo's generated stubs:

```bash
$PI_SSH 'cd /root/necklace && PYTHONPATH=/root/necklace/src \
  .venv/bin/python /tmp/send_speech.py \
  --wav /root/fixture-test/demo-yue.wav --addr 127.0.0.1:9000 --mode fast'
```

The repo-native client is:

```bash
go run ./tools/mockpeer --addr "${ECOSYS_HUB}" --role glass \
  --audio-file tools/e2e-media/demo-yue.wav --frames 1
```

Expected output from the harness client:

```text
WAV path=/root/fixture-test/demo-yue.wav channels=1 sample_width=2 rate=16000 frames=82368 duration_s=5.148
RESULT text='呢几个字都表达唔到我想讲嘅意思' speaker='' pts_ms=0
SUMMARY status=ok results=1 audio_chunks=52 elapsed=2.55s tts_bytes=0
TRANSCRIPT text='呢几个字都表达唔到我想讲嘅意思' speaker=''
```

Exit code 0 means a non-empty transcript. An empty `speaker=''` is correct for
an unenrolled speaker. TTS is not returned for an audio transcript; TTS belongs
to gesture results.

#### 4.3.1 Enrolled speaker: `Alice`, not `''`

To prove the speaker-attribution path, enroll the same voice on the hub first,
then stream the WAV back on the same call. The client is `/tmp/enroll_speaker.py`
(dev host, and the same path on the Pi). It uses the Pi repo's generated stubs:

```bash
$PI_SSH 'cd /root/necklace && PYTHONPATH=/root/necklace/src \
  .venv/bin/python /tmp/enroll_speaker.py \
    --wav /root/fixture-test/demo-yue.wav --addr 127.0.0.1:9000 \
    --name Alice --enroll-mode pad --audio-mode single'
```

Steps on the one `OpenStream` call:

1. Send `stream_open` with `device_id="fixture-speech"` and `role=GLASS`.
2. Send `enroll_request` with `name="Alice"` and the demo WAV's PCM payload
   zero-padded to at least 8 s. `--enroll-mode pad` pads to 272000 B (8.5 s),
   which clears `AudioFormat.ENROLLMENT_MIN_BYTES` = 256000 B (8 s). The
   `enroll_response` must be `ok=true` before any audio is sent.
3. Send the WAV's exact PCM payload as `audio_pcm16_16k` in 100 ms chunks, then
   half-close the request side.
4. Expect a non-empty transcript attributed to the enrolled name:

```text
ENROLL ok=True reason=''
RESULT text='呢几个字都表达唔到我想讲嘅意思' speaker='Alice' pts_ms=0
SUMMARY status=ok enroll_ok=True results=1 elapsed=1.56s enroll_mode=pad audio_mode=single
TRANSCRIPT text='呢几个字都表达唔到我想讲嘅意思'
SPEAKER_LABEL label='Alice'
```

The client exits 0 only when `enroll_response.ok` is true, the transcript is
non-empty, and the speaker label is non-empty. On the hub the enrollment store
writes an app-private `files/enrollment.txt` (mode `0600`) holding the
name/voiceprint rows; assert it with:

```bash
adb -s emulator-5554 shell 'run-as com.glassstorm.phonemanager ls -la files/enrollment.txt'
```

Expected `-rw------- ... files/enrollment.txt`. The recorded run is
`.omo/evidence/three-end/s2-enrollment-asr.txt`: CAM++ produced a 192-dim
voiceprint and the same stream then resolved to `Alice` at the 0.5 cosine
threshold. An unmatched speaker keeps the empty label, so `speaker='Alice'` is
not injected by the client.

### 4.4 S3: glasses camera, 健全 glasses

1. Put the glasses in 健全 under **Settings → 切換身份**.
2. Launch with the hub host extra and start recording.
3. Present a thumbs-up to the glasses camera.

Expected:

- hub logcat: `inbound image ... device=glasses role=GLASS`;
- hub logcat: `AiGesturePort: recognize: label=Thumb_Up ... hands=1`;
- glasses overlay shows the word and the glasses plays the hub TTS;
- hub 即時 shows the 影像 counter increasing.

The mock sender for the same route is:

```bash
$PI_SSH 'cd /root/necklace && PYTHONPATH=/root/necklace/src \
  .venv/bin/python /tmp/send_fixture.py \
  --fixture /root/fixture-test/thumbs-up.jpg --addr 127.0.0.1:9000'
```

Expected:

```text
GESTURE label='Thumb_Up' score=... text='謝謝' pts_ms=...
TTS bytes=<non-zero>
SUMMARY status=ok gesture_label='Thumb_Up' ... tts_bytes=<non-zero>
```

## 5. Known limits

- **400 ms pause.** The speech segmenter closes an utterance after about 400 ms
  of silence (or 10 s, or stream close). Speak the phrase and then stop; do not
  pause mid-phrase for longer than 400 ms unless testing boundary behaviour.
- **One role at a time, one GLASS session at a time.** Switching between 聽障 and
  健全 restarts the stream. Two GLASS sessions with the same device id confuse
  the hub's peer list and the TTS routing.
- **The glasses camera can be physically covered.** In the recorded harness the
  glasses camera saw a blank, overexposed frame, so the real MediaPipe engine
  returned `hands=null` and no gesture/TTS could be produced. That is an optical
  limitation, not an engine error.
- **The emulator harness is not page-14 phone-AP evidence.** It cannot prove the
  phone-hotspot path, the arm64 phone's real engines, or DNS-SD over the SoftAP.
  Run Topology A for the real claim.
- **adb `forward`/`reverse` do not work in this harness.** Use the `socat` bridge
  and the emulator-initiated pairer.
- **The x86_64 harness APK is an installed artifact.** It is not built by the
  repo and it does not change source. The arm64 slice can crash in TTS under
  libndk translation.
- **`synthetic` media is test-only.** It is valid for transport checks but not for
  the demo. A gesture or transcript proof must come from the real camera or the
  real WAV, with the real engine log lines in logcat.

## 6. Evidence index

All files are under `.omo/evidence/three-end/`, which is git-ignored and
local-only. No file contains a PSK, token, or password.

| File                                                                         | Proves                                                                    |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `pi-ssh-uplink.txt`                                                          | Pi SSH reachable, `usb0` is the management link, uplink works             |
| `pi-tooling.txt`                                                             | Pi has ALSA, ffmpeg, V4L2, rsync, curl; `/dev/video0` is MJPEG-capable    |
| `pi-uv.txt`                                                                  | `uv` and managed CPython 3.12 installed on the Pi                         |
| `pi-tests.txt`                                                               | Pi repo deployed to `/root/necklace`; suite passes with the venv flag     |
| `pi-config.txt`                                                              | The Pi CLI loads `config.json` on the real path                           |
| `pi-uvc-source.txt`                                                          | UVC/MJPEG source opens, 3 valid frames, consecutive frames differ         |
| `pi-uvc-hub-count.txt`                                                       | Hub inbound image count for the 3-frame UVC run                           |
| `pi-stream-open.txt`                                                         | `stream_open` is the first frame; live hub session is `role=DAEMON`       |
| `pi-gateway.txt`                                                             | `wlan0` gateway is preferred over the `usb0` management route             |
| `pi-bridge.txt`                                                              | Pi -> container -> emulator TCP bridge, one real Pi frame through the hub |
| `hub-apk-build.log`                                                          | Hub debug APK builds                                                      |
| `hub-apk-install.log`                                                        | Hub APK installs on the emulator                                          |
| `hub-apk-sha256.txt`                                                         | Installed hub APK hash                                                    |
| `hub-relay-tts.txt`                                                          | Sender-only TTS routing; relay and app suites green; cross-repo E2E       |
| `hub-fixture-test.txt`                                                       | Real hand JPEG -> real MediaPipe gesture -> real TTS on the hub           |
| `deploy-26.txt`                                                              | Deployed binary freshness check and the IS1 topology capture              |
| `is1-topology.png`                                                           | IS1 topology capture: hub, glasses, and Pi connected at once              |
| `cross-repo-e2e.txt`                                                         | `:app:ecosysPiE2e` drives the real Pi CLI against a fake-engined hub      |
| `glasses-apk-build.log`                                                      | Glasses debug APK builds                                                  |
| `glasses-apk-install.log`                                                    | Glasses APK installs on `YM00FCF87W0021`                                  |
| `glasses-apk-sha256.txt`                                                     | Installed glasses APK hash                                                |
| `glasses-session-mode.txt`                                                   | 聽障/健全 mode predicates and the display/audio split                     |
| `glasses-enrollment.txt`                                                     | Enrollment against the stored hub host on one stream                      |
| `glasses-jpeg-unit.txt`                                                      | Glasses JPEG downscale/encode unit coverage                               |
| `glasses-docs.txt`                                                           | Glasses docs corrected to port 9000 and the scenario table                |
| `s2-mock-asr.txt`                                                            | Real hub ASR returns the Cantonese transcript over gRPC                   |
| `s2-enrollment-asr.txt`                                                      | Real hub enrolls `Alice` (CAM++ 192-dim) and attributes the same WAV      |
| `s3-camera-uplink.txt`                                                       | 31 real glasses frames reach a GLASS session; engine runs; blank scene    |
| `s3-camera-frame.jpg`                                                        | One real frame extracted from the glasses uplink                          |
| `s3-glasses-recording.png`                                                   | Glasses recording UI during S3                                            |
| `s3-glasses-logcat.txt`                                                      | Glasses `S3 camera uplink active` and `S3 image frame -> hub`             |
| `s3-hub-logcat.txt`                                                          | Hub inbound image lines and per-frame `AiGesturePort` calls               |
| `s3-hub-live.png`                                                            | Hub 即時 tab with the image counter                                       |
| `s3-live-glasses.png`                                                        | Live S3 glasses capture                                                   |
| `s3-live-hub.png`                                                            | Live S3 hub capture                                                       |
| `s3-uplink-capture.bin`                                                      | Raw glasses-to-hub byte stream                                            |
| `emo-engine-logcat.txt`                                                      | Real engine load lines on the emulator                                    |
| `emo-models.txt`                                                             | Provisioned model tree on the emulator                                    |
| `fetch-stdout.txt`, `fetch-stderr.txt`, `fetch-time.txt`, `fetch-timing.txt` | Model fetch run                                                           |
| `failures-camera.txt`                                                        | Missing UVC device exits non-zero with a named error                      |
| `failures-config.txt`                                                        | Reverted CLI config loader fails the discovery test                       |
| `failures-gateway.txt`                                                       | `usb0`-only route fails with a named error when no `wlan0` AP exists      |
| `hub-regression.txt`                                                         | Hub unit suite green across all eight modules                             |
| `glasses-regression.txt`                                                     | Glasses unit tests (101) and lint green                                   |
| `pi-regression.txt`                                                          | Pi suite green on the dev host (211) and on the Pi                        |
| `glasses-suppression.txt`                                                    | Deprecated CameraX call replaced, suppression removed                     |
| `F1-compliance.txt`                                                          | Plan compliance re-audit after remediation                                |
| `F2-code-quality.txt`                                                        | Code quality re-review across the three repos                             |
| `F4-fidelity.txt`                                                            | Ideal-state fidelity re-walk IS1-IS6                                      |
| `runbook.txt`                                                                | This runbook and its evidence index were written                          |
| `runbook-index.txt`                                                          | Index-coverage audit: every file in this directory is named here          |

## 7. Cleanup

```bash
# stop the harness helpers
pkill -f pairer-linux-amd64
pkill -f 'ssh -N -f -o ExitOnForwardFailure=yes -p 2222 -R 9000'
adb -s emulator-5554 shell 'pkill -f /data/local/tmp/hubproxy'

# stop the hub UI if needed
adb -s emulator-5554 shell am force-stop com.glassstorm.phonemanager
adb -s YM00FCF87W0021 shell am force-stop hk.edu.hkmu.speakerdiarazationdemo
```

Do not commit `.omo/`, `local.properties`, model trees, or any file that carries
the hotspot SSID or PSK.
