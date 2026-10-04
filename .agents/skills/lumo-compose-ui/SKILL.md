---
name: lumo-compose-ui
description: "Documents the phone-manager Compose and Lumo design-system conventions: the plugin-generated UI kit, its layering and theme tokens, how to author new components and screens, the UI test rules, and how to reconcile the vendored kit's 44.dp touch target with the lumo design skill. Use when writing, changing, or reviewing anything under app/.../ui/, adding a Compose component or screen, styling through AppTheme, or asking whether the vendored 44.dp target is a bug."
---

# Lumo Compose UI

How this repository builds and styles Compose UI. It covers the generated design
system, the layering, the theme tokens, the rules for new components and screens,
the UI test contract, and the one place where the vendored kit deliberately
diverges from the design guideline.

## Two things called Lumo

This repo uses the name "Lumo" for two unrelated things, and confusing them
causes real mistakes:

- The **Gradle plugin** `com.nomanr.plugin.lumo` (version 1.2.5). It GENERATES the
  design system source under `app/src/main/java/com/glassstorm/phonemanager/ui`.
  This skill is about the code that plugin produces and how to work with it.
- The **design-intelligence skill** at `.agents/skills/lumo/SKILL.md`. It is a
  portable UI/UX rule set (WCAG, Fitts, Hick, Gestalt, Nielsen, platform drift).
  This skill REFERENCES it for principles; it never restates it.

## When to Use

Reach for this skill when you are about to:

- add, change, or review anything under `app/src/main/java/com/glassstorm/phonemanager/ui/`;
- add a Compose component or a new screen;
- pick a color, text style, spacing, or touch-target value;
- write or fix a Compose UI test under `app/src/test/java/com/glassstorm/phonemanager/ui/`;
- decide whether the vendored `44.dp` radio target is a bug (it is not).

## (a) The design system is generated, not hand-authored

The app's design system is produced by the Gradle plugin
`com.nomanr.plugin.lumo` version 1.2.5. The plugin reads its configuration from
`lumo.properties:1-9`:

- `ThemeName=AppTheme`
- `ComponentsDir=app/src/main/java/com/glassstorm/phonemanager/ui`
- `PackageName=com.glassstorm.phonemanager.ui`

From those values it generates the vendored UI kit:

- `ui/foundation/**`
- `ui/components/**`
- `ui/Theme.kt`
- `ui/Color.kt`
- `ui/Typography.kt`

This is generated third-party code. **DO NOT hand-edit or reformat it.**
Regenerate through the plugin instead. A hand edit is overwritten on the next
generation and, worse, forks the repo from its generator, so the next upstream
release cannot be merged cleanly.

`KtlintConventionPlugin.kt:45-51` confirms the intent. The shared ktlint
exclusions skip `/build/generated/` plus exactly those kit paths
(`**/ui/Theme.kt`, `**/ui/Color.kt`, `**/ui/Typography.kt`, `**/ui/components/**`,
`**/ui/foundation/**`). Read that correctly: the vendored kit is **excluded from
ktlint**, not enforced by it. Hand-written screens and `*ViewModel.kt` /
`*UiState.kt` files stay in ktlint's scope and ARE enforced, so keep those clean.

## (b) Layering: foundation, then components, then screens

Three tiers. Each tier may use the tier below it and never the tier above.

1. `ui/foundation` holds primitives: `Elevation`, `Ripple` (the `ripple()`
   indication factory), `ButtonElevation`, `Providers`, and
   `SystemBarsDefaultInsets`.
2. `ui/components` holds composites built on those primitives: `Button`,
   `Surface`, `Text`, `Scaffold`, `Divider` (`HorizontalDivider`), `RadioButton`,
   `card/` (`Card`), and `textfield/` (`OutlinedTextField`,
   `UnderlinedTextField`).
3. Screens are `ui/*Screen.kt` (Dashboard, Pairing, Stream, Devices, Settings).
   `ui/AppShell.kt` composes them into one `NavHost`.

A screen imports from `ui.components`; a component imports from
`ui.foundation`. The reverse never happens.

## (c) Theme tokens live behind `AppTheme`

`ui/Theme.kt:14-43` defines the `AppTheme` object. Its `colors` and `typography`
properties are `@ReadOnlyComposable` getters over `LocalColors` and
`LocalTypography`. The root `AppTheme(isDarkTheme, content)` composable provides
those locals, plus the ripple indication, the text-selection colors, the content
color, and the default text style.

