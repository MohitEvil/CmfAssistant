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

- **Offline neural voice** (Settings > Voice). Download a voice once (Kitten Nano is tiny and fast, Kitten Mini is richer, Kokoro is the most natural but about 330 MB) and it works with no internet afterwards. It replaces the stock Android voice for English. Pick the speaker and speed there. Devanagari Hindi still uses the phone voice.
- **More voices** (Settings > Voice): Supertonic 3 (10 voices, fast), Matcha + Vocos (single English voice) and MeloTTS (Chinese + English) join Kitten and Kokoro, all through sherpa-onnx.
  - **Not included:** Kyutai Pocket TTS needs a reference voice clip and has no confirmed Android config yet. "Phonon 31M" is by Gradium (not Google) and ships as a GGUF for Gradium's own Rust runtime, not MediaPipe/LiteRT, so there is no way to run it in this app today.
- **Instant spoken status.** The spoken line now starts at the same moment the text appears, with no built-in waits. Short replies and "Opening <app>." are rendered ahead of time and cached, so they start at once.
- **Thinking mode switch** (Settings > AI Chat). Off by default, which is faster. The old cut-off answers came from thinking tokens eating the reply limit; the limit is now much larger and a cut answer is ended at a full sentence.

## What it does
- States: READY, LISTENING, RECOGNIZING, THINKING, CONFIRM, EXECUTING, DONE, FAILED. Each has its own dot-matrix glyph.
- Live microphone level drives the LISTENING ring. Live partial transcript shows while RECOGNIZING.
- Sending a message always shows a confirmation card first; it then opens Messages with the text ready and you tap send.
- Haptics are event-based only (mic on, recognised, action start, success, failure, cancel). Nothing vibrates continuously.
- Pure black AMOLED dark theme, plus light theme and system-follow.
- Battery aware: low-power mode or battery under 20% lowers frame rate; under 10% draws static glyphs. Idle is nearly static, and the mic is only open while listening.
- Optional floating bubble (draws over other apps, tap to talk, drag to move).

## Commands (all parsed on-device, no cloud AI)
- "turn on / off the flashlight"
- "open YouTube" (any installed app)
- "set a timer for 5 minutes", "set an alarm for 6:30 am"
- "call Mom", "text Mom saying on my way"
- "volume up / down / 40 percent", "mute"
- "pause", "play", "next song", "previous song"
- "battery", "what time is it", "what's the date"
- "open Bluetooth / Wi-Fi settings", "open camera"
- "navigate to Connaught Place", "search for cricket scores"
- Anything else is treated as a question or chat (needs an AI key for real answers)

## Limits to know
- Voice quality and Hindi depend on the text-to-speech voices installed on the phone (Settings > System > Languages > Text-to-speech).
- The API key is stored on the phone in app storage, not encrypted. Use a key you can revoke.
- The AI has no live internet, so news, scores and prices can't be answered live.
- Speech recognition uses Android's built-in recogniser (Google). Offline works if the language pack is downloaded; otherwise it needs internet.
- Android does not let normal apps toggle Wi-Fi or Bluetooth, so those open the settings panel.
- This is a normal app, not a system assistant: launch it from the icon, the bubble, or an assist gesture if your phone offers this app for it.
- The CMF Phone 1 has no rear Glyph lights, so the glyph visuals are on-screen only.
- This was written without an Android SDK available, so it has not been compiled yet. If the first GitHub build shows an error, send me the log and I'll fix it.
