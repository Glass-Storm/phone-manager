# The `ecosys.v1` protocol contract

This document is the FROZEN interface contract between three peers: the phone hub
(this repository) and the two peers that are built OUT OF SCOPE here, the
smart-glasses app and the Ubuntu daemon. Read it before you write a client. The
source of truth is the proto file itself; this document explains the semantics
around it.

Source of truth: [`contract/src/main/proto/ecosys/v1/ecosys.proto`](../contract/src/main/proto/ecosys/v1/ecosys.proto).

---

## 1. Overview and roles

The phone is the hub. It is the access point AND the server. The two other peers
dial in to it, they never accept connections from it and they never talk to each
other.

| Peer              | Role value               | Who it is                                                                                                                   |
| ----------------- | ------------------------ | --------------------------------------------------------------------------------------------------------------------------- |
| Phone hub         | (none; it is the server) | This repo. Hotspot + gateway discovery + an unauthenticated stream + A/V/image relay + on-device speech/gesture/TTS.        |
| Smart glasses app | `DEVICE_ROLE_GLASS`      | Out of scope here. Streams mic audio, shows speaker-tagged subtitles and gestures, plays synthesized audio, enrolls voices. |
| Necklace / daemon | `DEVICE_ROLE_DAEMON`     | Out of scope here. Sends camera JPEG stills and plays the hub-synthesized audio.                                            |

The proto package is `ecosys.v1`. The `.proto` file is the SINGLE shared
contract: both out-of-scope peers generate their client stubs from this same
file, so a change here is a change to their wire surface. The proto has
`option java_multiple_files = true` (one Java file per message) and a
`option go_package` for the Go codegen used by the mockpeer. Kotlin/JVM codegen
uses protobuf-lite; see `service/build.gradle.kts`.

---

## 2. Messages

### `PairRequest`

Sent to `PairingService/Pair`. Only ever sent on `Pair`.

| Field         | Number | Type         | Semantics                                                                                             |
| ------------- | ------ | ------------ | ----------------------------------------------------------------------------------------------------- |
| `pin`         | 1      | `string`     | The single-use 6-digit code the hub shows inside an open pairing window. Never sent on any other RPC. |
| `device_name` | 2      | `string`     | Human-readable name the peer wants to be known by. Empty is rejected with `name-missing`.             |
| `role`        | 3      | `DeviceRole` | Which side of the ecosystem this peer is.                                                             |

### `PairResponse`

| Field           | Number | Type     | Semantics                                                                  |
| --------------- | ------ | -------- | -------------------------------------------------------------------------- |
| `ok`            | 1      | `bool`   | `true` on success. `false` on rejection.                                   |
| `token`         | 2      | `string` | The bearer token. Issued ONCE, only when `ok` is true. Empty on rejection. |
| `device_id`     | 3      | `string` | Opaque hub-assigned device id. Empty on rejection.                         |
| `reject_reason` | 4      | `string` | Machine-checkable reason on rejection (see section 4). Empty on success.   |

### `HeartbeatRequest`

| Field       | Number | Type     | Semantics                                                                                                                                                               |
| ----------- | ------ | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `device_id` | 1      | `string` | Identifies the paired peer. NOTE: the hub attributes a heartbeat to the TOKEN's device, never to this client-supplied value; the field is carried for peer bookkeeping. |

### `HeartbeatResponse`

| Field            | Number | Type    | Semantics                                                                    |
| ---------------- | ------ | ------- | ---------------------------------------------------------------------------- |
| `ok`             | 1      | `bool`  | `true` when the call was authenticated and served.                           |
| `server_time_ms` | 2      | `int64` | Hub clock in milliseconds since the Unix epoch. Peers use it to detect skew. |

### `StreamOpenRequest`

| Field       | Number | Type         | Semantics                           |
| ----------- | ------ | ------------ | ----------------------------------- |
| `device_id` | 1      | `string`     | The peer's device id.               |
| `kind`      | 2      | `StreamKind` | Which media the stream will carry.  |
| `role`      | 3      | `DeviceRole` | The peer's role (glass vs. daemon). |

