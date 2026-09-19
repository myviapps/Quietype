# On-Device AI Setup

The app has `OnDeviceAiGateway` and `RewriteCoordinator` boundaries. Production on-device rewrite should plug into that boundary without changing the keyboard UI.

## Target Behavior

1. Try selected on-device grammar/rewrite first.
2. If unsupported or failed:
   - `ON_DEVICE_ONLY`: show failure and do not send text.
   - `ASK_BEFORE_CLOUD`: require saved user consent before backend rewrite.
   - `DISABLE_CLOUD`: show failure and do not send text.
3. Cloud rewrite requires active subscription and backend session token.

## Integration Checklist

- First open-model experiment: SmolLM2-360M-Instruct quantized to GGUF through llama.cpp Android.
- Fallback open-model experiment: Qwen3-0.6B quantized to GGUF if SmolLM2 quality is too weak.
- Add the official Google ML Kit GenAI / AICore dependency later if we want the Google-managed device path.
- Implement `OnDeviceAiGateway.isRewriteSupported()`.
- Implement mode mapping:
  - `GRAMMAR_ONLY`: minimal correction.
  - `NATURAL_CASUAL`: human, casual correction.
  - `PROFESSIONAL`: work-safe correction.
- Add timeout handling so the keyboard never freezes.
- Do not store source text or rewritten text.
- Add real-device tests on supported and unsupported devices.

## Android Code Boundary

The app already includes:

- `LocalModelOption`: `NONE`, `SMOLLM2_360M`, `QWEN3_06B`.
- `SettingsStore.localModelOption`.
- `LocalModelRewriteGateway`, currently using deterministic cleanup until llama.cpp is wired.
- `RewriteRequest.localModel`, sent to backend for observability/debugging.

## Fallback Contract

If on-device rewrite is unavailable, the app must not silently send text to cloud. The user must explicitly allow cloud fallback in settings.
