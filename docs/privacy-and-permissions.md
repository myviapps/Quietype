# Quietype Privacy And Permissions

## Keyboard Warning

Android shows a system warning for third-party keyboards because keyboards can see typed text. Quietype must explain this clearly during onboarding:

- The keyboard processes active text only to type, rewrite, save clips, or paste templates.
- User text is not logged by default.
- Cloud rewrite is disabled unless the user explicitly allows fallback.
- Power clipboard storage is local and encrypted with Android Keystore.

## Data Handling

| Data | Stored On Device | Sent To Backend | Notes |
|---|---:|---:|---|
| Active text being rewritten | No permanent storage by default | Only with cloud fallback consent | Used only to return corrected text. |
| Saved clips | Yes, encrypted | No | User explicitly saves clips. |
| Emoji mixes | Yes, encrypted if saved | No | Emoji mixes are saved as pinned clips only when the user taps Save. |
| Templates | Yes, encrypted | No | User-created reusable snippets. |
| Subscription token | Yes, app prefs | Yes | Sent to verify paid entitlement. |
| Backend session token | Yes, app prefs | Yes | Sent as bearer token for cloud rewrite. |

## Permissions

- `INTERNET`: required for subscription verification and optional cloud rewrite.
- `BIND_INPUT_METHOD`: required by Android for custom keyboard services.
- Accessibility service (`FloatingAssistantService`): optional floating bubble. It reads and replaces the focused text box only when the user taps Rewrite, never in password fields, and stores or sends nothing.
- Debug builds allow cleartext traffic for local backend testing. Release builds should use HTTPS only.

## Explicit Non-Goals For V1

- No background clipboard scraping.
- No Accessibility use beyond the optional bubble: no event monitoring, no background reading.
- Rewrite, Save clip and the bubble are disabled in password and incognito fields.
- No silent cloud rewrite.
- No analytics events containing typed text, rewritten text, clips, or templates.
