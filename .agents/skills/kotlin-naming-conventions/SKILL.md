---
name: kotlin-naming-conventions
description: "Enforces the phone-manager Kotlin naming law: PascalCase files that match their primary class, lowerCamelCase functions/members/locals/parameters, UPPER_SNAKE_CASE const vals, and all-lowercase reverse-domain packages. Use when naming or reviewing Kotlin files, types, functions, properties, or constants; when a ktlintCheck naming failure appears; when touching the vendored Lumo UI kit under app/.../ui/; or when someone asks about the superseded Go-prefix and snake_case convention."
---

# Kotlin Naming Conventions

The naming law for every Kotlin source file in this repository, plus the ktlint
rules that enforce it and the one place where enforcement is deliberately OFF.

## When to Use

Reach for this skill when you are about to:

- name a new Kotlin file, class, function, property, parameter, or constant;
- fix a ktlint failure whose rule id mentions `filename`, `class-naming`, or
  `function-naming`;
- inspect or edit anything under `app/.../ui/` (the vendored Lumo UI kit);
- answer a question about the old `Go`-prefixed identifiers or `snake_case` file
  names, which are **superseded** (see the last section).

## The naming law

This is standard Kotlin style. The repo states it in `README.md:233-238` and
encodes it in `.editorconfig:1-39`. There is no local dialect and no `Go`
influence.

| Element                              | Rule                                             | Real examples (file:line)                                                                                                                                                                                                                                               |
| ------------------------------------ | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Files                                | PascalCase, matching the primary class they hold | `core/domain/src/main/kotlin/com/glassstorm/phonemanager/core/domain/service/PairingService.kt` (the `PairingService` interface), `app/src/main/java/com/glassstorm/phonemanager/ui/PairingScreen.kt` (the `PairingScreen` composable)                                  |
| Functions, methods                   | lowerCamelCase                                   | `startHotspot()` at `adapter/android/.../wifi/LocalOnlyHotspotAdapter.kt:42`; `boundPort()` at `transport/grpc/.../GrpcHubServer.kt:45` and `:56` of `HubServerAdapter.kt`; `openWindow()` on `PairingService.kt:21`                                                    |
| Members, properties                  | lowerCamelCase                                   | `boundPort` (`GrpcHubServer.kt:45`), `ttlMs` and `pin` (`PairingService.kt:21-33`)                                                                                                                                                                                      |
| Locals, parameters                   | lowerCamelCase                                   | `ctx`, `fromContext` as documented in `README.md:233-238`                                                                                                                                                                                                               |
| `const val` constants                | UPPER_SNAKE_CASE                                 | `DEFAULT_HUB_PORT = 9000` (`app/.../HubForegroundService.kt:79`); `CHANNEL_ID = "hub-foreground"` (`app/.../HubNotifier.kt:15`); `REASON_NO_WINDOW = "no-window"` (`core/model/.../PairOutcome.kt:25`); `MAX_PIN_ATTEMPTS = 5` (`core/domain/.../PairingService.kt:54`) |
| Types (classes, interfaces, objects) | PascalCase                                       | `PairingService`, `HubForegroundService`, `HubNotifier`, `PairOutcome`, `LocalOnlyHotspotAdapter`                                                                                                                                                                       |
| Packages                             | all-lowercase reverse-domain                     | `com.glassstorm.phonemanager...` (e.g. `com.glassstorm.phonemanager.core.domain.service`)                                                                                                                                                                               |

Two consequences worth stating plainly:

- A file rename is a convention change, not a cosmetic one. When you rename a
  file to match its primary class, the class name is the source of truth.
- A value that is `const val` is UPPER_SNAKE_CASE. A `val` that is merely
  immutable stays lowerCamelCase unless it is an actual compile-time constant.

## ktlint enforcement

The rules are real, they run on the build, and they fail it. There is no
baseline file and no disabled rule.

- `.editorconfig:1-39` says it directly: the Kotlin naming convention IS the
  ktlint default, so the standard naming rules run unmodified and fail the
  build on violation. "Nothing is disabled."
- Every code-correctness rule is also enabled: indentation, unused and wildcard
  imports, import ordering, final newline, blank lines, spacing, wrapping
  (`.editorconfig:9-11`).
- The rule ids are the ktlint 1.5.0 standard-rule ids, the version that
  ktlint-gradle 14.2.0 pulls (`.editorconfig:13-16`).

