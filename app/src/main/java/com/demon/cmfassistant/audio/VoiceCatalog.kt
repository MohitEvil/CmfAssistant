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
    /** "kitten" or "kokoro": which sherpa-onnx model type this is. */
    val kind: String,
    val modelFile: String,
    /** Voice name to speaker id. */
    val voices: List<Pair<String, Int>>,
    val defaultSid: Int,
    /** Big models are slower, so the app skips the background phrase pre-render for them. */
    val heavy: Boolean = false
)

object VoiceCatalog {
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