With bearer-token auth removed, this message IS used by
`StreamService/OpenStream`: a peer sends it as the first frame (`stream_open`,
field 10) to bind its session to a device id and role, which is how the hub routes
frames. The media kind is still inferred from which payload variant each frame
carries.

### `StreamResult`

| Field           | Number | Type     | Semantics                                                                  |
| --------------- | ------ | -------- | -------------------------------------------------------------------------- |
| `text`          | 1      | `string` | The recognized utterance.                                                  |
| `speaker_label` | 2      | `string` | Resolved speaker name. EMPTY when the speaker is not enrolled (section 8). |
| `pts_ms`        | 3      | `int64`  | Presentation timestamp in milliseconds.                                    |

### `ImageFrame`

A single JPEG still captured by the necklace camera (320×240 at 10 fps). This is
the ONE media payload the hub decodes — into a bitmap, solely to run isolated
gesture recognition on the still. It never re-encodes or stores the bytes, and
the payload stays opaque to every other layer.

| Field    | Number | Type    | Semantics                               |
| -------- | ------ | ------- | --------------------------------------- |
| `jpeg`   | 1      | `bytes` | Raw JPEG bytes of the still.            |
| `pts_ms` | 2      | `int64` | Presentation timestamp in milliseconds. |
| `width`  | 3      | `int32` | Still width in pixels (320).            |
| `height` | 4      | `int32` | Still height in pixels (240).           |

### `GestureResult`

One recognized isolated gesture, emitted by the hub toward the glasses.
`label` is the raw recognizer class; `text` is the spoken meaning. There is no
sentence-level sign-language translation — isolated canned labels only.

| Field        | Number | Type     | Semantics                                     |
| ------------ | ------ | -------- | --------------------------------------------- |
| `text`       | 1      | `string` | The spoken meaning of the recognized gesture. |
| `label`      | 2      | `string` | The raw recognizer class label.               |
| `confidence` | 3      | `float`  | Recognizer confidence in `[0, 1]`.            |
| `pts_ms`     | 4      | `int64`  | Presentation timestamp in milliseconds.       |

### `TtsAudio`

Hub-synthesized speech as raw little-endian PCM16 mono **@ 16 kHz**, played by
the necklace or the glasses. The hub never sends another sample rate; the peers
only play the bytes, they never synthesize.

| Field       | Number | Type    | Semantics                               |
| ----------- | ------ | ------- | --------------------------------------- |
| `pcm16_16k` | 1      | `bytes` | Raw little-endian PCM16 mono @ 16 kHz.  |
| `pts_ms`    | 2      | `int64` | Presentation timestamp in milliseconds. |

### `EnrollRequest`

A voiceprint-enrollment request from the glasses: a display name plus a raw
little-endian PCM16 mono @ 16 kHz sample. Enrollment rides the stream as a
frame, so no new RPC (and no token) is introduced.

| Field             | Number | Type     | Semantics                                                                                                     |
| ----------------- | ------ | -------- | ------------------------------------------------------------------------------------------------------------- |
| `name`            | 1      | `string` | Display name. Must match `^[\p{Han}A-Za-z0-9]+$` (no whitespace), length 1–24; the hub rejects anything else. |
| `audio_pcm16_16k` | 2      | `bytes`  | Raw little-endian PCM16 mono @ 16 kHz. The hub requires at least 8 s of audio.                                |
| `pts_ms`          | 3      | `int64`  | Presentation timestamp in milliseconds.                                                                       |

### `EnrollResponse`

The hub's reply to an `EnrollRequest`. On rejection `ok` is false and `reason`
carries a machine-checkable explanation (for example `name-invalid` or
`audio-too-short`).

| Field    | Number | Type     | Semantics                              |
| -------- | ------ | -------- | -------------------------------------- |
| `ok`     | 1      | `bool`   | `true` when the voiceprint was stored. |
| `reason` | 2      | `string` | Machine-checkable rejection reason.    |