**The single carve-out** (`.editorconfig:29-39`):

```
ktlint_function_naming_ignore_when_annotated_with = Composable
```

Compose names a UI-emitting function in PascalCase (it behaves like a factory),
the opposite of the camelCase a plain function takes. ktlint's own guidance is to
exempt `@Composable` functions from function-naming rather than lowercase them.
The rule stays ENABLED: it still enforces camelCase on every other function and
only skips functions annotated `@Composable`. You can see the exemption in
action in `app/src/main/java/com/glassstorm/phonemanager/ui/PairingScreen.kt`
(`@Composable` at lines 30, 59, 95).

### Checking your work

```
./gradlew ktlintCheck
```

`ktlintCheck` is the naming gate. It is wired into CI
(`.github/workflows/ci.yml:53`, run alongside the module test tasks). If a
naming rule is violated, the build fails and names the rule.

## The vendored UI kit exclusion (CRITICAL)

`ktlintCheck` does **not** cover everything. `KtlintConventionPlugin.kt:45-51`
excludes a fixed set of UI paths from every ktlint module:

```
**/ui/Theme.kt
**/ui/Color.kt
**/ui/Typography.kt
**/ui/components/**
**/ui/foundation/**
```

These files are the vendored Lumo UI kit: generated third-party code produced
from the design system. `KtlintConventionPlugin.kt:26-28` spells out the reason:
reformatting them would fork them from their generator. So:

- Their conventions are **NOT mechanically enforced**. A green `ktlintCheck`
  says nothing about the contents of `Theme.kt`, `Color.kt`, `Typography.kt`,
  `ui/components/**`, or `ui/foundation/**`.
- **Do not hand-reformat them.** If one of those files looks off-style, leave
  it. Fix the generator or regenerate; do not edit by hand, because the next
  regeneration overwrites you.
- Hand-written UI stays **IN scope**: screens (`*Screen.kt`), `*ViewModel.kt`,
  and `*UiState.kt` files are authored code, so naming rules apply to them
  exactly as they do everywhere else (`KtlintConventionPlugin.kt:27-28`).

The plugin also excludes generated protobuf and gRPC sources under a module's
`build/generated/` directory, matched by the absolute path fragment
`/build/generated/` (`KtlintConventionPlugin.kt:21-25,42-44`). That is machine
output, not authored code. It does not change the naming law for anything you
write.

## The superseded Go-prefix / snake_case decision

An early decision (documented as "Naming decision #5") claimed the repo should
use PascalCase variables, single-word folder names, `snake_case` files, and
single-word DTO files under `domain/dto/`. That decision is **SUPERSEDED and was
a mis-reading**: the phrase "reference Go's naming style" meant "use a
consistent, idiomatic style", not "spell Kotlin identifiers with a literal `Go`
prefix" (`GoStart`, `GoBoundPort`). There is no Go interop in the Kotlin sources
besides the separate `tools/mockpeer` program
(`.omo/notepads/phone-manager-bootstrap/decisions.md:24-47`).

The migration ran in T1-T4 of the modularization plan and landed at HEAD
`e490692`: files renamed to PascalCase, the `Go` prefix dropped for
lowerCamelCase members, every constant normalised to UPPER_SNAKE_CASE, then the
`.editorconfig` ktlint naming rules re-enabled with nothing disabled. The
normalization commits are:

- `dfdce89` refactor(naming): rename kotlin files to pascalcase convention
- `fe6b1ff` refactor(naming): drop go prefix and use lowercamelcase members
- `8061fae` refactor(naming): normalize constants to upper snake case
- `e490692` chore(build): enable ktlint naming rules after normalization
- `da4b2cc` refactor(naming): drop residual lowercase go prefixes

If you see a `go` segment in a lowercase name or a `snake_case.kt` file, it is a
residual bug, not a convention. Rename it to the Kotlin standard. Do not
reintroduce the old scheme.

## Quick checklist

- [ ] New file is PascalCase and named after its primary class.
- [ ] Functions, members, locals, and parameters are lowerCamelCase.
- [ ] Every `const val` is UPPER_SNAKE_CASE.
- [ ] Types are PascalCase; packages are lowercase reverse-domain.
- [ ] Only `@Composable` functions are exempt from function-naming.
- [ ] You did not hand-edit any file under the excluded UI-kit paths.
- [ ] `./gradlew ktlintCheck` passes.
