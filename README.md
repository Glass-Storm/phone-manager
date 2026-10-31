# phone-manager

The phone-side hub of the `ecosys.v1` device ecosystem. It turns an Android phone
into the access point AND the server for two peers: a smart-glasses app and a
Raspberry Pi / Ubuntu daemon. The hub runs a local-only hotspot and serves an
**UNAUTHENTICATED** bidirectional stream: peers bind their identity with a
`stream_open` frame (no PIN, no bearer token), the hub relays audio, video and
JPEG stills between peers and an on-device speech-to-text / gesture / TTS engine,
and returns transcripts, gesture text and synthesized audio on the same stream.

The peer wire contract is frozen in [`docs/protocol.md`](docs/protocol.md). The
source of truth for it is
[`contract/src/main/proto/ecosys/v1/ecosys.proto`](contract/src/main/proto/ecosys/v1/ecosys.proto).
The glasses app and the Ubuntu daemon are OUT OF SCOPE for this repository; this
repo ships the hub plus the contract they generate their clients from.

## Module layout

| Module             | Kind                | Owns                                                                                                                                                                                                                    |
| ------------------ | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `:app`             | Android application | Compose UI (Lumo components), the five screens, and the composition root. The only module allowed to see the services AND the adapters.                                                                                 |
| `:core:model`      | pure Kotlin/JVM     | The hand-written domain DTOs: pure data types with zero dependencies, the shared language spoken across every layer.                                                                                                    |
| `:core:domain`     | pure Kotlin/JVM     | Ports (interfaces) and the two deterministic state machines. Zero implementation, zero framework annotations, no contract edge.                                                                                         |
| `:core:service`    | pure Kotlin/JVM     | The use-case implementations (device, relay, cross-session registry). Contract-free and gRPC-free by construction.                                                                                                      |
| `:transport:grpc`  | pure Kotlin/JVM     | The gRPC service implementations (the UNAUTHENTICATED stream), the netty-backed hub server, and the DTO↔proto mapping. The ONE module allowed to depend on `:contract`.                                                 |
| `:contract`        | pure Kotlin/JVM     | The FROZEN `ecosys.v1` wire contract: the `.proto` source of truth and its protobuf/gRPC-lite codegen. The ONE artifact the out-of-scope peers consume.                                                                 |
| `:adapter:jvm`     | pure Kotlin/JVM     | Android-free port implementations: the deterministic gesture/speech/TTS/enrollment fakes bound by default, and the discarding frame sink.                                                                               |
| `:adapter:android` | Android library     | Platform-backed adapters: SQLite, LocalOnlyHotspot, NSD discovery, and the runtime config store. The battery-exemption helper lives in `:app` (it is app-owned platform glue).                                          |
| `:adapter:ai`      | Android library     | The ai-android INTEGRATION module: device implementations of the gesture / speech+diarization / TTS / enrollment ports, backed by the pinned `hk.edu.hkmu:*` composite build. ISOLATED from `:app` (see below).         |
| `:testing:testkit` | pure Kotlin/JVM     | Test support: the bounded child-process runner, the deterministic media generators, and the in-memory repository double, shared by the `:transport:grpc` and `:app` test suites. Its only dependency is `:core:domain`. |

`tools/mockpeer` (the Go reference peer) is NOT a Gradle module; it stays at the
repo root because the root `go.work` and the Go module are root-relative.
`:build-logic` is a Gradle composite build providing the `phonemanager.*`
convention plugins, not a published module.

## Provisioning the ai-android engine (on-device inference)

The hub's on-device gesture, speech+diarization, TTS and voiceprint-enrollment
adapters live in `:adapter:ai` and are implemented against
[`ai-android`](https://github.com/Glass-Storm/ai-android) (`group hk.edu.hkmu`,
version `0.0.1-alpha.1`). ai-android ships no stable release, so it is consumed as
a **pinned composite build**, never a released artifact.

### The pinned composite build

`settings.gradle.kts` resolves `../../ai-android` (override by cloning there),
asserts its `HEAD` equals `pinnedAiAndroidCommit`, and substitutes the
`hk.edu.hkmu:core|sign|asr|diarize|tts` coordinates for that checkout's projects.
A missing checkout or a drifted commit fails configuration with a named message,
so a build can never silently compile against a different engine.

### Why `:adapter:ai` is isolated

ai-android's MediaPipe path uses **full protobuf-java**, while `:contract` and
`:transport:grpc` use **protobuf-lite**. Putting both on `:app`'s runtime
classpath would ship duplicate `com.google.protobuf.*` classes and break dexing.
`:adapter:ai` is therefore a **separate module that `:app` does not depend on**:
the hub keeps building with the deterministic `:adapter:jvm` fakes bound by
default, and the engine is compiled and packaged only for the arm64 on-device
build. ai-android's gesture/diarization native is `arm64-v8a` only, so a JVM test
could never load it anyway.

