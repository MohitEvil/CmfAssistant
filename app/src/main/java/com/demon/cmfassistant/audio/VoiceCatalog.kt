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
    /** "kokoro", "supertonic", "matcha", "melo" or "piper": which sherpa-onnx model type this is. */
    val kind: String,
    val modelFile: String,
    /** Voice name to speaker id. */
    val voices: List<Pair<String, Int>>,
    val defaultSid: Int,
    /** Big models are slower, so the app skips the background phrase pre-render for them. */
    val heavy: Boolean = false,
    /** True if the model can also read Hindi (Devanagari) text. */
    val hindi: Boolean = false,
    /** Extra single files to fetch into the model folder: (url, file name). Matcha needs a separate vocoder. */
    val extras: List<Pair<String, String>> = emptyList()
)

/** Files that must exist in the model folder. A missing one makes the native engine abort the whole app. */
fun VoiceModel.requiredFiles(): List<String> = when (kind) {
    "kokoro" -> listOf(modelFile, "voices.bin", "tokens.txt", "espeak-ng-data")
    "supertonic" -> listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin"
    )
    "matcha" -> listOf(modelFile, "tokens.txt", "espeak-ng-data")
    "melo" -> listOf(modelFile, "lexicon.txt", "tokens.txt", "dict")
    "piper" -> listOf(modelFile, "zani.onnx.json", "tokens.txt", "espeak-ng-data")
    else -> listOfNotNull(modelFile.ifBlank { null }, "voices.bin", "tokens.txt") // newer Kitten voices may or may not ship espeak-ng-data
} + extras.map { it.second }

/** The model file: the named one, or (when no name is set) the first .onnx file in the folder. */
fun VoiceModel.resolveModel(dir: java.io.File): java.io.File? {
    if (modelFile.isNotBlank()) return java.io.File(dir, modelFile).takeIf { it.exists() }
    return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".onnx") }?.minByOrNull { it.name }
}

/** A pronunciation lexicon shipped with the voice, if any (newer Kitten voices need it). */
fun VoiceModel.findLexicon(dir: java.io.File): java.io.File? =
    dir.listFiles()?.filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }?.minByOrNull { it.name }

object VoiceCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    private val kittenVoices = (1..8).map { "Voice $it" to (it - 1) }

    // Kitten voices were removed: sherpa-onnx 2.x dropped its built-in phonemizer for Kitten, so they stay silent.
    val all = listOf(
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
            blurb = "Fast, clean and natural. Ten voices, quick to start, and it reads English and Hindi (Devanagari).",
            sizeLabel = "medium",
            url = BASE + "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
            folder = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            kind = "supertonic",
            modelFile = "vector_estimator.int8.onnx",
            voices = (1..10).map { "Voice $it" to (it - 1) },
            defaultSid = 0,
            hindi = true
        ),
        VoiceModel(
            id = "piper-zani",
            name = "ZANI (YOUR VOICE)",
            blurb = "Your own trained Piper voice. Fast and light, English. Downloads your two voice files from your GitHub repo plus a small pronunciation pack.",
            sizeLabel = "small",
            url = BASE + "espeak-ng-data.tar.bz2",
            folder = "piper-zani",
            kind = "piper",
            modelFile = "zani.onnx",
            voices = listOf("Zani" to 0),
            defaultSid = 0,
            extras = listOf(
                "https://raw.githubusercontent.com/MohitEvil/Zani_Voice_model/main/zani.onnx" to "zani.onnx",
                "https://raw.githubusercontent.com/MohitEvil/Zani_Voice_model/main/zani.onnx.json" to "zani.onnx.json"
            )
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