### `StreamFrame`

One frame on the bidirectional stream. Exactly one payload variant is set at a
time, because the payload is a `oneof`. Media payloads are OPAQUE to the hub,
with ONE exception: `image.jpeg` is decoded (never re-encoded or stored) so the
hub can run gesture recognition on the still.

| oneof case        | Number | Type                | Direction              | Semantics                                                                                                    |
| ----------------- | ------ | ------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------ |
| `audio_pcm16_16k` | 1      | `bytes`             | peer → hub             | Raw little-endian PCM16 mono at 16 kHz. The hub never decodes it; it forwards it to the speech engine.       |
| `video_h264_nal`  | 2      | `bytes`             | peer → hub             | Raw H.264 NAL units. The hub NEVER decodes or re-encodes them, and it never renders video. Pure passthrough. |
| `transcript`      | 3      | `string`            | hub → peer             | Frozen legacy form of a recognized utterance as plain text; no longer emitted (use `result`).                |
| `result`          | 4      | `StreamResult`      | hub → peer             | The structured form of a recognized utterance (text + speaker).                                              |
| `image`           | 5      | `ImageFrame`        | necklace → hub         | A JPEG still; the hub decodes it for gesture recognition.                                                    |
| `gesture`         | 6      | `GestureResult`     | hub → glasses          | One recognized isolated gesture.                                                                             |
| `tts_audio`       | 7      | `TtsAudio`          | hub → necklace/glasses | Hub-synthesized PCM16 @ 16 kHz for the peer to play.                                                         |
| `enroll_request`  | 8      | `EnrollRequest`     | glasses → hub          | A name + voiceprint sample to enroll.                                                                        |
| `enroll_response` | 9      | `EnrollResponse`    | hub → glasses          | The enrollment outcome.                                                                                      |
| `stream_open`     | 10     | `StreamOpenRequest` | peer → hub             | The session-binding bootstrap, sent as the FIRST frame. Binds device id + role (no token; section 4).        |

At the generated-code level the payload case is readable as
`StreamFrame.getPayloadCase()` with values `AUDIO_PCM16_16K`, `VIDEO_H264_NAL`,
`TRANSCRIPT`, `RESULT`, `IMAGE`, `GESTURE`, `TTS_AUDIO`, `ENROLL_REQUEST`,
`ENROLL_RESPONSE`, `STREAM_OPEN`, `PAYLOAD_NOT_SET` (Kotlin:
`StreamFrame.PayloadCase.*`). A frame whose payload is unset, or whose payload is
a hub-to-peer output case (`transcript`/`result`/`gesture`/`tts_audio`/
`enroll_response`) arriving FROM a peer, is accepted and IGNORED by the hub.

---

## 3. Enums

Both enums reserve `0` as `UNSPECIFIED` so a default-constructed request never
silently claims a role or a media kind.

### `DeviceRole`

| Value                     | Number | Meaning                                                                  |
| ------------------------- | ------ | ------------------------------------------------------------------------ |
| `DEVICE_ROLE_UNSPECIFIED` | 0      | The zero value. A default-constructed `PairRequest` never claims a role. |
| `DEVICE_ROLE_GLASS`       | 1      | The smart-glasses app.                                                   |
| `DEVICE_ROLE_DAEMON`      | 2      | The Ubuntu daemon.                                                       |

`PairRequest.device_name` must be non-blank; the hub does NOT reject an
`UNSPECIFIED` role, it stores the role string as given.

### `StreamKind`

| Value                     | Number | Meaning         |
| ------------------------- | ------ | --------------- |
| `STREAM_KIND_UNSPECIFIED` | 0      | The zero value. |
| `STREAM_KIND_AUDIO`       | 1      | Audio.          |
| `STREAM_KIND_VIDEO`       | 2      | Video.          |