Build the engine adapter on its own:

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :adapter:ai:assembleDebug
```

Adopting it on a device is a deliberate graph change: add the `:adapter:ai` edge
to `:app`, bind its constructors in place of the `:adapter:jvm` fakes, and resolve
the protobuf conflict there (exclude `protobuf-javalite` and supply full
`protobuf-java`, which also bundles `GeneratedMessageLite`).

### Model provisioning

The device adapter reads its models from
`context.getExternalFilesDir(null)/models` on an arm64 device.
`scripts/fetch-ai-models.sh [DEST_DIR]` downloads and SHA-256-verifies all four
engine trees into `DEST_DIR` (`DEST_DIR` defaults to `./build/ai-models`):

| Tree                                                                                    | License               | Source                                       |
| --------------------------------------------------------------------------------------- | --------------------- | -------------------------------------------- |
| `asr/model.int8.onnx` + `asr/tokens.txt`                                                | Apache-2.0            | SenseVoice int8 (WSYue variant)              |
| `tts/vits-cantonese-hf-xiaomaiiwn/{onnx,tokens.txt,lexicon.txt,rule.fst}`               | Not declared upstream | Cantonese VITS (xiaomaiiwn)                  |
| `gesture/gesture_recognizer.task`                                                       | Apache-2.0            | MediaPipe Tasks (Google)                     |
| `diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx` plus the CAM++ ONNX | MIT + Apache-2.0      | pyannote/segmentation-3.0 + 3D-Speaker CAM++ |

[`docs/ai-model-provisioning.md`](docs/ai-model-provisioning.md) has the exact
on-device tree, every pinned SHA-256, the ASR/TTS release sources, the licence
caveats, and the `adb push` commands for
`/sdcard/Android/data/com.glassstorm.phonemanager/files/models`.

The Cantonese VITS voice declares no upstream licence; treat it as
local-evaluation-only and do not redistribute it.

## The Gradle preamble (JDK 21 + Android SDK)

Gradle 8.13 cannot run on Java 25, and the shell used by build agents is
non-interactive: it has no `ANDROID_HOME` and it may put Java 25 on `PATH`. Two
mechanisms cover this, and the inline preamble below remains the canonical form
used by every acceptance command in this file:

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew <task>
```

- **JDK 21 is now pinned IN THE REPO**, not only in the command line.
  [`gradle/gradle-daemon-jvm.properties`](gradle/gradle-daemon-jvm.properties)
  declares the Gradle 8.8+ daemon-JVM criteria `toolchainVersion=21`, so a bare
  `./gradlew <task>` selects a JDK 21 daemon wherever Gradle can discover one —
  CI's Temurin 21, or this host's JDK 21 (made discoverable through the
  machine-local, gitignored `~/.gradle/gradle.properties`:
  `org.gradle.java.installations.paths=/home/chaos/.jdk/jdk-21.0.12.1+1`).
  When no JDK 21 can be found, the build fails with a clear toolchain message
  instead of the opaque `What went wrong: 25.0.4.1`. Only the `JAVA_HOME=`
  prefix is therefore optional now; a bare `./gradlew :contract:test` works on
  this host.
- **`ANDROID_HOME`** (or `local.properties`) must still point at the SDK.
  Without it, AGP cannot find `compileSdk 36` / `build-tools 36.0.0` and
  configuration fails. If you prefer to keep the SDK path out of the command
  line, create `local.properties` at the repo root:

    ```properties
    sdk.dir=/home/chaos/Android/Sdk
    ```

    `local.properties` is gitignored and must never be committed.

This path is machine-specific on purpose. CI uses its own SDK and JDK; the
workflow in `.github/workflows/ci.yml` sets its own environment and must not
copy this string.

## Commands

### Build

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :app:assembleDebug
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :app:assembleRelease
```

The release build runs R8 (minify + resource shrink) and is UNSIGNED. There is no
signing configuration in this repo, and the only publishing in it is `:contract`
to Maven local (see below): the release build exists to prove the hub's
runtime-discovered gRPC transport survives shrinking.

### Publish the contract

`:contract` is the one artifact the out-of-scope glasses app and Ubuntu daemon
consume, so it publishes itself instead of forcing a clone:

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :contract:publishToMavenLocal
```

Coordinates `com.glassstorm.phonemanager:contract:1.0.0`; both the compiled
bindings and a `proto`-classifier JAR carrying `ecosys/v1/ecosys.proto` land under
`~/.m2/repository/com/glassstorm/phonemanager/contract/1.0.0/`. This is
Maven-local only — no remote repository, no signing, no credentials. The
consumption and regeneration commands are in
[`docs/protocol.md`](docs/protocol.md) section 11.