- `ui/Color.kt:56-140`: the `@Immutable data class Colors` carries the semantic
  palette (`primary`/`onPrimary`, `secondary`, `tertiary`, `error`, `success`,
  `disabled`, `surface`, `background`, `outline`, `text`, `textSecondary`,
  `textDisabled`, `scrim`, `elevation`, and the rest). `LightColors` and
  `DarkColors` are the two instances. A new token goes into the data class AND
  both instances. Never inline a raw `Color(0x...)` in a screen.
- `ui/Typography.kt:15-139`: the `Typography` data class carries the type scale
  (`h1` through `h4`, `body1` through `body3`, `label1` through `label3`,
  `button`, `input`), with `defaultTypography`, `provideTypography()`, and
  `LocalTypography`.

Inside composition, read tokens as `AppTheme.colors.primary` or
`AppTheme.typography.h2`. Product code does not reach for `LocalColors` or
`LocalTypography` directly.

## (d) Authoring rules for new components

When you add a component (as opposed to editing the vendored kit), match the
kit's own conventions:

- A PascalCase `@Composable` function in a PascalCase file, under
  `ui/components/` (or `ui/foundation/` if it is a true primitive).
- A trailing `content: @Composable () -> Unit` slot, or a nullable `content`
  when the component can be content-free (see `Button`, which accepts a `text`
  string, a `content` slot, or both).
- A nested `object XDefaults` holding the component's tokens, for example
  `RadioButtonDefaults.MinimumInteractiveSize`, `RadioButtonDefaults.RadioButtonIconSize`,
  and `ButtonDefaults.contentPadding`. Do not scatter magic `dp` values.
- `@Immutable` or `@Stable` on state holders and on color/typography data classes
  (`RadioButtonColors` is `@Immutable`; `ripple()` is `@Stable`).
- `@NonRestartableComposable` on a `Surface`-like wrapper that only relays its
  content, so it adds no extra recomposition scope (`ui/components/Surface.kt:31-33,65-67`).
- One `@Preview` per component with representative states.
- The foundation `ripple()` for indication, never an ad-hoc Material ripple.
  `RadioButton` passes `ripple(bounded = false, radius = MinimumInteractiveSize / 2)`;
  `Surface` passes `ripple()` or `ripple(color = contentColor)`.
- Reuse semantic tokens and existing components. Do not restyle a one-off clone.

## (e) Screen pattern

Every screen follows the same shape:

```kotlin
@Composable
fun XScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
) {
    val viewModel: XViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()
    // ...
}
```

The rules behind it:

- `*UiState` is a `data class` with a default for every property, so a screen
  renders a zero state with no arguments (`ui/SettingsUiState.kt:15-27`).
- `*ViewModel` takes its domain ports as constructor `@Inject` dependencies,
  owns `private val state = MutableStateFlow(XUiState())`, and exposes
  `val uiState: StateFlow<XUiState> = state.asStateFlow()`.
- User actions are `onX()` functions. Reads that can fail use `runCatching` and
  degrade to an "unavailable" rendering instead of crashing. `SettingsViewModel.refresh()`
  reads every port inside `runCatching` and derives `configAvailable` from the
  results.
- Keep a `forTesting` companion seam when the ViewModel needs an injected clock
  or a second construction path. `DashboardViewModel.forTesting(...)` and
  `StreamViewModel.forTesting(...)` build the ViewModel with an explicit poll
  interval so a test cadence is deterministic; production uses the `@Inject`
  constructor. `PairingViewModel` does the same with a private constructor taking
  `nowMs` that the `@Inject` constructor delegates to.
- Routes are `const val ROUTE_*` in `ui/AppShell.kt:23-36`. Navigation uses
  `launchSingleTop` plus `popUpTo(startRoute) { saveState = true }` and
  `restoreState = true`, so repeated tab taps never stack duplicate destinations
  (`ui/AppShell.kt:140-150`).
- `AppShell(viewModelFactory, startRoute, navController)` owns the `NavHost` over
  the five routes and the bottom `NavigationBar`. A test passes its own
  `navController` so it can assert the back stack directly.

## (f) UI test rules

Compose UI tests run on the JVM under Robolectric; no emulator exists on this
host.

- Annotate with `@RunWith(RobolectricTestRunner::class)` and use
  `createComposeRule()`.
- Assert LITERAL strings, never the screen's own constants. Asserting against a
  shared constant is tautological: the test passes even when the UI renders the
  wrong text. `AppShellTest` asserts `"Dashboard"` and `"PAIRING"` as literals,
  not the `ROUTE_*` constants.