`StreamKind` is declared but not consumed by `OpenStream` in v1 (see
`StreamOpenRequest` above).

---

## 4. Pairing flow (REMOVED FROM THE RUNTIME)

> **Removed in v3.** Pairing, PIN and bearer-token authentication were removed
> from the hub runtime. `PairingService/Pair` and `PairingService/Heartbeat` are
> still declared in the `.proto` (frozen; unused/reserved), but the hub no longer
> serves them, mints tokens, or verifies credentials. `OpenStream` is opened
> UNAUTHENTICATED; the peer binds its identity with the `stream_open` frame
> (section 6). The messages and reasons below remain frozen for wire compatibility
> with peers generated from older revisions.

1. **The hub opens a pairing window.** At most ONE window exists at a time. It
   carries a 6-digit PIN drawn from `SecureRandom`, and a TTL. The app's default
   TTL is **120 seconds**.
2. **The peer calls `Pair` with that PIN**, its device name, and its role.
3. **The hub validates the PIN** in constant time, then:
    - burns the PIN first (single-use: a successful `Pair` consumes it, and a
      second attempt with the same PIN is rejected with `pin-consumed`),
    - derives a bearer token, stores only the token HASH (see below), and returns
      `token` + `device_id` in `PairResponse`.
4. **The peer keeps the token** and sends it on every subsequent call as gRPC
   metadata `authorization: Bearer <token>`.

None of steps 1–4 is reachable in the current hub runtime.

Wrong PINs are counted. After **5 failed attempts**
(`PairingService.MAX_PIN_ATTEMPTS = 5`) the current PIN is locked; only a fresh
window clears the counter and issues a new PIN. The failed-attempt counter lives
with the window, in memory.

**The token is returned exactly once.** Only its HASH is persisted: the hub
stores `SHA-256(token)` and compares with `MessageDigest.isEqual` (constant
time). Token derivation uses PBKDF2-HMAC-SHA256 with a per-pairing 16-byte
`SecureRandom` salt and 210,000 iterations by default; the token is rendered as
unpadded base64url. A hub restart loses nothing durable: device rows and token
hashes persist, the pairing window does not.

**Rejection reasons** (`PairResponse.reject_reason` when `ok` is false):

| Reason         | Meaning                                                        |
| -------------- | -------------------------------------------------------------- |
| `no-window`    | No pairing window is currently open.                           |
| `pin-missing`  | The request carried no usable PIN.                             |
| `name-missing` | The request carried no device name.                            |
| `pin-expired`  | The window's TTL has elapsed.                                  |
| `pin-consumed` | The PIN was already used; recovery requires a fresh window.    |
| `pin-locked`   | Too many failed attempts against the current PIN.              |
| `pin-invalid`  | The window is open but the supplied PIN is not the window PIN. |

A rejection is a NORMAL outcome, not a transport error: it is a successful RPC
carrying `ok=false`. Nothing is persisted and no token exists on any rejection
path.

---

## 5. The auth-rejection contract (REMOVED FROM THE RUNTIME)

> **Removed in v3.** There is no bearer-token gate on the hub any more: every RPC
> it serves is unauthenticated. There is no `AuthInterceptor`, no
> `UNAUTHENTICATED` rejection, and no revocation. The section below describes the
> retired v1 behaviour and is kept only as history.

In v1 exactly ONE method was reachable without a bearer token:
`ecosys.v1.PairingService/Pair`. This is enforced by a server interceptor that
matches the full method name `package.Service/Method`, so it cannot be bypassed
by casing or by a method-name collision across services.

Every other RPC, including `PairingService/Heartbeat`, requires a well-formed
`authorization: Bearer <token>` metadata header carrying a token the hub
recognises. The header is parsed strictly: exactly the scheme string `Bearer`,
one space, then the token. A bare token, `Basic <token>`, an empty bearer, or a
lookalike scheme are all rejected.

On any failure the call is closed immediately with the gRPC status
`UNAUTHENTICATED`, before any service method body runs. The rejection is
deliberately uniform:

