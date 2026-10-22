# Product

## Register

product

## Users

The hub phone is held by the **Deaf / hard-of-hearing user who signs**. A hearing
conversation partner wears the smart glasses (and, in the necklace scenarios, the
Raspberry-Pi necklace). Neither user is a technician, and the phone is usually
propped or held during a live, face-to-face conversation — standing, moving, in
unpredictable light and noise.

Three scenarios run over this hub (from the 悅視眼鏡 v3.0 proposal):

1. **Necklace-side gesture** — the necklace camera sees the sign; the phone
   recognises it; the necklace speaks it and the glasses show the word.
2. **Speech → subtitle** — the glasses mic hears nearby speech; the phone runs
   on-device STT + speaker diarization; the glasses show text with a speaker tag.
3. **Glasses-side gesture** — the glasses camera sees the sign; the phone
   recognises it; text **and** audio go back to the glasses.

In every scenario the phone is the silent "brain" in the middle. The user's job on
the phone is to **start it, trust it, and see at a glance that it is working**.

## Product Purpose

`phone-manager` is the phone-side hub of the offline device ecosystem: a local-only
hotspot that discovers the two peers, runs on-device speech-to-text, speaker
diarization, sign/gesture recognition and speech synthesis, and relays results
between the necklace and the glasses. This app is the **control and status console**
for that hub — turn the system on, see who is connected, watch what is being
relayed, and configure the link.

Success: a user starts a session and can trust the current state in **under three
seconds**, without reading a single log line or protocol term. The intended bar is
"connect ≤ 3 s, playback < 500 ms, SUS ≥ 70" — the UI must never be the reason a
target is missed.

## Brand Personality

**Calm, clear, quietly capable.** A reassuring assistant, not an engineer's control
panel. Plain, warm, non-technical language ("已連線", "正在聆聽", "手語辨識中") —
never jargon ("emitters bound", "token", "channel"). Bright, airy, high-contrast,
generous spacing: the feel of a clean assistive/medical companion that stays out of
the way and simply works.

Voice: short sentences, present tense, no exclamation, no blame. Errors say what to
do next, not what went wrong internally.

## Anti-references

No hard taboos were given by the user. Held by principle:

- **Not** a dense grey admin console or a terminal log dump.
- **Not** a flashy marketing/gaming surface (no gradients-for-decoration, no
  ornamental motion).
- **Not** a wall of tiny tracked uppercase labels (the eybrow/kicker tell).
- **Not** colour-only status: every state must survive greyscale and colour
  blindness.

## Design Principles

1. **Status first.** The single most important fact — is the system working? — is
   the largest, highest-contrast thing on the screen.
2. **Plain language over protocol.** Say what happened ("手語已辨識"), never how.
3. **Calm by default, confident when live.** Idle is quiet and uncluttered; the
   live state is unmistakable — colour **plus** icon **plus** word, never colour
   alone.
4. **One vocabulary.** The same status chip, section header, info row, stat tile
   and button shape on every screen. Consistency over surprise.
5. **Honest degradation.** A failing port reads as a calm "無法使用" line, not a
   crash and not a silent blank.

## Accessibility & Inclusion

- **WCAG 2.1 AA** as the floor: body text ≥ 4.5:1, large text ≥ 3:1, non-text UI
  and focus indicators ≥ 3:1. The dark theme must also meet AA (not "dark because
  tools look cool").
- **Android touch targets ≥ 48 × 48 dp** (the vendored kit's 44 dp radio is a
  deliberate generated exemption; new hand-authored controls use ≥ 48 dp).
- **Never colour alone.** Status = icon + label + colour, so it survives
  colour-blindness and greyscale.
- **Scalable type** (`sp`, honouring the system text scale) and TalkBack
  `contentDescription` on every icon-only control.
- This is an assistive product for a hearing-impaired primary user: legibility and
  calm clarity outrank decoration, always.
