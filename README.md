# HumanRewrite Keyboard

HumanRewrite is a privacy-first Android keyboard for grammar correction, human-sounding rewrite, and power clipboard workflows.

## What Is Implemented

- Native Android Kotlin project.
- Custom `InputMethodService` keyboard.
- Keyboard toolbar: Rewrite, mode switch, Clipboard, Templates, Settings.
- Familiar Android keyboard layout with suggestion strip, shift, symbols, backspace, enter, comma, period, and wide spacebar.
- Emoji panel and unique Emoji Mix composer for emojis, symbols, kaomoji, and reusable mixes.
- Rewrite modes: Grammar Only, Natural Casual, Professional.
- Lightweight local model selection: SmolLM2 360M, Qwen3 0.6B, or no local model.
- Selected-text rewrite first, current-sentence rewrite fallback.
- Subscription entitlement model with strict 24-hour offline expiry.
- Backend API adapters for entitlement verification and cloud rewrite.
- Local Node backend for entitlement verification and cloud rewrite fallback.
- Encrypted local clipboard/template storage with Android Keystore.
- Clipboard features: save, pin/unpin, search, paste, templates.
- Optional floating bubble (Android Accessibility service) that reads and replaces the focused text box, only when the user taps Rewrite. Password fields are never read.
- Product validation, backend contract, and phase docs under `docs/`.
- Android Studio/Codex development workflow documented under `docs/`.
- Play Billing, on-device AI, beta testing, and phase-completion docs under `docs/`.
- Lightweight open-model research and recommendation under `docs/lightweight-llm-options.md`.

## What Is Stubbed For The Next Integration Pass

- Google Play Billing purchase UI and subscription product ids.
- ML Kit GenAI / Gemini Nano / AICore implementation.
- Deploying the backend: it needs `GOOGLE_SERVICE_ACCOUNT_JSON` (Play Console service account) so `/entitlements/verify` can call the Play Developer API, and the app needs `backendUrl=https://...` in gradle.properties. Test tokens (`test_active*`) only work with `ALLOW_DEV_TOKENS=1` outside production.
- Pinning SHA-256 digests for the downloadable models (downloads are currently checked for size and GGUF header only).

## Local Build

This workspace includes a locally downloaded Android SDK under `.tools/android-sdk` and Gradle under `.tools/gradle-8.10.2`.

```powershell
$env:ANDROID_HOME = "$PWD\.tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\.tools\gradle-8.10.2\bin\gradle.bat assembleDebug
```

On this machine, Gradle currently fails before project compilation with:

```text
java.io.IOException: Unable to establish loopback connection
```

A direct Java pipe test showed the same failure on Microsoft JDK 17, while Java 25 can run the tiny pipe test. The Gradle daemon still fails during its own socket handoff, so the remaining blocker is local Java/Windows loopback behavior, not an app-source compile error observed from Gradle.

## Try In Android Studio

1. Open this folder as an Android project.
2. Let Android Studio use its bundled JDK and Gradle sync.
3. Run the `app` configuration on a device or emulator.
4. Enable HumanRewrite under Android input method settings.
5. Use the debug unlock button in the app to enable paid features for 24 hours.

## Run The Local Backend

```powershell
npm --prefix backend test
npm --prefix backend start
```

Then in the Android app:

1. Set backend URL to `http://10.0.2.2:8787` for an emulator, or your computer LAN URL for a phone.
2. Enter `test_active_local` as the test purchase token.
3. Tap `Verify subscription token`.
4. Enable cloud fallback consent if you want rewrite requests to call `/rewrite`.

The backend intentionally does not log request bodies because rewrite text may be private.
