# CMF Assistant

A Nothing-style voice assistant for the CMF Phone 1, built from the "Glyph Pulse" plan.
Kotlin + Jetpack Compose. No root, no PC needed (build with GitHub Actions).

## Build without a PC
The workflow also downloads the sherpa-onnx library for the neural voice (scripts/fetch-sherpa.sh). Building locally? Run `bash scripts/fetch-sherpa.sh` once first. If that step fails the app still builds and just uses the phone voice.

1. Create a GitHub repo and upload this whole folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Build APK** (it also runs on every push).
3. Download the `CMF-Assistant-APKs` artifact, unzip, install `app-release.apk`.

Or in Android Studio: open the folder and press Run.

## What's new
- **It talks back.** Answers and confirmations are spoken with your phone's own text-to-speech (Settings > Spoken replies). Tap the glyph while it talks to interrupt and speak.
- **"Do you mean namida?"** If an app name isn't found but a close one is installed, it asks out loud. Say yes, yeah, haan, ha, hanji, hmm, theek hai (or tap YES) and it opens. Say no, nahi, or anything else and it says it couldn't find the app.
- **Floating bubble opens a small panel** over your current app (a dot-matrix line, what you said, the answer), not the whole app. Tap outside to dismiss, OPEN APP for the full screen.
- **Follow-up conversation.** After an answer the mic reopens, so you can keep chatting. It goes quiet after a few seconds of silence. Recent context is remembered for two minutes.
- **Casual chat and questions.** Add an API key in Settings > AI Chat (Gemini, Groq, OpenRouter, OpenAI, Anthropic or any OpenAI-compatible address). Without a key you get basic small talk, and questions fall back to a web search.

- **Persona.** With an AI key set, Settings > Assistant persona lets you pick a preset (Minimal, Friendly, Witty, Desi Buddy, Formal) or write your own. Default is a quiet, minimal assistant that keeps answers short and is good at small talk. "Try it" shows how it would answer.
- **Test connection** button under AI Chat shows the exact reason if the AI can't be reached (bad key, wrong model, no internet).
- Command confirmations are now varied and natural instead of echoing what you said.

- **Offline neural voice** (Settings > Voice). Download a voice once (Supertonic is fast, Kokoro is the most natural but about 330 MB; Kitten was removed because the current engine can't speak it) and it works with no internet afterwards. It replaces the stock Android voice for English. Pick the speaker and speed there. Devanagari Hindi still uses the phone voice.
- **More voices** (Settings > Voice): Supertonic 3 (10 voices, fast, reads English and Hindi) and Kokoro (English). MeloTTS and Matcha + Vocos were removed as slow and unreliable.
  - **Zani (your voice):** Kyutai Pocket TTS cloned from your voice samples (main) plus three moods (soft, serious, amused). English only.
  - **Hinglish:** use the HINGLISH persona so replies mix Devanagari Hindi with English words. Supertonic speaks Devanagari as Hindi and Latin text as English.
  - **Not included:** "Phonon 31M" is by Gradium (not Google) and ships as a GGUF for Gradium's own Rust runtime, not MediaPipe/LiteRT, so it can't run in this app today.
