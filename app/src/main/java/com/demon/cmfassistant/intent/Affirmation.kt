package com.demon.cmfassistant.intent

/** Understands short yes / no answers in English, Hindi (Devanagari) and Hinglish (Hindi in Latin letters). */
object Affirmation {

    enum class Answer { YES, NO, OTHER }

    private val yes = setOf(
        // English
        "yes", "yeah", "yea", "yep", "yup", "ya", "yah", "sure", "ok", "okay", "okk", "okey", "k", "correct",
        "right", "exactly", "please", "proceed", "confirm", "ahead", "absolutely", "definitely", "affirmative",
        // Hinglish
        "haan", "han", "haa", "ha", "hanji", "haanji", "haji", "hnji", "ji", "jee", "theek", "thik", "tik",
        "sahi", "bilkul", "pakka", "kar", "karo", "kardo", "kholo", "khol", "chalo", "chalega", "accha", "acha",
        // Sounds people make
        "hmm", "hm", "hmmm", "mm", "mmm", "mhm", "uhhuh",
        // Devanagari
        "हाँ", "हां", "हा", "हाँजी", "हांजी", "जी", "ठीक", "सही", "बिल्कुल", "बिलकुल", "करो", "कर", "खोलो", "खोल",
        "चलो", "अच्छा", "हम्म", "हम", "हूँ", "हूं", "ओके", "ओके", "यस", "येस", "पक्का"
    )

    private val no = setOf(
        // English
        "no", "nope", "nah", "never", "dont", "not", "cancel", "stop", "wrong", "incorrect",
        // Hinglish
        "nahi", "nahin", "nai", "nahee", "nahiin", "mat", "rehne", "rehno", "chhodo", "chodo", "galat", "ruko",
        // Devanagari
        "नहीं", "नही", "नहि", "मत", "रहने", "छोड़ो", "छोड़", "रुको", "गलत", "कैंसिल", "नो"
    )

    private val yesPhrases = listOf("go ahead", "do it", "open it", "send it", "kar do", "khol do")
    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun classify(raw: String): Answer {
        val text = raw.lowercase().replace("'", "").replace("’", "").trim()
        val tokens = text.split(splitter).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Answer.OTHER
        // A lone "na" / "ना" is a refusal; inside a sentence it is usually a filler ("haan na").
        if (tokens.size == 1 && (tokens[0] == "na" || tokens[0] == "ना")) return Answer.NO
        if (tokens.any { it in no }) return Answer.NO
        if (tokens.any { it in yes } || yesPhrases.any { text.contains(it) }) return Answer.YES
        return Answer.OTHER
    }
}
