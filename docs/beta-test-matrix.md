# Beta Test Matrix

Run this before a closed Play Store beta.

## Devices

| Device Type | Required |
|---|---:|
| Android 10 low-end phone | Yes |
| Android 12 mid-range phone | Yes |
| Android 14/15 modern phone | Yes |
| Large-screen / foldable | Optional |
| Gemini Nano / AICore supported device | Yes for on-device AI phase |
| Unsupported on-device AI phone | Yes |

## Apps

| App | Test Cases |
|---|---|
| WhatsApp | type, rewrite selected text, rewrite current sentence, paste clip |
| Gmail | multiline rewrite, professional mode, templates |
| LinkedIn | natural/professional rewrite, emoji mix |
| Chrome | web text fields, password field behavior |
| Google Messages | short text, emoji, backspace, enter |
| ChatGPT | long prompt rewrite and templates |
| Instagram / Threads | emoji mix and short captions |

## Pass Criteria

- Keyboard opens within 500 ms after selection on mid-range phone.
- Normal typing works after subscription expiry.
- Paid features lock within 24 hours offline.
- No typed text appears in backend logs.
- Cloud fallback never runs without consent.
- Clipboard never saves text unless user takes an explicit action.
- Floating assistant opens settings/keyboard controls and does not read text via Accessibility.

