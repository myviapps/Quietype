# Lightweight Open Model Options For Quietype

Goal: find the smallest practical model for grammar correction and human-sounding rewrite inside an Android keyboard.

## Recommendation

Start with **SmolLM2-360M-Instruct GGUF via llama.cpp** for the first on-device experiment.

Why:

- Small enough to realistically package/test on Android.
- Apache 2.0 license is friendly for a paid app.
- Stronger than ultra-tiny 100M-class models, much lighter than 1B+ models.
- GGUF + llama.cpp has an Android path and keeps model/runtime choices flexible.

Fallback candidate: **Qwen3-0.6B** if SmolLM2-360M quality is too weak.

## Candidate Models

| Model | Size | License | Fit For Keyboard | Notes |
|---|---:|---|---|---|
| SmolLM2-135M-Instruct | 135M | Apache 2.0 | Too weak for final quality, useful for speed test | Very small, but grammar rewrite may be inconsistent. |
| SmolLM2-360M-Instruct | 360M | Apache 2.0 | Best first experiment | Good size/quality balance for local rewrite. |
| Qwen3-0.6B | 600M | Apache 2.0 | Strong second option | Likely better quality, heavier than SmolLM2-360M. |
| SmolLM2-1.7B-Instruct | 1.7B | Apache 2.0 | Higher quality, heavier app | Better rewrite quality but large for keyboard distribution. |
| TinyLlama 1.1B Chat | 1.1B | Open-source project | Possible, older | More mature ecosystem, but not the best modern small-model pick. |
| Llama 3.2 1B Instruct | 1B | Meta Llama license | Good quality, license review needed | Designed for on-device uses, but not OSI open-source. |
| Gemma 3n | 2B/4B effective class | Gemma terms | Good mobile AI path, not very light | Optimized for devices, but heavier and custom-licensed. |
| MobileLLM 125M/350M | 125M/350M | Check carefully | Research interest | Very relevant architecture, but licensing may not fit a commercial paid app. |

## Runtime Choice

Recommended first runtime: **llama.cpp Android + GGUF**.

Reasons:

- Mature mobile/edge ecosystem.
- GGUF quantized models are easy to swap.
- Android docs/examples exist.
- Works without committing to a single model family.

Alternative runtimes:

- **MLC LLM Android**: good performance-oriented packaging, more setup.
- **ExecuTorch**: strong PyTorch/mobile direction, more involved conversion path.
- **Google AICore / Gemini Nano / ML Kit GenAI**: best Android-native experience where supported, but not open-source and not available on every phone.

## Product Decision

Use a layered rewrite engine:

1. Deterministic local cleanup for obvious grammar/spelling.
2. On-device open model when installed/supported.
3. Cloud fallback only after explicit consent and active subscription.

This keeps the keyboard fast and private even when the local model is too slow or unavailable.

## Test Prompt

Use this fixed prompt for model evaluation:

```text
Fix grammar and spelling only. Preserve the user's voice. Do not add facts. Do not make it sound AI-written. Return only the corrected text.

Text:
i dont want teh text to sound ai written but it should not have grammer mistakes
```

Expected style:

```text
I don't want the text to sound AI-written, but it should not have grammar mistakes.
```