- the description never distinguishes missing, unknown, tampered, or revoked;
- it never echoes the token or the PIN;
- peers must not expect a machine-readable reason on this status. Treat every
  `UNAUTHENTICATED` as "re-pair or give up".

**Revocation makes a previously valid token fail immediately.** The hub deletes
the device row, which removes the token hash, so the same token that worked a
moment ago is refused with `UNAUTHENTICATED` on the next call.

Wrong, expired, or replayed PINs are a DIFFERENT channel: they arrive as a
successful RPC with `PairResponse.ok=false` and a `reject_reason`, never as
`UNAUTHENTICATED`.

---

## 6. Stream framing

`StreamService/OpenStream` is a bidirectional stream of `StreamFrame`. Every
session OPENS with a `stream_open` frame carrying `StreamOpenRequest{device_id,
kind, role}` — with auth removed, this is how the hub binds the session to a
device and routes frames. The peer then sends media frames; the hub emits
`result` / `gesture` / `tts_audio` / `enroll_response` frames back on the SAME
stream.

- **Audio is raw little-endian PCM16, mono, at 16 kHz.** The hub's constant is
  `StreamService.AUDIO_SAMPLE_RATE_HZ = 16_000`. The canonical chunk is 20 ms,
  which is 320 samples, 640 bytes. The hub never decodes the audio; it forwards
  it to the on-device speech engine.
- **Video is raw H.264 NAL units, OPAQUE.** The hub never decodes, re-encodes,
  transcodes, or renders video. It relays the bytes. Anything that needs video
  to be displayed does so on the peer.
- **Images (`image`) are JPEG stills.** This is the ONE media kind the hub
  decodes: it turns each still into a bitmap and runs isolated gesture
  recognition on it. It never stores or re-encodes the bytes. The necklace sends
  these at 320×240 and 10 fps.
- **Routing is by session role.** A necklace session's `image` frame becomes a
  `gesture` frame on the GLASSES session AND a `tts_audio` frame back on the
  necklace; a glasses session's `audio_pcm16_16k` becomes a `result` frame on the
  SAME session. Cross-session fan-out is tracked by a registry in `:core:service`.
- **Transcripts come back as `result` frames** carrying `StreamResult{text,
speaker_label, pts_ms}`. The `speaker_label` is the resolved speaker name, and
  is empty when the speaker is not enrolled. The bare `transcript` variant
  (field 3) is frozen but no longer emitted by the hub.
- **Enrollment rides the stream.** A glasses `enroll_request{name, audio}` yields
  an `enroll_response{ok, reason}` on the same session; there is no enrollment
  RPC (section 9).

### Backpressure policy

The relay keeps SEPARATE queues per media kind, because one queue cannot express
both policies.

| Media | Policy                                                        | Observable effect                                                                        |
| ----- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| Audio | **NEVER dropped.** The producer PARKS when the queue is full. | The audio counter only ever grows for accepted frames; no audio is silently lost.        |
| Video | **Drops the OLDEST queued frame** under load.                 | The hub tracks a drop counter (`videoDropped`), so a peer can see that frames were shed. |

Session teardown DRAINS rather than cancels: closing a session joins the pumps,
then closes the STT session exactly once. A peer cancel or a downstream failure
still tears the session down cleanly.

### Where transcripts come from

The hub's on-device speech engine. In the JVM/test graph the default is a
deterministic OFFLINE mock, which requires no network and produces a transcript
that is a pure function of the audio bytes (`mock:<byte-length>:<fnv1a64-hex>`);
on a real arm64 device the `:adapter:ai` adapter runs the `ai-android` ASR +
diarization engine locally. Either way the hub emits `result` frames carrying
`StreamResult{text, speaker_label, pts_ms}` — `speaker_label` is the resolved
enrollment name, empty when the speaker is not enrolled. There is NO cloud STT
and NO cloud TTS: transcription and speech synthesis both run on the phone, and
the peers never reach the internet.