### Test

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :contract:test :core:model:test :core:domain:test :core:service:test :transport:grpc:test :adapter:jvm:test :adapter:android:testDebugUnitTest :app:testDebugUnitTest
```

### End-to-end

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew e2e
```

`e2e` is a thin alias for the two real-socket integration suites: the plain-JVM
harness in `:transport:grpc` and the Robolectric-hosted app hub in `:app`, both
driving the Go mockpeer as a child process. It depends on the same test tasks, so
it cannot diverge from them. On a warm build `e2e` reports `UP-TO-DATE`; add
`--rerun-tasks` to force it to actually execute.

The Go toolchain is a **hard** prerequisite: the real-socket suites fail (they do
not skip) when `go` is absent.

### Cross-repo E2E (the real Pi CLI)

A second, **opt-in** Python suite (`EcosysPiE2eTest`, T23) is the cross-repo E2E.
It drives the REAL sibling Pi repo's `ecosys_pi` CLI (the necklace) against the
Robolectric-hosted hub — whose `AiModule` binds the deterministic `:adapter:jvm`
fakes — over a loopback socket with NO token, while an in-process `ecosys.v1` peer
(the `:transport:grpc` test-fixture `HubPeerClient`) plays the glasses. It asserts
BOTH new loops:

- **gesture** — the necklace's JPEG `image` frames become a `gesture` frame on the
  glasses session AND a hub-synthesized `tts_audio` frame back on the necklace;
- **subtitle** — the glasses' `audio_pcm16_16k` frames become a `StreamResult` on
  the SAME session, with the EMPTY `speaker_label` for an unregistered speaker.

No device, camera, `nmcli`, model, or hotspot is involved: the Pi is pointed at
`127.0.0.1` with its synthetic JPEG source and a shimmed `aplay` that captures the
audio the necklace is told to play. It is excluded from `e2e` and `test`, and runs
only under `:app:ecosysPiE2e` (which is also attached to `check`), skipping
LOUDLY (a named `Assume` reason) only when the Pi repo, `uv`, or CPython 3.11+ is
absent; any other failure is a test failure.

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :app:ecosysPiE2e --rerun-tasks
```

Its two machine-specific defaults are overridable by environment variable:

- `PI_ECOSYS_REPO` — the sibling Pi repo root (default:
  `/home/chaos/workplace/fyp/necklace-data-processing-raspberrypi-side-`). It
  must contain `src/ecosys_pi/cli.py`.
- `UV_BIN` — the `uv` executable (default: `/home/chaos/.local/bin/uv`).

### Lint

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew lint
```

## Running the mockpeer

`tools/mockpeer` is the Go reference peer. It opens the stream UNAUTHENTICATED,
sends a `stream_open` bootstrap frame to bind its device id/role, and pushes
synthetic media so you can exercise the hub without a device or an emulator.

```bash
go run ./tools/mockpeer --addr 127.0.0.1:50051
```

Build it from the REPOSITORY ROOT. The root has a `go.work` (the root is not a Go
module; the only Go code is `tools/mockpeer`), so from the root you must name the
package:

```bash
go build ./tools/mockpeer/...
go vet ./tools/mockpeer/...
```

NOT `go build ./...` from the root: with a `go.work` present the root has no
packages and `./...` fails with "directory prefix . does not contain modules
listed in go.work". From inside `tools/mockpeer`, plain `./...` is fine.

### Flags

| Flag         | Default           | Meaning                                            |
| ------------ | ----------------- | -------------------------------------------------- |
| `--addr`     | `127.0.0.1:50051` | Hub `host:port` to dial (plaintext).               |
| `--frames`   | `10`              | Synthetic media frames to send per enabled medium. |
| `--timeout`  | `30`              | Overall deadline in seconds.                       |
| `--mode`     | `both`            | Media to send: `audio` \| `video` \| `both`.       |
| `--scenario` | `full`            | `full` \| `stream`.                                |

There are no credential flags: the stream is unauthenticated, so the peer never
pairs, heartbeats, or sends an `authorization` header. It binds its identity by
sending a `stream_open` frame as the stream's first message.

### stdout contract

Automation keys on stdout, so the lines are frozen:

| Line                                    | Meaning                                                                                | Exit |
| --------------------------------------- | -------------------------------------------------------------------------------------- | ---- |
| `stream-open-ok`                        | The `stream_open` bootstrap frame was accepted.                                        | 0    |
| `session-ok frames=<N> transcripts=<M>` | The scenario completed. `N` counts frames SENT, `M` counts transcript frames RECEIVED. | 0    |
| `error: <detail>`                       | Anything else.                                                                         | 1    |

`--scenario full` prints `stream-open-ok` before the `session-ok` line.

One trap: `go run` normalises ANY non-zero child exit to 1, so assert `exit != 0`,
never a specific exit code, when driving the CLI through `go run`.