- One `setContent` per test. Build the content in a private helper, as
  `SettingsScreenTest.setSettingsContent(...)` does.
- Test doubles implement DOMAIN ports only. `TestDoubles.kt` binds each fake
  under the port type, so a screen that reached past an interface into a concrete
  `:adapter` module class would fail to compile. `:app` test sources must never
  import a concrete `:adapter` class (the `core.domain.adapter.*` imports are the
  DOMAIN port interfaces, not that module).
- `viewModelFactory(vararg models: ViewModel)` builds a
  `ViewModelProvider.Factory` over explicitly constructed ViewModels, so a
  failure-path test can inject a port that THROWS rather than an absent one (a
  missing port is impossible under compile-time DI).
- Drive screens through text selectors, with `performScrollTo()` before
  `assertIsDisplayed()` or `performClick()`, and assert the effect through the
  port's own getter, for example `config.sttEngine()`.

## (g) The lumo skill is the design guideline of record

`.agents/skills/lumo/SKILL.md` is the repo's UI/UX design guideline. It carries
the rule set and the reasoning: WCAG contrast, Fitts's law, Hick's law, Gestalt
grouping, Nielsen heuristics, Apple HIG vs Material parity, and Jetpack Compose
vs SwiftUI design-system drift.

Use it for the WHY of a design decision and for review criteria. Do not duplicate
its content here, and do not modify that file.

Its `lumo-*` CLIs (`lumo-wcag`, `lumo-source`, `lumo-render`, `lumo-audit`,
`lumo-parity`, `lumo-figma`) are **not installed** in this repository and are not
part of its toolchain. That skill's tooling is a separate Python package
(`tools/lumo/**`, exposed as console scripts only after an external install). None
of it ships here. Reference the skill for principles; never present those
commands as runnable in this repo.

## (h) The 44.dp touch target: do not "fix" it

The vendored kit sets `RadioButtonDefaults.MinimumInteractiveSize = 44.dp`
(`ui/components/RadioButton.kt:119`). The radio uses that value for its required
interactive size and for its unbounded ripple radius.

That DIVERGES from the referenced design guideline. `.agents/skills/lumo/SKILL.md:299-301`
flags `undersized_tap_target` when Compose uses `Modifier.size(N.dp)` with
`N < 48`, and `.agents/skills/lumo/SKILL.md:948-949` states the Android minimum
tap target is 48 x 48 dp (the iOS minimum is 44 x 44 pt).

The reconciliation, which is deliberate:

- The vendored kit is generated third-party code and is intentionally **exempt**
  from the lumo tap-target checks. Do not "fix" the 44.dp value, do not bump it
  to 48.dp, and do not file it as a bug. A hand change would be overwritten by
  the generator.
- The value is also outside ktlint's scope (`KtlintConventionPlugin.kt:45-51`),
  so leaving it alone is not a lint violation. The two exclusions are separate
  facts, and neither one is "the kit is ktlint-enforced".
- For NEW hand-authored components, follow the repo's existing `44.dp` token
  rather than introducing a second, conflicting minimum. Consistency with the
  shipped kit beats a solo 48.dp outlier.
- Screen tests assert behavior, not pixels, so the exact value never needs to
  appear in an assertion.

If the 48dp guideline should win, that is an upstream generator decision, not a
local edit.

## (i) Security UI rule: the API key is masked by default

`ui/SettingsUiState.kt:10-13` states the posture: `apiKeyDraft` holds the stored
key so it can be replaced, but the screen renders the field through a password
transformation while `apiKeyVisible` is `false`. Revealing the key takes an
explicit action, so the cleartext value is never on screen by default.

Apply the same shape to any future secret-bearing field: masked by default,
revealed only on an explicit user action, and never logged or echoed in UI text.
`SettingsScreenTest` enforces this. It counts `SemanticsProperties.Password`
nodes and searches the semantics tree for a raw sentinel key, expecting zero
matches until the user clicks "Show API key".

## (j) See also

- `.agents/skills/lumo/SKILL.md`: the design principles this skill leans on
  (WCAG, Fitts/Hick/Gestalt/Nielsen, Compose/SwiftUI drift). Read it for WHY;
  this file covers the repo's HOW.
- `project-conventions`: build commands, module boundaries, Dagger DI, and the
  repo-scope vendored-kit policy.
- `kotlin-naming-conventions`: the naming law that applies to `*Screen.kt`,
  `*ViewModel.kt`, and `*UiState.kt` files and their members.

This skill does not restate those skills. Follow the pointer when you need their
content.
