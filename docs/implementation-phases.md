# Quietype Implementation Phases

## Phase 0: Validation

Completed in docs: competitor review, platform constraints, and positioning.

## Phase 1: Keyboard MVP

Implemented:

- Native Android Kotlin project.
- `InputMethodService` keyboard.
- Toolbar actions for Rewrite, Clipboard, Templates, and Settings.
- Text commit, backspace, space, and enter keys.
- Familiar Android keyboard layout with suggestion strip, shift, symbol mode, comma, period, and wide spacebar.
- Emoji panel and unique Emoji Mix panel for combining emojis, symbols, kaomoji, and decorations.
- Rewrite target capture from selected text or current sentence.

## Phase 2: Rewrite Modes

Implemented foundation:

- `GRAMMAR_ONLY`, `NATURAL_CASUAL`, and `PROFESSIONAL` modes.
- Mode cycling from keyboard toolbar.
- Local heuristic rewrite engine for privacy-safe development builds.

Next adapter:

- Replace or augment `LocalHeuristicRewriteEngine` with ML Kit GenAI/Gemini Nano when the Android SDK dependency is added.

## Phase 3: AI And Privacy

Implemented foundation:

- Settings for on-device only, ask before cloud, and disabled cloud fallback.
- Cloud rewrite adapter with `/rewrite` endpoint.
- Cloud fallback blocked until user consent is saved.
- Local Node backend that enforces active session tokens and avoids request-body logging.

## Phase 4: Subscription And Offline Lock

Implemented foundation:

- Entitlement model with `active`, `expired`, `grace`, `lastVerifiedAt`, and `offlineExpiresAt`.
- Paid feature guard for rewrite, clipboard, and templates.
- 24-hour offline expiry rule.
- Backend adapters for entitlement verification and refresh.
- Local backend accepts `test_active*` purchase tokens for development and returns a 24-hour offline entitlement window.

Next adapter:

- Add Google Play Billing Library purchase flow and send purchase token to `/entitlements/verify`.

## Phase 5: Power Clipboard

Implemented foundation:

- Encrypted local clip/template storage through Android Keystore AES-GCM.
- Saved clips, pinned clips, search, and templates.
- Edit/delete clips and delete templates from the keyboard panel.
- Emoji mixes can be pasted immediately or saved as pinned clips.
- Paste from keyboard panel.
- Retention cap of 200 unpinned clips or 30 days.

## Phase 6: Beta Testing

Pending until Android SDK/emulator or physical device is available.

Required apps:

- WhatsApp
- Gmail
- LinkedIn
- Chrome
- Google Messages
- ChatGPT
- Instagram or Threads text fields

## Phase 7: Floating Hover Bubble V2

Implemented safe version:

- Optional floating assistant starts from settings after overlay permission.
- Bubble can collapse/expand, drag, open app settings, open keyboard settings, hide, or stop.
- It does not read or replace text from other apps.

Deferred:

- Accessibility-based text reading/replacement remains out of scope until Play policy review and real-device validation.
