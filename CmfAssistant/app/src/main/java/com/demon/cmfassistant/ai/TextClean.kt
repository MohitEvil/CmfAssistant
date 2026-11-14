package com.demon.cmfassistant.ai

/** Keeps answers plain: no markdown on screen, and nothing awkward for the voice to read out. */
object TextClean {
    private val url = Regex("https?://\\S+")
    private val emphasis = Regex("(\\*\\*|__|`+|~~)")
    private val listMarker = Regex("(?m)^\\s*(#{1,6}|[-*•]|\\d+\\.)\\s+")
    private val spaces = Regex("\\s+")

    private val thinkBlock = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun forDisplay(s: String): String =
        s.replace(thinkBlock, "")
            .replace(emphasis, "")
            .replace(listMarker, "")
            .replace("*", "")
            .trim()

    fun forSpeech(s: String): String {
        val t = forDisplay(s)
            .replace(url, "link")
            .filter { !it.isSurrogate() && Character.getType(it) != Character.OTHER_SYMBOL.toInt() }
        return t.replace(spaces, " ").trim()
    }
}
