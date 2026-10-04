# AGENTS.md - phone-manager

The always-loaded contract for this repository. The skills under
`.agents/skills/` hold the depth; this file is the digest you get for free.

## 1. Project

`phone-manager` is the phone-side hub of the `ecosys.v1` device ecosystem: an
Android phone that is both the access point AND the server. It runs a local-only
hotspot, advertises `_ecosys._tcp` over DNS-SD, pairs peers with a single-use
6-digit PIN, authenticates every later call with a bearer token, relays audio and
video between peers and a pluggable STT engine, and returns transcripts on the same
stream. The ecosystem has exactly three peers: the phone hub (THIS repo, in scope);
the smart-glasses app and the Ubuntu/Raspberry-Pi daemon, both OUT OF SCOPE here.
Those two consume this repo's frozen `:contract` artifact instead of forking it.

## 2. Golden rules (NON-NEGOTIABLE)

- Hexagonal boundaries: `ecosys.v1.*` may be imported ONLY by `:transport:grpc`
  (and `:contract`); only `:app` sees the use cases, the transport, and the adapters
  together. Enforced by Konsist, with no suppression escape hatch.
- Dagger is the only DI. Constructor injection everywhere; the `@Singleton
@Component` graph is built once in `PhoneManagerApplication`. A missing or
  duplicated binding fails the BUILD, not a runtime lookup.
- Standard Kotlin naming: PascalCase files matching their primary class,
  lowerCamelCase functions/members/locals/parameters, UPPER_SNAKE_CASE `const val`.
- The `ecosys.v1` contract is FROZEN: never renumber or reuse a field number or an
  enum value, never change a field's type or name. Additive changes only, and new
  fields must be optional.
- All docs, comments, and agent instructions are 100% English.
- Never hand-edit the vendored Lumo UI kit (`ui/foundation/**`, `ui/components/**`,
  `Theme.kt`, `Color.kt`, `Typography.kt`). Regenerate it through the plugin.
- Always use the mandatory Gradle preamble (section 4).

## 3. Side-project reference (canonical)

The ONLY place these URLs live. A peer repo does not need to clone this one: it
consumes `com.glassstorm.phonemanager:contract:1.0.0`. Both clone forms are listed
because agents may be in an environment restricted to one. Marker: (v) came from
`git remote -v`; (d) is derived from it. Branch shown is as of 2026-09-29.

| Project                                    | Purpose                                    | Local path                                                             | Branch                | HTTPS URL                                                                           | SSH URL                                                                         |
| ------------------------------------------ | ------------------------------------------ | ---------------------------------------------------------------------- | --------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Daemon / Raspberry Pi (`ecosys_pi`)        | Python peer, pairs with this hub over gRPC | `/home/chaos/workplace/fyp/necklace-data-processing-raspberrypi-side-` | `feat/ecosys-client`  | `https://github.com/Glass-Storm/necklace-data-processing-raspberrypi-side-.git` (v) | `git@github.com:Glass-Storm/necklace-data-processing-raspberrypi-side-.git` (d) |
| Glasses app (`SpeakerDiarazation-Gesture`) | Android subtitle and gesture demo          | `/home/chaos/workplace/fyp/SpeakerDiarazation-Gesture`                 | `main`                | `https://github.com/Glass-Storm/SpeakerDiarazation-Gesture.git` (d)                 | `git@github.com:Glass-Storm/SpeakerDiarazation-Gesture.git` (v)                 |
| This hub (`phone-manager`)                 | The `ecosys.v1` hub                        | `/home/chaos/workplace/fyp/phone-manager`                              | `feat/pi-ecosys-peer` | `https://github.com/Glass-Storm/phone-manager.git` (d)                              | `git@github.com:Glass-Storm/phone-manager.git` (v)                              |

## 4. Command cheat-sheet

Mandatory preamble, required on EVERY Gradle command (the agent shell is
non-interactive: no `ANDROID_HOME`, and `JAVA_HOME` points at Java 25 which Gradle
8.13 cannot run):

    JAVA_HOME=/home/chaos/.jdk/jdk-21.0.12.1+1 ANDROID_HOME=/home/chaos/Android/Sdk ./gradlew <task>

- Build: `:app:assembleDebug`, `:app:assembleRelease` (release runs R8 and is UNSIGNED).
- Publish the contract: `:contract:publishToMavenLocal`.
- Test (module list): `:contract:test :core:model:test :core:domain:test
:core:service:test :transport:grpc:test :adapter:jvm:test
:adapter:android:testDebugUnitTest :app:testDebugUnitTest`.
- End-to-end: `./gradlew e2e` (an alias over the real-socket suites; add
  `--rerun-tasks` to force execution when it reports `UP-TO-DATE`).
- Lint, AGP: `./gradlew lint`. Lint, Kotlin style and naming: `./gradlew ktlintCheck`.
- Go (from the repo ROOT, name the package): `go build ./tools/mockpeer/...`,
  `go vet ./tools/mockpeer/...`, `go test ./tools/mockpeer/...`. Never bare
  `go build ./...` from the root (the root is not a Go module).

Details and citations: `.agents/skills/project-conventions/SKILL.md`.

## 5. Skill routing

Load the skill that matches the task before you act.

| Task                                                               | Load                                    |
| ------------------------------------------------------------------ | --------------------------------------- |
| Build, language, module boundaries, DI, commands                   | `project-conventions`                   |
| Naming a file, type, function, or constant; ktlint naming failures | `kotlin-naming-conventions`             |
| Compose, screens, components, theme, UI tests                      | `lumo-compose-ui`                       |
| UI/UX design principles, accessibility, review criteria            | `lumo` (`.agents/skills/lumo/SKILL.md`) |
| Wire contract, pairing, auth, streams, peers, mockpeer             | `ecosys-protocol`                       |

## 6. Closing

Depth lives in `.agents/skills/`. This file is the always-loaded digest.
