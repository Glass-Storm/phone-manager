# phone-manager

The phone-side hub of the `ecosys.v1` device ecosystem. It turns an Android phone
into the access point AND the server for two peers: a smart-glasses app and an
Ubuntu daemon. The hub runs a local-only hotspot, advertises itself over DNS-SD,
pairs peers with a single-use PIN, authenticates every subsequent call with a
bearer token, relays audio and video between peers and a pluggable speech-to-text
engine, and returns transcripts on the same stream.

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
| `:core:service`    | pure Kotlin/JVM     | The use-case implementations (device, pairing, relay). Contract-free and gRPC-free by construction.                                                                                                                     |
| `:transport:grpc`  | pure Kotlin/JVM     | The gRPC service implementations, the bearer-token interceptor, the netty-backed hub server, and the DTO↔proto mapping. The ONE module allowed to depend on `:contract`.                                                |
| `:contract`        | pure Kotlin/JVM     | The FROZEN `ecosys.v1` wire contract: the `.proto` source of truth and its protobuf/gRPC-lite codegen. The ONE artifact the out-of-scope peers consume.                                                                 |
| `:adapter:jvm`     | pure Kotlin/JVM     | Android-free port implementations: the STT engines and the discarding frame sink.                                                                                                                                       |
| `:adapter:android` | Android library     | Platform-backed adapters: SQLite, LocalOnlyHotspot, NSD discovery, and the runtime config store. The battery-exemption helper lives in `:app` (it is app-owned platform glue).                                          |
| `:testing:testkit` | pure Kotlin/JVM     | Test support: the bounded child-process runner, the deterministic media generators, and the in-memory repository double, shared by the `:transport:grpc` and `:app` test suites. Its only dependency is `:core:domain`. |

`tools/mockpeer` (the Go reference peer) is NOT a Gradle module; it stays at the
repo root because the root `go.work` and the Go module are root-relative.
`:build-logic` is a Gradle composite build providing the `phonemanager.*`
convention plugins, not a published module.

## The mandatory Gradle preamble

Every Gradle invocation in this repository needs an explicit JDK and Android SDK.
The shell used by build agents is non-interactive: it has no `ANDROID_HOME` and
it points `JAVA_HOME` at Java 25, on which Gradle 8.13 cannot run. So set both
inline:

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew <task>
```

Why both:

- **`JAVA_HOME`** must point at a JDK 21. Gradle 8.13 refuses to start on Java 25,
  and the toolchain here is Temurin 21.0.12.1+1.
- **`ANDROID_HOME`** must point at the SDK. Without it, AGP cannot find
  `compileSdk 36` / `build-tools 36.0.0` and configuration fails.

This path is machine-specific on purpose. CI uses its own SDK and JDK; the
workflow in `.github/workflows/ci.yml` sets its own environment and must not
copy this string. If you prefer to keep the SDK path out of the command line,
create `local.properties` at the repo root with:

```properties
sdk.dir=/home/chaos/Android/Sdk
```

Either mechanism works. `local.properties` is gitignored and must never be
committed; the inline `ANDROID_HOME` is the form the acceptance commands below
use.

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

### Lint

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew lint
```

## Running the mockpeer

`tools/mockpeer` is the Go reference peer. It pairs, heartbeats, and pushes
synthetic media so you can exercise the hub without a device or an emulator.

