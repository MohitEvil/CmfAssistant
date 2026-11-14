package com.demon.cmfassistant.intent

/** Ending a conversation politely: "no", "that's all", "stop listening", "thanks", and the questions that invite them. */
object Wrapup {

    sealed interface Result {
        /** Stop listening and close the panel. */
        data object Dismiss : Result
        /** "Thanks": reply politely and ask if there is more. */
        data object Thanks : Result
        /** A bare "yes" to "anything else?": keep listening. */
        data object Yes : Result
        /** Anything else. [text] has any trailing "thanks" removed. */
        data class Rest(val text: String) : Result
    }

    val askMore = listOf(
        "Is there anything else you want me to do?",
        "Anything else I can help with?",
        "Need anything else?",
        "What else can I do for you?",
        "Is there something else you'd like?",
        "Anything more you need?",
        "Want me to do anything else?",
        "Is that all, or should I keep going?",
        "Anything else on your mind?",
        "Should I help with anything else?",
        "Is there anything else?",
        "Anything else for me to do?",
        "Shall I do something else for you?",
        "Do you need anything more from me?"
    )

    val welcome = listOf(
        "You're welcome.", "Glad to help.", "My pleasure.", "Anytime.", "Happy to help.",
        "No problem.", "Of course.", "Anytime, glad I could help.", "Always happy to help.", "Don't mention it."
    )

    private val splitter = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    private val negativeStart = setOf("no", "nope", "nah", "naw", "negative", "nahi", "nahin", "nai", "nothing", "na", "नहीं", "नही", "नो")
    private val yesWords = setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "haan", "han", "ha", "please", "hmm", "हाँ", "हां", "जी")
    private val dismissAlways = setOf("close", "exit", "quit", "bye", "goodbye", "cancel", "dismiss", "enough", "nevermind", "bas", "बस")
    private val dismissPhrases = listOf(
        "stop listening", "stop it", "stop now", "close app", "close the app", "close this", "close it", "close assistant",
        "go away", "never mind", "thats all", "that is all", "im done", "i am done", "nothing else", "no more",
        "band karo", "bas karo", "bas itna", "rehne do", "no thanks", "no thank you", "good bye", "see you", "talk later"
    )
    private val fillers = setOf("ok", "okay", "great", "cool", "nice", "perfect", "alright", "awesome", "good", "oh", "wow", "that works", "got it")

    private val thanksTail = Regex(
        "[\\s,.!]*(?:thanks|thank you|thank u|thankyou|thx|ty|cheers|much appreciated|appreciate it|shukriya|dhanyavad|dhanyawad|धन्यवाद|शुक्रिया)" +
            "(?:\\s+(?:a lot|so much|very much|a ton|buddy|mate|assistant|man|bro|again|dear|you))*[\\s.!]*$",
        RegexOption.IGNORE_CASE
    )

    fun classify(raw: String, awaiting: Boolean): Result {
        val text = raw.trim()
        val tokens = text.lowercase().replace("’", "").replace("'", "").split(splitter).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result.Rest(raw)
        val joined = tokens.joinToString(" ")

        if (tokens.size <= 6 && dismissPhrases.any { joined == it || joined.contains(it) }) return Result.Dismiss
        if (tokens.size <= 2 && joined in dismissAlways) return Result.Dismiss
        if (awaiting && joined == "stop") return Result.Dismiss
        if (tokens.size <= 4 && tokens[0] in negativeStart) return Result.Dismiss
        if (awaiting && tokens.size <= 3 && tokens.all { it in yesWords }) return Result.Yes

        val m = thanksTail.find(text)
        if (m != null) {
            val rest = text.removeRange(m.range).trim().trimEnd(',', '.', '!').trim()
            val restNorm = rest.lowercase().replace(splitter, " ").trim()
            return if (restNorm.isEmpty() || restNorm in fillers) Result.Thanks else Result.Rest(rest)
        }
        return Result.Rest(raw)
    }
}
