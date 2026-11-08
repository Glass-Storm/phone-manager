# Translation UI preview

On branch `feat/ui`, MainActivity opens a UI-only prototype. Existing hub screens,
services and AI adapters remain in the project. Neither role selection nor the
simulated connection starts network or recognition services.

## Android Studio

Open this repository and select branch `feat/ui`. Use Gradle JDK 21, install
Android SDK 36, and sync Gradle. Run the `app` debug configuration on an emulator
or Android device. The existing pinned ai-android composite checkout is still
required at `../../ai-android`; follow the repository README to provision it.
Use your own SDK path; cloud-specific toolchain paths are not committed.

1. Choose phone or glasses on first launch. The choice survives app restarts.
2. On the connection page, Start conversation is disabled until Simulate
   connection is selected. All simulated states are labelled in the UI.
3. Start a conversation and reveal sample speech and sign replies. Pause/resume
   affects revealing samples; no microphone, camera, TTS or AI processing runs.
4. Settings contains role switching and subtitle size. Switching roles ends the
   simulated connection. Ending a conversation requires reconnecting.
5. Relaunching the app retains the role but requires a fresh connection.

Role buttons appear only during initial selection or when changing role in
settings. This APK is a UI preview, not a glasses transport implementation.

Validation: assembleDebug, app ktlintCheck, and TranslationFlowTest (Compose on
Robolectric) cover the connection gate, role switch and labelled sample output.