```bash
go run ./tools/mockpeer --addr 127.0.0.1:50051 --pin 123456
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

| Flag                       | Default           | Meaning                                                                                                                          |
| -------------------------- | ----------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `--addr`                   | `127.0.0.1:50051` | Hub `host:port` to dial (plaintext).                                                                                             |
| `--pin`                    | (empty)           | 6-digit pairing PIN for the open window.                                                                                         |
| `--token`                  | (empty)           | Bearer token; skips `Pair` when set.                                                                                             |
| `--token-file`             | (empty)           | Read the bearer token from this file instead of pairing.                                                                         |
| `--token-out`              | (empty)           | Write the issued token to this file (mode 0600), keeping it OUT of stdout.                                                       |
| `--no-token`               | `false`           | Skip `Pair` and heartbeat WITHOUT metadata; the hub must reject.                                                                 |
| `--expect-unauthenticated` | `false`           | Heartbeat with the token and REQUIRE an `UNAUTHENTICATED` refusal (the revoked-token leg). Requires `--token` or `--token-file`. |
| `--frames`                 | `10`              | Synthetic media frames to send per enabled medium.                                                                               |
| `--timeout`                | `30`              | Overall deadline in seconds.                                                                                                     |
| `--mode`                   | `both`            | Media to send: `audio` \| `video` \| `both`.                                                                                     |
| `--scenario`               | `full`            | `full` \| `pair` \| `stream`.                                                                                                    |

`--token`, `--token-file`, and `--no-token` are mutually exclusive.
`--scenario stream` requires a credential (or `--no-token` to prove rejection).

### stdout contract

Automation keys on stdout, so the lines are frozen:

| Line                                    | Meaning                                                                                | Exit |
| --------------------------------------- | -------------------------------------------------------------------------------------- | ---- |
| `session-ok frames=<N> transcripts=<M>` | The scenario completed. `N` counts frames SENT, `M` counts transcript frames RECEIVED. | 0    |
| `UNAUTHENTICATED`                       | The hub refused an unauthenticated call, as it must.                                   | 2    |
| `pair-rejected reason=<r>`              | The hub rejected the PIN with reason `<r>`.                                            | 2    |
| `error: <detail>`                       | Anything else.                                                                         | 1    |

`--scenario full` additionally prints `pair-ok` and `heartbeat-ok` before the
`session-ok` line, in that order.

One trap: `go run` normalises ANY non-zero child exit to 1, so a rejection that
exits 2 inside the binary shows up as `go run` exiting 1. Assert `exit != 0`,
never a specific rejection code, when driving the CLI through `go run`. The token
itself never reaches stdout; use `--token-out` when you need to reuse it.

## The manual-tether dev flow

Manual tethering is the PRIMARY dev path on this project. Two reasons: OEMs block
programmatic `LocalOnlyHotspot`, and Wi-Fi Direct is unstable on the glasses. So
during development you enable the phone's system hotspot by hand:

1. Open Android Settings, enable the hotspot, and note the SSID/password.
2. Join the glasses or the Ubuntu daemon to that hotspot.
3. The hub advertises `_ecosys._tcp`; peers that can resolve it use mDNS.
4. Peers that cannot resolve it use the direct gateway IP. The phone is the AP,
   so the hub is at the gateway, typically `192.168.43.1`. The discovery adapter
   falls back to the gateway on an mDNS miss, so this path works even when mDNS
   is filtered.

The direct-IP fallback is a normal outcome, not an error. Do not treat it as a
degraded mode or a failure to investigate.

## On-device smoke checklist (NON-BLOCKING)

**This checklist is NOT an acceptance gate.** This machine has no emulator (KVM
is inaccessible) and no device automation, so on-device behaviour must be
verified by hand on real hardware. The automated suite does NOT cover any of the
items below; a green test run says nothing about them. Do not present a passed
suite as evidence that on-device behaviour works.

- [ ] The app requests the runtime permissions it needs and the request flow is
      sane on a real device.
- [ ] The system hotspot can be started from the app, or the manual tether path
      is used and the dashboard shows the gateway address.
- [ ] The hub foreground service survives screen-off and backgrounding long
      enough to serve a peer.
- [ ] mDNS resolution of `_ecosys._tcp` works over the SoftAP (this is exactly
      the case the gateway fallback exists for).
- [ ] A real peer pairs with a PIN and holds an authenticated heartbeat.
- [ ] Audio and video actually arrive from a real peer and transcripts come back.
- [ ] A revoked token stops working immediately on the device.

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
  primary class (`PairingService.kt`, `Context.kt`, `Device.kt`); functions,
  members, locals, and parameters are lowerCamelCase (`startHotspot`,
  `boundPort`, `ctx`, `fromContext`); constants are UPPER_SNAKE_CASE
  (`DEFAULT_HUB_PORT`, `CHANNEL_ID`, `REASON_NO_WINDOW`, `MAX_PIN_ATTEMPTS`);
  types are PascalCase; package names are all-lowercase reverse-domain.

## Security posture

- Only the token HASH is persisted; the plaintext bearer token is minted, returned
  once, and never stored.
- The Speechmatics API key lives in app-private SharedPreferences
  (`runtime_config`), and that prefs file is excluded from cloud backup and
  device transfer, together with `phone_manager.db` (which holds token hashes).
- The cleartext network-security config is **DEV-ONLY**. It exists for the
  plaintext LAN posture of v1; it is not a security boundary and it must be
  replaced by the documented TLS upgrade path before any production use. See
  `docs/protocol.md` section 8.
