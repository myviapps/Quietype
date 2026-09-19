# Quietype Product Validation

## Positioning

Quietype is a privacy-first paid Android keyboard for grammar correction, human-sounding rewrite, and power clipboard workflows. The product should not compete as a broad AI writing suite. Its wedge is fast correction inside any Android text field while preserving the user's own voice.

## Existing Services

- Grammarly: strong grammar and rewrite brand, cross-app Android keyboard behavior.
- Gboard: first-party Google keyboard with AI writing features on supported devices.
- Microsoft SwiftKey: mature keyboard with AI writing integrations.
- CleverType: AI keyboard with grammar, tone, paraphrase, translation, and ChatGPT-style features.
- ParagraphAI: AI writing assistant and keyboard workflow.
- QuillBot: grammar, paraphrase, humanizer, translation, and writing tools.
- Clipboard managers: useful but weakened by Android clipboard privacy restrictions unless the workflow lives inside the active keyboard.

## Differentiator

The v1 app should lead with:

1. Grammar correction that does not sound AI-written.
2. Local-first privacy with explicit cloud fallback consent.
3. A paid power clipboard built into the keyboard.
4. Subscription enforcement that stops paid features after expiry, including offline expiry.

## Platform Constraints

- Keyboard-first is safer than overlay-first because Android IMEs can commit text into active fields through `InputConnection`.
- Floating overlay is a V2 feature because reading or replacing text outside the keyboard may require overlay and Accessibility permissions.
- Android 10+ restricts background clipboard access, so Quietype uses manual save and keyboard-active clipboard actions rather than silent scraping.
- Play Store distribution should use Google Play Billing for subscriptions to digital features.
- On-device Gemini Nano/AICore or ML Kit GenAI integrations should be treated as adapters, not assumed available on every phone.

