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

| Peer              | Role value                     | Who it is                                                                  |
| ----------------- | ------------------------------ | -------------------------------------------------------------------------- |
| Phone hub         | (none, it authenticates peers) | This repo. Hotspot + discovery + pairing/auth + A/V relay + pluggable STT. |
| Smart glasses app | `DEVICE_ROLE_GLASS`            | Out of scope here. Streams audio and video, receives transcripts.          |
| Ubuntu daemon     | `DEVICE_ROLE_DAEMON`           | Out of scope here. Long-lived paired peer.                                 |

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

| Field       | Number | Type         | Semantics                          |
| ----------- | ------ | ------------ | ---------------------------------- |
| `device_id` | 1      | `string`     | The peer's device id.              |
| `kind`      | 2      | `StreamKind` | Which media the stream will carry. |

This message is part of the frozen contract. It is NOT used by
`StreamService/OpenStream` today: the session binds to the device the bearer
token proved, and the media kind is inferred from which payload variant each
frame carries. The header comment in the proto says the same thing: the message
is frozen, and peers may exchange it over their own side channel.

### `StreamResult`

| Field           | Number | Type     | Semantics                                                                      |
| --------------- | ------ | -------- | ------------------------------------------------------------------------------ |
| `text`          | 1      | `string` | The recognized utterance.                                                      |
| `speaker_label` | 2      | `string` | Speaker label. Always empty in v1; diarization is NOT implemented (section 8). |
| `pts_ms`        | 3      | `int64`  | Presentation timestamp in milliseconds.                                        |

### `StreamFrame`

One frame on the bidirectional stream. Exactly one payload variant is set at a
time, because the payload is a `oneof`. Media payloads are OPAQUE to the hub.

| oneof case        | Number | Type           | Semantics                                                                                                    |
| ----------------- | ------ | -------------- | ------------------------------------------------------------------------------------------------------------ |
| `audio_pcm16_16k` | 1      | `bytes`        | Raw little-endian PCM16 mono at 16 kHz. The hub never decodes it, it forwards it to the STT engine.          |
| `video_h264_nal`  | 2      | `bytes`        | Raw H.264 NAL units. The hub NEVER decodes or re-encodes them, and it never renders video. Pure passthrough. |
| `transcript`      | 3      | `string`       | Hub to peer: one recognized utterance as plain text.                                                         |
| `result`          | 4      | `StreamResult` | Hub to peer: the structured form of a recognized utterance.                                                  |

At the generated-code level the payload case is readable as
`StreamFrame.getPayloadCase()` with values `AUDIO_PCM16_16K`, `VIDEO_H264_NAL`,
`TRANSCRIPT`, `RESULT`, `PAYLOAD_NOT_SET` (Kotlin:
`StreamFrame.PayloadCase.*`). A frame whose payload is unset, or whose payload
is `transcript`/`result` arriving FROM a peer, is accepted and ignored by the
hub; those two cases are hub-to-peer output.

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

## 4. Pairing flow

1. **The hub opens a pairing window.** At most ONE window exists at a time. It
   carries a 6-digit PIN drawn from `SecureRandom`, and a TTL. The app's default
   TTL is **120 seconds** (`PairingViewModel.WINDOW_TTL_MS = 120_000L`).
2. **The peer calls `Pair` with that PIN**, its device name, and its role.
3. **The hub validates the PIN** in constant time, then:
    - burns the PIN first (single-use: a successful `Pair` consumes it, and a
      second attempt with the same PIN is rejected with `pin-consumed`),
    - derives a bearer token, stores only the token HASH (see below), and returns
      `token` + `device_id` in `PairResponse`.
4. **The peer keeps the token** and sends it on every subsequent call as gRPC
   metadata `authorization: Bearer <token>`.

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

## 5. The auth-rejection contract

**Exactly ONE method is reachable without a bearer token:
`ecosys.v1.PairingService/Pair`.** This is enforced by a server interceptor that
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

`StreamService/OpenStream` is a bidirectional stream of `StreamFrame`. The peer
sends media frames; the hub emits `transcript` / `result` frames back on the
SAME stream.

- **Audio is raw little-endian PCM16, mono, at 16 kHz.** The hub's constant is
  `StreamService.AUDIO_SAMPLE_RATE_HZ = 16_000`. The canonical chunk is 20 ms,
  which is 320 samples, 640 bytes. The hub never decodes the audio; it forwards
  it to the configured STT engine.
- **Video is raw H.264 NAL units, OPAQUE.** The hub never decodes, re-encodes,
  transcodes, or renders video. It relays the bytes. Anything that needs video
  to be displayed does so on the peer.
- **Transcripts come back as `transcript` frames** (and the structured
  `StreamResult` on `result`). In v1 the hub emits the plain text; it does not
  emit a `StreamResult` carrying a speaker label, because diarization is out of
  scope.

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

The configured STT engine. The default is a deterministic OFFLINE mock
(`MockSttAdapter`), which requires no network and produces a transcript that is
a pure function of the audio bytes (`mock:<byte-length>:<fnv1a64-hex>`). The
optional Speechmatics adapter sends `pcm_f32le` at 16 kHz to a cloud endpoint
and requires the phone's OWN cellular uplink; the peers never reach the
internet, only the phone does. The engine is selectable from the app's Settings
screen; the mock is the v1 default and the cloud path is not an acceptance gate.

---

## 7. Discovery contract

The hub advertises a DNS-SD service of type **`_ecosys._tcp`**. A peer may
resolve it over mDNS and learn the hub's address and port.

There is ALSO a **first-class direct-IP fallback** to the hub's gateway address.
The phone IS the access point, so the hub is reachable at the AP gateway,
typically `192.168.43.1` on Android's tether. This fallback is the PRIMARY dev
path in the manual-tether flow, and it is a NORMAL outcome, not an error. An
mDNS miss or timeout resolves to the gateway address rather than throwing.

A peer should therefore try mDNS first and fall back to the gateway IP without
treating the fallback as a failure. The advertised port is the hub's gRPC port.

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

Pairing and liveness.

| RPC         | Request            | Response            | Auth                                                                         |
| ----------- | ------------------ | ------------------- | ---------------------------------------------------------------------------- |
| `Pair`      | `PairRequest`      | `PairResponse`      | **NONE** (the sole unauthenticated method, and only while a window is open). |
| `Heartbeat` | `HeartbeatRequest` | `HeartbeatResponse` | Bearer token required.                                                       |

### `StreamService`

Bidirectional media and results relay.

| RPC          | Request              | Response             | Auth                   |
| ------------ | -------------------- | -------------------- | ---------------------- |
| `OpenStream` | `stream StreamFrame` | `stream StreamFrame` | Bearer token required. |

Full method names on the wire: `ecosys.v1.PairingService/Pair`,
`ecosys.v1.PairingService/Heartbeat`, `ecosys.v1.StreamService/OpenStream`.

---

## 10. What is explicitly NOT in v1

Do not build against these; they do not exist.

- **No diarization and no speaker enrollment.** `StreamResult.speaker_label` is
  always empty.
- **No Wi-Fi Direct and no BLE transports.** The hub is the AP; discovery is
  mDNS plus the direct-IP fallback.
- **No media storage or recording.** The hub relays media and does not persist
  it. There is no session history.
- **No on-device ASR model.** Transcription is a pluggable engine: offline mock
  by default, cloud Speechmatics optionally.
- **No TLS / SPAKE2+ / Noise** (documented upgrade path only, section 8).
- **No video decode or render** anywhere on the hub.

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