---

## 7. Discovery contract

Discovery is **manual-hotspot + gateway**. The hub still advertises a DNS-SD
service of type **`_ecosys._tcp`**, but the peers no longer depend on mDNS: they
join the phone's hotspot by hand and then dial the **default gateway**.

The phone IS the access point, so the hub is reachable at the AP gateway,
typically `192.168.43.1` on Android's tether, on the hub's gRPC port. The Pi
resolves the gateway with `ip route` / `nmcli`; the glasses read it from
`ConnectivityManager`. Reaching the hub through the gateway is the NORMAL
outcome, not an error or a degraded mode; mDNS is a legacy convenience, not the
discovery path peers rely on.

---

## 8. Transport and port

- Transport: **plaintext gRPC (h2c) over the LAN.** No TLS in v1.
- Default hub port: **9000** (`AppComposition.DEFAULT_HUB_PORT`). Tests bind
  an ephemeral port (`0`) and read back the bound port.
- Bind address: the app binds all interfaces (`0.0.0.0`) in production, because
  the phone hosts the AP and its LAN peers must be able to dial in. Tests bind
  loopback (`127.0.0.1`).

### Why plaintext is acceptable for v1

This is a v1 dev-stage posture, NOT a production-final one. It is acceptable
because:

- the traffic stays on a local-only hotspot LAN that the phone itself owns;
- the glasses never touch the internet, so there is no hostile network hop;
- grpc-java's TLS path is broken on Android, so TLS is not a working option for
  the hub's own listener in this toolchain.

Adding TLS is explicitly out of scope for v1. The **documented upgrade path** is:

1. Give every peer a certificate, or pin a self-signed hub certificate on each
   peer, and switch the gRPC transport to TLS.
2. Longer term, move to a mutual authentication handshake (SPAKE2+ or a Noise
   protocol) so pairing is not PIN-over-plaintext.
3. Flip the Android network-security config to forbid cleartext once TLS is in.

None of that is implemented today. Do not treat the plaintext posture as a
promise of confidentiality.

---

## 9. Services and RPCs

### `PairingService`

Pairing and liveness. **Not served by the current hub runtime**: the methods are
retained in the `.proto` for wire compatibility but the hub exposes neither.

| RPC         | Request            | Response            | Auth                               |
| ----------- | ------------------ | ------------------- | ---------------------------------- |
| `Pair`      | `PairRequest`      | `PairResponse`      | not served (retired with pairing). |
| `Heartbeat` | `HeartbeatRequest` | `HeartbeatResponse` | not served (retired with pairing). |

### `StreamService`

Bidirectional media and results relay.

| RPC          | Request              | Response             | Auth      |
| ------------ | -------------------- | -------------------- | --------- |
| `OpenStream` | `stream StreamFrame` | `stream StreamFrame` | **NONE**. |

Full method names on the wire: `ecosys.v1.PairingService/Pair`,
`ecosys.v1.PairingService/Heartbeat`, `ecosys.v1.StreamService/OpenStream`.

---

## 10. What is explicitly NOT in v1 (revised for v3)

Do not build against these; they do not exist.

- **No PIN pairing, no bearer tokens, no revocation.** Pairing/auth were removed
  from the hub runtime (sections 4–5). `PairingService` stays in the `.proto` only
  as a frozen, unserved stub.
- **No Wi-Fi Direct and no BLE transports.** Discovery is a manual hotspot join
  plus the default-gateway address; the hub still advertises `_ecosys._tcp` over
  DNS-SD, but the peers no longer rely on mDNS (section 7).
- **No media storage or recording.** The hub relays media and does not persist
  it. There is no session history.
- **No sentence-level sign-language translation.** Gesture recognition yields
  isolated canned labels only.
- **No TLS / SPAKE2+ / Noise** (documented upgrade path only, section 8).

The following v1 "never" promises are SUPERSEDED by v3 and no longer hold:

