# Translation UI preview

On `feat/ui`, MainActivity opens a UI-only prototype based on the original INMO
Link layout: a fixed dark palette, compact connection and action rows, a large
scrollable transcript, and a fixed reply composer. No action starts network,
camera, microphone, speech recognition or TTS services. All connections and
sample output are explicitly labelled as simulated.

## Android Studio

Open the repository on `feat/ui`, use Gradle JDK 21 and Android SDK 36, sync, then
run the `app` debug configuration. The pinned ai-android checkout is required at
`../../ai-android`; see the README for its pinned commit and AAR download script.
Initialize its submodules with `git submodule update --init --recursive`. Install
NDK 27.0.12077973 and use local.properties for your local SDK path.

## UI flow

1. Select phone or glasses once. This choice is saved locally; settings contains
   the role switch instead of keeping two role tabs visible during conversations.
2. Simulate a connection, then start the conversation. Start remains disabled
   until connected. Each fresh process requires a new simulated connection.
3. The sample action appends speech and sign replies. The composer appends a
   local text reply; it does not transmit it to a peer. Labels identify speaker,
   source and sequence. Text can be selected and copied.
4. Earlier sentences remain in the scrollable transcript. Reading older messages
   does not force-scroll to the latest entry. Closing the conversation or
   disconnecting retains the history, including through activity recreation;
   clearing history requires confirmation. Changing role clears the session.
5. Camera preview is a collapsible, labelled placeholder, hidden by default to
   leave more room for the transcript. It never claims to display a camera feed.
6. Settings controls subtitle size. Landscape uses the same fixed composer and
   scrollable history layout, without a permanent role-switching bar.

Validation includes assembleDebug, app ktlintCheck, conversation gate and role
switch tests, history retention, and portrait/landscape layout checks. Local
Robolectric render outputs are generated under app/build/ui-review.
