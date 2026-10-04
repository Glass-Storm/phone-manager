---
name: project-conventions
description: "Project conventions for the phone-manager Android hub: required languages (Kotlin for every Gradle module and build-logic, Go only in tools/mockpeer, Protobuf for the frozen wire contract), the mandatory Gradle JDK and Android SDK preamble, the ten Gradle modules, the Konsist hexagonal boundary laws, Dagger-only dependency injection, build, publish, test, e2e, and lint commands including ktlintCheck, the Go workspace rules, Conventional Commits, workflow discipline, and the security posture. Use before writing or changing any code in this repo, when running any Gradle or Go command, when adding a module, port, adapter, or dependency-injection binding, or whenever you need the project's build and test workflow."
---

# Project conventions (phone-manager)

The build, language, architecture, dependency-injection, and workflow guide for the
`phone-manager` Android hub. Read it before touching code. It points at the
repository's own files instead of restating them, and every claim below carries a
`path:line` citation.

## (a) Required languages

- **Kotlin** is the language of every Gradle module and of the `build-logic`
  composite build. All ten modules declared in `settings.gradle.kts:21-63` are
  Kotlin, and the `phonemanager.*` convention plugins come from the composite
  build included at `settings.gradle.kts:8`.
- **Go** appears in exactly one place: `tools/mockpeer`, the reference peer. The
  workspace lists only that module (`go.work:9`) and its module path is
  `phone-manager/tools/mockpeer` (`tools/mockpeer/go.mod:1-3`).
- **Protobuf** defines the wire contract in a single file,
  `contract/src/main/proto/ecosys/v1/ecosys.proto` (see the package and syntax
  at `contract/src/main/proto/ecosys/v1/ecosys.proto:16-18`).
- **There is no Python in this repository.** The Python `ecosys_pi` CLI lives in
  the sibling Raspberry Pi repo. The only trace here is the test-side child-process
  launcher, which points at that sibling checkout
  (`app/src/test/java/com/glassstorm/phonemanager/EcosysPiCli.kt:55-56`).

## (b) The mandatory Gradle preamble

Every Gradle invocation needs an explicit JDK and Android SDK. The build-agent
shell is non-interactive: it has no `ANDROID_HOME` and it points `JAVA_HOME` at
Java 25, on which Gradle 8.13 cannot run. Set both inline
(`README.md:35-44`):

```bash
JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew TASK
```

`TASK` is any Gradle task. Both variables are required:

- `JAVA_HOME` must point at JDK 21, the Temurin 21.0.12.1+1 toolchain; Gradle 8.13
  refuses to start on Java 25 (`README.md:48-49`).
- `ANDROID_HOME` must point at the SDK, or AGP cannot find `compileSdk 36` /
  `build-tools 36.0.0` and configuration fails (`README.md:50-51`).

This path is machine-specific on purpose. CI sets its own SDK and JDK and must NOT
copy this string (`README.md:53-55`). `local.properties` is a gitignored
alternative and must never be committed (`README.md:56-63`).

## (c) Module map

There are ten Gradle modules plus the `:build-logic` composite build and the Go
`tools/mockpeer` tool. Do not memorize a copied table; read the source of truth:

- `settings.gradle.kts:21-63` declares every module and documents what each owns.
- `README.md:16-33` gives the module table (kind and ownership per module).

`tools/mockpeer` is NOT a Gradle module and `:build-logic` is not a published
module (`README.md:30-33`). The cycle-breaking detail worth knowing: `:adapter:jvm`
is Android-free and never depends on `:adapter:android`, and only `:transport:grpc`
may depend on `:contract` (`settings.gradle.kts:22-49`).

## (d) Hexagonal boundary laws

The boundary laws are enforced by Konsist in the `:testing:architecture` module,
which parses the other modules' sources from disk. They fail the build, and the
suite is unconditional: no baseline, no `@Suppress`, no Gradle escape hatch
(`ArchitectureTest.kt:8-19`). The seven laws (a) through (g):

- **(a) Contract isolation.** `:contract` may be imported only by `:transport:grpc`
  and by `:contract`'s own sources (`ArchitectureTest.kt:24-33`).
- **(b) Wire-format confinement.** `ecosys.v1.*` may be imported only by
  `:transport:grpc` and `:contract`; it must never reach the domain, the use
  cases, or an adapter (`ArchitectureTest.kt:38-48`).
- **(c) Use-cases stay clean.** `:core:service` imports no `io.grpc.*`, no
  `ecosys.v1.*`, and no `com.glassstorm.phonemanager.adapter.*`; it speaks DTOs
  only (`ArchitectureTest.kt:53-64`).
- **(d) Pure domain.** `:core:domain` imports no DI framework
  (`dagger.*` / `javax.inject.*`), declares no framework annotation, and keeps its
  port packages interface-only (`ArchitectureTest.kt:69-116`).
- **(e) Adapters are leaves.** No adapter may import the `app` module
  (`ArchitectureTest.kt:121-129`).
- **(f) App is a top.** Nothing outside `app` may import `app`
  (`ArchitectureTest.kt:134-142`).
- **(g) Single composition root.** Only `app` may import the use cases, the gRPC
  transport, and the adapters together (`ArchitectureTest.kt:147-157`).

A separate guard fails if any source silences a rule via a Konsist suppression
(`ArchitectureTest.kt:162-172`). The package vocabulary and the permitted-importers
table live in `ModuleBoundaries.kt:5-13`, `ModuleBoundaries.kt:41-51`, and
`ModuleBoundaries.kt:62-74`.

## (e) Dagger-only dependency injection

