package com.demon.cmfassistant.ai

/** Tiny offline fallback so casual chat still gets a friendly reply when no AI key is set. */
object SmallTalk {
    private val I = RegexOption.IGNORE_CASE

    private val jokes = listOf(
        "Why did the phone go to therapy? Too many unresolved notifications.",
        "I told my battery a joke. It didn't laugh, it just drained.",
        "Why was the computer cold? It left its Windows open.",
        "Ek aadmi ne phone se kaha, tu bahut smart hai. Phone bola, bas thoda sa charge chahiye."
    )

    private val rules: List<Pair<Regex, List<String>>> = listOf(
        Regex("how are you|how r u|kaise ho|kaisi ho|kya haal|kaisa hai|how's it going", I) to listOf(
            "Doing great, thanks for asking. How about you?",
            "Main badhiya hoon! Aap batao, kya chal raha hai?"
        ),
        Regex("good morning|suprabhat", I) to listOf("Good morning! Hope today treats you well.", "Suprabhat! Aaj ka din badhiya ho."),
        Regex("good night|shubh ratri|sweet dreams", I) to listOf("Good night! Sleep well.", "Shubh ratri! Achhe se sona."),
        Regex("thank|thanks|shukriya|dhanyavad|thx", I) to listOf("Anytime!", "Koi baat nahi!", "Happy to help."),
        Regex("your name|who are you|tum kaun|aap kaun|tera naam|tumhara naam", I) to listOf(
            "I'm CMF Assistant, living right here on your phone.",
            "Main CMF Assistant hoon, aapke phone mein rehta hoon."
        ),
        Regex("what can you do|how can you help|kya kar sakte|kya kar sakti", I) to listOf(
            "I can open apps, set timers and alarms, call or text, control the flashlight and volume, and chat. Just ask."
        ),
        Regex("joke|hasao|mazaak|chutkula", I) to jokes,
        Regex("love you", I) to listOf("That's sweet. I'm just an assistant, but I'm glad to be here."),
        Regex("bored|bore ho", I) to listOf("Let's fix that. Want a joke, or should I open some music?"),
        Regex("\\b(bye|goodbye|see you|alvida|tata)\\b", I) to listOf("Bye! Call me anytime.", "Chalo, phir milte hain!"),
        Regex("\\b(hi|hello|hey|hii+|namaste|namaskar|yo)\\b", I) to listOf(
            "Hey! What can I do for you?",
            "Hello! I'm listening.",
            "Namaste! Kya karna hai aaj?"
        )
    )

    fun reply(text: String): String? = rules.firstOrNull { it.first.containsMatchIn(text) }?.second?.random()
}
