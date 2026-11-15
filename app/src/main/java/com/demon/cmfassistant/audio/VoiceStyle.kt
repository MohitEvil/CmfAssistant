package com.demon.cmfassistant.audio

/**
 * Extra expression for AI voices that understand it (Gemini 3.x TTS): <chuckle> style cues and IPA for names the voice tends to
 * mispronounce. Never used for the offline voice or other providers, where the tags would be read out loud.
 */
object VoiceStyle {
    private val ipa = mapOf(
        "jaipur" to "/ˈdʒeɪpʊər/", "delhi" to "/ˈdɛli/", "mumbai" to "/mʊmˈbaɪ/", "kolkata" to "/koʊlˈkɑːtə/",
        "chennai" to "/ˈtʃɛnaɪ/", "bengaluru" to "/ˈbɛŋɡəluːru/", "bangalore" to "/ˈbæŋɡəlɔːr/", "hyderabad" to "/ˈhaɪdərəbɑːd/",
        "pune" to "/ˈpuːnə/", "lucknow" to "/ˈlʌknaʊ/", "ahmedabad" to "/ˈɑːmədəbɑːd/", "gujarat" to "/ˌɡʊdʒəˈrɑːt/",
        "uttarakhand" to "/ˌʊtərəˈkʌnd/", "kerala" to "/ˈkɛrələ/", "punjab" to "/pʌnˈdʒɑːb/", "chandigarh" to "/ˈtʃʌndɪɡɑːr/",
        "cmf" to "/ˌsiː ɛm ˈɛf/", "whatsapp" to "/ˈwɒtsæp/", "wifi" to "/ˈwaɪfaɪ/", "nfc" to "/ˌɛn ɛf ˈsiː/",
        "oled" to "/ˈoʊlɛd/", "adb" to "/ˌeɪ diː ˈbiː/", "dpi" to "/ˌdiː piː ˈaɪ/", "gps" to "/ˌdʒiː piː ˈɛs/", "upi" to "/ˌjuː piː ˈaɪ/"
    )

    private val laugh = Regex("\\b(?:ha(?:\\s?ha)+|he(?:\\s?he)+|lol|lmao)\\b[!.]?", RegexOption.IGNORE_CASE)

    fun decorate(text: String): String {
        var t = text
        t = laugh.replace(t, "<chuckle>")
        for ((word, sym) in ipa) t = Regex("\\b$word\\b", RegexOption.IGNORE_CASE).replace(t, sym)
        return t
    }
}
