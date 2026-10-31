---
name: ecosys-protocol
description: "The FROZEN ecosys.v1 wire contract and its three peers (phone hub, smart-glasses app, Ubuntu daemon). Use when pairing, bearer-token auth, stream framing, relay backpressure, contract publishing, or the Go mockpeer stdout contract is in play."
---

# ecosys-protocol

The peer wire contract for this ecosystem. The `:contract` module is the source of
truth; `docs/protocol.md` explains the semantics around it. The contract is frozen,
so read this before you touch a field number, an enum value, or a service method.

## The three peers

The ecosystem has exactly three peers (`docs/protocol.md:1-30`):

| Peer              | Role value                     | Scope                                                                      |
| ----------------- | ------------------------------ | -------------------------------------------------------------------------- |
| Phone hub         | (none, it authenticates peers) | THIS repo. Hotspot + discovery + pairing/auth + A/V relay + pluggable STT. |
| Smart-glasses app | `DEVICE_ROLE_GLASS`            | OUT OF SCOPE. Streams audio and video, receives transcripts.               |
| Ubuntu daemon     | `DEVICE_ROLE_DAEMON`           | OUT OF SCOPE. Long-lived paired peer.                                      |

The phone is the hub: it is the access point AND the server. The other two peers
dial in to it. They never accept connections from it, and they never talk to each
other. The `:contract` `.proto` is the single shared file both out-of-scope peers
generate their clients from, so any change there is a change to their wire surface
(`docs/protocol.md:1-30`).

## The FROZEN contract rules

The rules are stated in the proto file's own header
(`contract/src/main/proto/ecosys/v1/ecosys.proto:8-11`) and repeated in
`docs/protocol.md:349-361`:

- never renumber or reuse a field number or enum value;
- never change an existing field's type or name;
- additive changes are allowed (new fields, messages, rpcs), and new fields must
  be optional.

Field numbers and enum values are permanent. If you retire something, mark it
`reserved` instead of handing its number to something else. A peer generated from
an older revision must keep working against a hub generated from a newer one, and
the reverse must hold too (`docs/protocol.md:358-361`).

## The `ecosys.v1` import boundary

`ecosys.v1.*` may be imported ONLY by `:transport:grpc` (the module that maps the
wire format to the domain's own DTOs) and by `:contract` itself. It must never
reach the domain, the use cases, or an adapter.

Konsist law (b) enforces this at test time, unconditionally and with no
suppression escape hatch:
`testing/architecture/src/test/kotlin/com/glassstorm/phonemanager/testing/architecture/ArchitectureTest.kt:37-48`.
The Gradle dependency edges already fail a wrong module dependency; the Konsist law
catches what Gradle cannot see, a source-level import in either the main or the
test source set.

## Auth

Only ONE method is reachable without a bearer token:
`ecosys.v1.PairingService/Pair`, and only while a pairing window is open
(`docs/protocol.md:139-246`).

- A pairing window carries a 6-digit PIN drawn from `SecureRandom` with a TTL. The
  app default is 120 seconds (`PairingViewModel.WINDOW_TTL_MS = 120_000L`).
- The PIN is single-use: a successful `Pair` burns it. A second attempt with the
  same PIN is rejected with `pin-consumed`. After 5 failed attempts
  (`PairingService.MAX_PIN_ATTEMPTS = 5`) the current PIN is locked, and only a
  fresh window clears the counter.
- The bearer token is returned exactly once, in `PairResponse.token`. Only its
  SHA-256 hash is persisted; the hub compares with `MessageDigest.isEqual` in
  constant time. The plaintext token is never stored.
- Every other RPC requires a strict `authorization: Bearer <token>` metadata
  header. Any failure closes the call with gRPC `UNAUTHENTICATED` before any
  service body runs. The description never distinguishes missing, unknown,
  tampered, or revoked, and never echoes the token or the PIN.
- Revocation is immediate: deleting the device row removes the token hash, so a
  previously valid token fails the next call.

A rejected PIN is a different channel. It arrives as a SUCCESSFUL RPC carrying
`ok=false` and a `reject_reason` (`no-window`, `pin-missing`, `name-missing`,
`pin-expired`, `pin-consumed`, `pin-locked`, `pin-invalid`), never as
`UNAUTHENTICATED` (`docs/protocol.md:165-211`).

## Stream framing, relay, and backpressure

`StreamService/OpenStream` is a bidirectional stream of `StreamFrame`. The peer
sends media frames; the hub emits `transcript` and `result` frames back on the
SAME stream (`docs/protocol.md:215-246`).

- Audio is raw little-endian PCM16, mono, 16 kHz. The canonical chunk is 20 ms
  (320 samples, 640 bytes). The hub never decodes it; it forwards it to the STT
  engine.
- Video is raw H.264 NAL units, opaque. The hub never decodes, re-encodes,
  transcodes, or renders video.
- Transcripts come back as `transcript` frames. In v1 the hub emits plain text and
  does not emit a `StreamResult` with a speaker label; diarization is out of scope.

Backpressure uses SEPARATE queues per media kind, because one queue cannot express
both policies:

| Media | Policy                                       | Observable effect                                             |
| ----- | -------------------------------------------- | ------------------------------------------------------------- |
| Audio | NEVER dropped. The producer PARKS when full. | The counter only grows for accepted frames; no audio is lost. |
| Video | Drops the OLDEST queued frame under load.    | The hub tracks a `videoDropped` counter.                      |

Session teardown drains rather than cancels: closing a session joins the pumps,
then closes the STT session exactly once. A peer cancel or a downstream failure
still tears the session down cleanly (`docs/protocol.md:243-245`).

## Publishing and consuming the contract

A peer does not need to clone this repo. `:contract` publishes itself, and the
coordinates are `com.glassstorm.phonemanager:contract:1.0.0` (`README.md:80-94`,
`docs/protocol.md:362-400`). Publish with:

```
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew :contract:publishToMavenLocal
```

Two JARs land for that coordinate: the main JAR carries the compiled
protobuf/gRPC-lite bindings, and a `proto`-classifier JAR carries the `.proto`
source of truth at `ecosys/v1/ecosys.proto`. A non-JVM peer that generates its own
client (Go, Swift, another language) needs the classifier JAR; extract it and point
`protoc` at the extracted root. A JVM peer depends on the main coordinate and gets
the bindings plus the grpc/protobuf-lite runtime from the published POM. Publishing
targets Maven local only: no remote repository, no signing, no credentials.

## The mockpeer stdout contract

`tools/mockpeer` is the Go reference peer, and automation keys on its stdout, so
the lines are frozen (`README.md:161-178`, `tools/mockpeer/main.go:10-13`):

| Line                                    | Meaning                                                        | Exit |
| --------------------------------------- | -------------------------------------------------------------- | ---- |
| `session-ok frames=<N> transcripts=<M>` | Scenario completed. `N` frames sent, `M` transcripts received. | 0    |
| `UNAUTHENTICATED`                       | The hub refused an unauthenticated call, as it must.           | 2    |
| `pair-rejected reason=<r>`              | The hub rejected the PIN with reason `<r>`.                    | 2    |
| `error: <detail>`                       | Anything else.                                                 | 1    |

One trap: `go run` normalises ANY non-zero child exit to 1, so a rejection that
exits 2 inside the binary shows up as `go run` exiting 1. When you drive the CLI
through `go run`, assert `exit != 0`, never a specific rejection code. The token
never reaches stdout; use `--token-out` when you need to reuse it.

Side-project repositories and their remote URLs (HTTPS and SSH) live in the root `AGENTS.md` reference table.