Dagger is the only DI in this repo. Each port is bound to its implementation in a
`@Module` and injected by CONSTRUCTOR; the `@Singleton @Component` graph is built
once in `PhoneManagerApplication`. A missing or duplicated binding fails the BUILD,
not a runtime lookup. There is no Hilt, Koin, Room, or service registry anywhere
(`README.md:228-232`).

## (f) Commands

All Gradle commands below are prefixed with the preamble from (b).

- **Build.** `./gradlew :app:assembleDebug` and `./gradlew :app:assembleRelease`.
  The release build runs R8 (minify plus resource shrink) and is UNSIGNED by design
  (`README.md:68-78`).
- **Publish the contract.** `./gradlew :contract:publishToMavenLocal`, coordinates
  `com.glassstorm.phonemanager:contract:1.0.0`, Maven-local only
  (`README.md:80-94`).
- **Test (module list).** `./gradlew :contract:test :core:model:test
:core:domain:test :core:service:test :transport:grpc:test :adapter:jvm:test
:adapter:android:testDebugUnitTest :app:testDebugUnitTest` (`README.md:96-100`).
- **End-to-end.** `./gradlew e2e`, a thin alias that depends on
  `:transport:grpc:test` and `:app:testDebugUnitTest` (`README.md:102-112`,
  `build.gradle.kts:32-35`). On a warm build it reports `UP-TO-DATE`; add
  `--rerun-tasks` to force execution (`README.md:110-112`).
- **Lint, AGP.** `./gradlew lint` is the Android Gradle Plugin lint gate
  (`README.md:114-118`). CI scopes it to `:app`, the only Android application
  surface (`ci.yml:110-115`).
- **Lint, Kotlin style.** `./gradlew ktlintCheck` is the Kotlin style and naming
  gate, separate from AGP lint. CI runs `ktlintCheck --stacktrace` in the core job
  (`ci.yml:50-53`); the plugin is declared at `build.gradle.kts:9-13` and its rules
  are configured in `.editorconfig:1-39`.
- **Go.** `go build ./tools/mockpeer/...`, `go vet ./tools/mockpeer/...`, and
  `go test ./tools/mockpeer/...` (`README.md:133-136`, `ci.yml:162-171`).

## (g) Go workspace rules

Run the Go commands from the REPOSITORY ROOT and name the package explicitly. The
root is not a Go module: the only Go code is `tools/mockpeer`, and `go.work` lists
just that one module (`go.work:1-9`). From the root you must write
`go build ./tools/mockpeer/...` (`README.md:133-136`).

Do NOT run bare `go build ./...` from the root. With a `go.work` present the root
has no packages, so `./...` fails with "directory prefix . does not contain modules
listed in go.work" (`README.md:138-140`). Inside `tools/mockpeer`, plain `./...` is
fine (`README.md:140`).

## (h) Commit style: Conventional Commits (OBSERVED)

There is no CONTRIBUTING file in this repo, so this is OBSERVED from `git log`, not
from a written policy. Commit subjects follow the Conventional Commits shape: a
type, an optional parenthesized scope, a colon, then a lower-case summary, for
example `refactor(core): extract core domain ports and state machines` and
`fix(app): route hub teardown through the foreground service`.

The types observed in the history are `feat`, `fix`, `refactor`, `chore`, `docs`,
`test`, and `ci`. Keep the summary imperative and lower-case, and put the affected
module or area in the scope.

## (i) Workflow

Treat an implementation and its test as ONE unit of work. Every module ships its
tests beside it, and CI runs the code and its tests in the same invocation
(`ci.yml:52-53`, `ci.yml:115`). The `e2e` alias is deliberately a thin dependency
over the same test tasks so it can never diverge from them (`build.gradle.kts:28-35`).

Keep the evidence for a change under `.omo/evidence/`. CI itself uploads a file
from that directory as a machine-checkable artifact (`ci.yml:217-229`).

## (j) On-device smoke checklist is NON-BLOCKING

The on-device smoke checklist is NOT an acceptance gate and is NOT covered by the
automated suite (`README.md:197-206`). The host cannot allocate the emulator's
userdata partition and no device automation is present, so on-device behaviour must
be verified by hand on real hardware (`README.md:199-206`).

A green test run says nothing about the checklist items: permissions on a real
device, hotspot start, foreground-service survival, mDNS over the SoftAP, a real
peer pairing, real audio and video plus transcripts, and revoked-token rejection
(`README.md:208-218`). Never present a passing suite as on-device proof.

## (k) Security posture

- Only the token HASH is persisted. The plaintext bearer token is minted, returned
  once, and never stored (`README.md:242-243`).
- The Speechmatics API key lives in app-private SharedPreferences
  (`runtime_config`), and that prefs file is excluded from cloud backup and device
  transfer, together with `phone_manager.db`, which holds the token hashes
  (`README.md:244-246`). The key is masked by default in the UI: the field is
  rendered through a password transformation until the user reveals it explicitly
  (`app/src/main/java/com/glassstorm/phonemanager/ui/SettingsUiState.kt:10-13`).
- The cleartext network-security config is DEV-ONLY. It exists for the plaintext
  LAN posture of v1, it is not a security boundary, and it must be replaced by the
  documented TLS upgrade path before any production use (`README.md:247-250`).

## Related skills

Naming detail is in `.agents/skills/kotlin-naming-conventions/SKILL.md`; Compose and
Lumo UI conventions are in `.agents/skills/lumo-compose-ui/SKILL.md`; the wire
contract, pairing, and relay rules are in
`.agents/skills/ecosys-protocol/SKILL.md`. Side-project repository URLs live in the
root `AGENTS.md` reference table.
