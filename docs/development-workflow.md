# Development Workflow Without An Android Studio Codex Extension

Android Studio does not need a Codex extension for this project.

## How We Develop

1. Codex edits the project files directly in this workspace.
2. Android Studio opens the same folder and handles Gradle sync, emulator/device install, Logcat, profiling, and visual debugging.
3. When an issue appears in Android Studio, copy the error text or Logcat output back to Codex.
4. Codex updates the Kotlin, XML, Gradle, backend, or docs files.
5. Android Studio reruns the app on the emulator or physical phone.

This is the same workflow as using an external engineer with Android Studio: the IDE does not need to host the coding assistant.

## Recommended Setup

- Open `C:\Project Developer Project\Android games` in Android Studio.
- Let Android Studio use its bundled JDK.
- Let Android Studio download any missing Gradle/Android dependencies if prompted.
- Run the `app` configuration on a real Android phone first. Keyboards are easier to test on a real device than on an emulator.
- Start the local backend with:

```powershell
npm --prefix backend start
```

Use these backend URLs:

- Emulator: `http://10.0.2.2:8787`
- Physical phone: `http://<computer-lan-ip>:8787`

## Keyboard Quality Target

The keyboard should feel familiar to users of major Android keyboards without copying Google branding or proprietary behavior.

V1 quality targets:

- QWERTY letter rows.
- Suggestion/action strip.
- Toolbar actions for Rewrite, Clipboard, Templates, and Settings.
- Shift, symbols, backspace, enter, comma, period, and wide spacebar.
- Emoji panel plus Emoji Mix panel for combining emojis, symbols, kaomoji, and decorations.
- Paid features must fail safely without breaking normal typing.
- Rewrites must preserve the user's intent and human voice.

Later quality targets:

- Number row setting.
- Long-press symbols.
- Haptic and sound preferences.
- Theme options.
- Real suggestions/autocorrect dictionary.
- Glide typing only if a proven library or dedicated engine is chosen.