- **Diarization and speaker enrollment now exist.** `StreamResult.speaker_label`
  carries the resolved speaker name (empty when the speaker is unregistered), and
  enrollment rides the stream as `enroll_request`/`enroll_response` frames.
- **Transcription runs on-device.** The default engine is a deterministic offline
  mock; the on-device `ai-android` adapter is the production path.
- **JPEG stills ARE decoded on the hub.** The hub decodes each `image` frame
  solely to run isolated gesture recognition. It still never decodes, re-encodes,
  transcodes, or renders the H.264 video stream.

---

## 11. Rules for changing this contract

The contract is FROZEN. The rules are stated in the proto file's own header
(lines 8 to 11) and repeated here:

- **never renumber or reuse a field number or enum value**;
- **never change an existing field's type or name**;
- **additive changes are allowed** (new fields, messages, rpcs) and **new fields
  must be optional**.

Field numbers and enum values are permanent. If you retire something, mark it
reserved; do not hand its number to something else. A peer generated from an
older revision of this file must keep working against a hub generated from a
newer one, and vice versa.

### Retiring without removing

`PairingService` (and the `PairRequest`/`PairResponse`/`HeartbeatRequest`/
`HeartbeatResponse` messages) is the worked example: pairing was dropped from the
hub RUNTIME, but the service is kept byte-identical in the `.proto` as an
unserved, reserved stub so an older peer still links. `StreamService/OpenStream`
remains and is now UNAUTHENTICATED; a peer binds its identity with the
`stream_open` frame instead of a token. Fields 1–4 of `StreamFrame` and every enum
value are untouched: the v3 gesture/diarization work added `image` (5), `gesture`
(6), `tts_audio` (7), `enroll_request` (8), `enroll_response` (9) and
`stream_open` (10), all optional, so an older peer keeps working against a newer
hub and vice versa.

### Consuming this contract as an artifact

The `.proto` above lives in the `:contract` Gradle module, which publishes itself
so a peer does NOT need to clone this repository. The coordinates are:

```
com.glassstorm.phonemanager:contract:1.0.0
```

Two JARs are published for that coordinate:

| Artifact                             | Contains                                                       |
| ------------------------------------ | -------------------------------------------------------------- |
| `contract-1.0.0.jar`                 | the compiled protobuf/gRPC-lite bindings (Java + Kotlin, lite) |
| `contract-1.0.0-proto.jar` (`proto`) | the `.proto` source of truth at `ecosys/v1/ecosys.proto`       |

A peer that generates its own client (Go, Swift, another language) needs the
`proto` classifier JAR — the compiled hub classes are not enough. Extract it and
point `protoc` at the extracted root:

```bash
./gradlew :contract:publishToMavenLocal   # run inside phone-manager
unzip -o ~/.m2/repository/com/glassstorm/phonemanager/contract/1.0.0/contract-1.0.0-proto.jar -d /tmp/contract-proto
protoc -I /tmp/contract-proto --go_out=... ecosys/v1/ecosys.proto
```

A JVM peer instead depends on the main coordinate and gets the bindings (plus the
grpc/protobuf-lite runtime, which the published POM lists as transitive
dependencies):

```kotlin
dependencies { implementation("com.glassstorm.phonemanager:contract:1.0.0") }
```

Publishing targets Maven **local** only: there is no remote repository, no
signing, and no credentials in this build. To publish to a shared repository,
add one under `publishing.repositories` in `contract/build.gradle.kts` — the
coordinates above stay the same.

### Regenerating the bindings

The bindings are generated, never hand-edited. `contract/build.gradle.kts` runs
`protoc` with the `grpc` and `grpckt` plugins in `lite` mode over
`contract/src/main/proto/ecosys/v1/ecosys.proto`. The Go half of the same codegen
is `tools/mockpeer/gen.sh`, which reads this same file:

```bash
./gradlew :contract:generateProto   # Kotlin/Java + gRPC-lite bindings
bash tools/mockpeer/gen.sh          # the Go reference peer's bindings
```