## The manual-tether dev flow

Manual tethering is the PRIMARY dev path on this project. Two reasons: OEMs block
programmatic `LocalOnlyHotspot`, and Wi-Fi Direct is unstable on the glasses. The
peers are trusted devices on one local network, so there is no PIN pairing and no
bearer token: during development you enable the phone's system hotspot by hand and
the peers dial the gateway directly.

1. Open Android Settings, enable the hotspot, and note the SSID/password.
2. Join the glasses (and the Pi) to that hotspot by hand. The Pi does this with
   `nmcli` from its config; the glasses show a manual-connect screen.
3. The peer finds the hub at the hotspot's **default gateway** — the phone is the
   AP, so the hub is at the gateway, typically `192.168.43.1` — on the hub's gRPC
   port. The Pi resolves the gateway with `ip route` / `nmcli`; the glasses read it
   from `ConnectivityManager`.
4. The hub still advertises `_ecosys._tcp` over DNS-SD, but the peers no longer
   depend on mDNS: the gateway is the normative discovery path.

Reaching the hub through the gateway is a normal outcome, not an error. Do not
treat it as a degraded mode or a failure to investigate.

## On-device smoke checklist (NON-BLOCKING)

**This checklist is NOT an acceptance gate.** KVM is accessible to this user
(group `kvm`), and the emulator tooling is installed: emulator 37.1.11, the
`phonemanager29` AVD (`system-images;android-29;google_apis;x86_64`), and
`libpulse0` (so it boots headless — add `-no-audio` if audio is not needed).
What is still missing is _automation_: there is no `src/androidTest` source set,
no Gradle Managed Devices configuration, and no `connectedAndroidTest` wiring, so
nothing below is exercised by an automated task. This is a deliberate v1 choice:
the host no longer blocks it, the design simply keeps on-device behaviour manual.
Consequently on-device behaviour must be verified by hand on real hardware. The
automated suite does NOT cover any of the items below; a green test run says
nothing about them. Do not present a passed suite as evidence that on-device
behaviour works.

- [ ] The app requests the runtime permissions it needs and the request flow is
      sane on a real device.
- [ ] The system hotspot can be started from the app, or the manual tether path
      is used and the dashboard shows the gateway address.
- [ ] The hub foreground service survives screen-off and backgrounding long
      enough to serve a peer.
- [ ] mDNS resolution of `_ecosys._tcp` works over the SoftAP, even though the
      peers now reach the hub through the gateway rather than mDNS.
- [ ] A peer opens the stream with no token and binds its identity with a
      `stream_open` frame.
- [ ] A necklace JPEG still becomes a gesture result on the glasses AND a
      synthesized-audio (`tts_audio`) frame back on the necklace.
- [ ] A glasses audio frame becomes a speaker-tagged transcript on the same
      session, with an empty speaker label for an unregistered speaker.

## Repo conventions

- **Hexagonal boundaries.** `:core:domain` holds ports only, no implementation.
  `:core:service` holds use-cases and has NO dependency edge to any adapter, so
  importing an adapter class from `:core:service` fails to compile. `:contract`
  is consumed by exactly one module, `:transport:grpc`, which maps the wire
  format to the domain's own DTOs; `ecosys.v1.*` never appears elsewhere. Only the
  `:app` composition root wires implementations to ports.
- **Dagger is the only DI.** Each port is bound to its implementation in a
  `@Module` and injected by CONSTRUCTOR; the `@Singleton @Component` graph is
  built once in `PhoneManagerApplication`. A missing or duplicated binding fails
  the BUILD, not a runtime lookup. There is no Hilt, Koin, Room, or service
  registry anywhere in this repo.
- **Naming.** Standard Kotlin convention. Files are PascalCase and match their
  primary class (`HubBringUp.kt`, `Context.kt`, `Device.kt`); functions,
  members, locals, and parameters are lowerCamelCase (`startHotspot`,
  `boundPort`, `ctx`, `fromContext`); constants are UPPER_SNAKE_CASE
  (`DEFAULT_HUB_PORT`, `CHANNEL_ID`, `AUDIO_SAMPLE_RATE_HZ`, `DISCOVERY_NAME`);
  types are PascalCase; package names are all-lowercase reverse-domain.

## Security posture

- There is no bearer token or PIN any more: `OpenStream` is unauthenticated and a
  peer binds its identity with the `stream_open` frame. `PairingService` stays in
  the `.proto` as a frozen, unserved stub.
- Voiceprint enrollment persists name→voiceprint rows in a hub-owned store;
  `phone_manager.db` is excluded from cloud backup and device transfer.
- The cleartext network-security config is **DEV-ONLY**. It exists for the
  plaintext LAN posture of v1; it is not a security boundary and it must be
  replaced by the documented TLS upgrade path before any production use. See
  `docs/protocol.md` section 8.
