package com.demon.cmfassistant.audio

/** One downloadable offline voice model (runs on-device with sherpa-onnx). */
data class VoiceModel(
    val id: String,
    val name: String,
    val blurb: String,
    val sizeLabel: String,
    val url: String,
    /** Folder name inside the downloaded archive. */
    val folder: String,
    /** "kitten", "kokoro", "supertonic", "matcha" or "melo": which sherpa-onnx model type this is. */
    val kind: String,
    val modelFile: String,
    /** Voice name to speaker id. */
    val voices: List<Pair<String, Int>>,
    val defaultSid: Int,
    /** Big models are slower, so the app skips the background phrase pre-render for them. */
    val heavy: Boolean = false,
    /** Extra single files to fetch into the model folder: (url, file name). Matcha needs a separate vocoder. */
    val extras: List<Pair<String, String>> = emptyList()
)

object VoiceCatalog {
    /** Reference clip file names (assets/voice_refs) in the same order as the Zani style speaker ids. */
    val zaniRefs = listOf("zani_01_polite.wav", "zani_02_formal.wav", "zani_03_cynical.wav", "zani_04_caring.wav", "zani_05_weary.wav", "zani_06_warm.wav", "zani_07_deadpan.wav", "zani_08_firm.wav", "zani_09_critical.wav", "zani_10_cozy.wav", "zani_11_sweet.wav", "zani_12_nostalgic.wav", "zani_13_humorous.wav", "zani_14_realistic.wav", "zani_15_loyal.wav", "zani_16_admiring.wav", "zani_17_daydream.wav", "zani_18_upbeat.wav", "zani_19_wry.wav", "zani_20_heroic.wav")

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    private val kittenVoices = listOf(
        "Voice 2 (male)" to 0, "Voice 2 (female)" to 1,
        "Voice 3 (male)" to 2, "Voice 3 (female)" to 3,
        "Voice 4 (male)" to 4, "Voice 4 (female)" to 5,
        "Voice 5 (male)" to 6, "Voice 5 (female)" to 7
    )

    val all = listOf(
        VoiceModel(
            id = "kitten-nano",
            name = "KITTEN NANO",
            blurb = "Tiny and very fast. Natural, expressive English voices. Best for instant replies.",
            sizeLabel = "small",
            url = BASE + "kitten-nano-en-v0_1-fp16.tar.bz2",
            folder = "kitten-nano-en-v0_1-fp16",
            kind = "kitten",
            modelFile = "model.fp16.onnx",
            voices = kittenVoices,
            defaultSid = 3
        ),
        VoiceModel(
            id = "kitten-mini",
            name = "KITTEN MINI",
            blurb = "A bigger Kitten with richer, smoother speech. Still fast on most phones.",
            sizeLabel = "medium",
            url = BASE + "kitten-mini-en-v0_8.tar.bz2",
            folder = "kitten-mini-en-v0_8",
            kind = "kitten",
            modelFile = "model.onnx",
            voices = kittenVoices,
            defaultSid = 3
        ),
        VoiceModel(
            id = "kokoro",
            name = "KOKORO",
            blurb = "The most natural and expressive. Large download and slower to start, so repeat phrases are cached.",
            sizeLabel = "about 330 MB",
            url = BASE + "kokoro-multi-lang-v1_0.tar.bz2",
            folder = "kokoro-multi-lang-v1_0",
            kind = "kokoro",
            modelFile = "model.onnx",
            voices = listOf(
                "Heart (US female)" to 3, "Bella (US female)" to 2, "Nicole (US female)" to 6,
                "Sarah (US female)" to 9, "Sky (US female)" to 10,
                "Adam (US male)" to 11, "Michael (US male)" to 16,
                "Emma (UK female)" to 21, "George (UK male)" to 26
            ),
            defaultSid = 3,
            heavy = true
        ),
        VoiceModel(
            id = "supertonic",
            name = "SUPERTONIC 3",
            blurb = "Fast, clean and natural. Ten voices, quick to start even on a phone.",
            sizeLabel = "medium",
            url = BASE + "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            folder = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            kind = "supertonic",
            modelFile = "vector_estimator.int8.onnx",
            voices = (1..10).map { "Voice $it" to (it - 1) },
            defaultSid = 0
        ),
        VoiceModel(
            id = "matcha-vocos",
            name = "MATCHA + VOCOS",
            blurb = "Matcha-TTS with the Vocos vocoder. Smooth single English voice (LJSpeech). Downloads two files.",
            sizeLabel = "medium",
            url = BASE + "matcha-icefall-en_US-ljspeech.tar.bz2",
            folder = "matcha-icefall-en_US-ljspeech",
            kind = "matcha",
            modelFile = "model-steps-3.onnx",
            voices = listOf("LJSpeech (female)" to 0),
            defaultSid = 0,
            extras = listOf("https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-22khz-univ.onnx" to "vocos-22khz-univ.onnx")
        ),
        VoiceModel(
            id = "melotts",
            name = "MELOTTS",
            blurb = "MeloTTS Chinese + English, one voice. English words work only if they are in its lexicon, so odd words may be skipped.",
            sizeLabel = "about 160 MB",
            url = BASE + "vits-melo-tts-zh_en.tar.bz2",
            folder = "vits-melo-tts-zh_en",
            kind = "melo",
            modelFile = "model.onnx",
            voices = listOf("Default" to 0),
            defaultSid = 0,
            heavy = true
        ),
        VoiceModel(
            id = "pocket-zani",
            name = "ZANI (YOUR VOICE)",
            blurb = "Kyutai Pocket TTS cloning your Zani samples. Each of the 20 styles is a speaker. Slower to start, so repeat phrases are cached.",
            sizeLabel = "about 100 MB",
            url = BASE + "sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
            folder = "sherpa-onnx-pocket-tts-int8-2026-01-26",
            kind = "pocket",
            modelFile = "lm_main.int8.onnx",
            voices = listOf("Polite" to 0, "Formal" to 1, "Cynical" to 2, "Caring" to 3, "Weary" to 4, "Warm" to 5, "Deadpan" to 6, "Firm" to 7, "Critical" to 8, "Cozy" to 9, "Sweet" to 10, "Nostalgic" to 11, "Humorous" to 12, "Realistic" to 13, "Loyal" to 14, "Admiring" to 15, "Daydream" to 16, "Upbeat" to 17, "Wry" to 18, "Heroic" to 19),
            defaultSid = 5,
            heavy = true
        )
    )

    fun byId(id: String): VoiceModel? = all.firstOrNull { it.id == id }

    /** Short fixed replies. Rendered once in the background so they play with no wait. */
    val staticPhrases = listOf(
        "Flashlight on.", "Torch is on.", "Light's on.", "Flashlight off.", "Torch is off.", "Light's off.",
        "Muted.", "Quiet now.", "Playing.", "Resuming.", "Paused.", "Okay, paused.", "Skipping.", "Next one.",
        "Going back.", "Previous track.", "Opening the camera.", "Camera's up.", "Here's the camera.", "Okay.",
        "Okay, I won't send it.", "Here are your settings.", "All set. Tap send when you're ready.",
        "Drafted. Tap send in Messages.", "Microphone permission needed."
    )
}
