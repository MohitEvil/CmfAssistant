package com.demon.cmfassistant.intent

/** "Message Demon on Telegram" with no text yet: the next thing the user says is the message. */
object DictState {
    @Volatile private var contact = ""
    @Volatile private var app: String? = null
    @Volatile private var at = 0L

    fun ask(c: String, a: String?) { contact = c; app = a; at = System.currentTimeMillis() }
    fun active(): Boolean = contact.isNotEmpty() && System.currentTimeMillis() - at < 90_000
    fun clear() { contact = "" }

    private val cancel = Regex("^(?:cancel|never ?mind|forget it|stop|don't|do not|no|nothing|abort|leave it)\\b.*$", RegexOption.IGNORE_CASE)
    fun isCancel(s: String) = cancel.matches(s.trim().trimEnd('.', '!'))

    /** The command that sends [body], consuming the pending message. */
    fun finish(body: String): Command {
        val c = contact
        val a = app
        clear()
        val send = Command.UiMessage(c, body.trim().trim('"', '“', '”').replaceFirstChar { it.uppercase() })
        return if (a != null) Command.Chain(listOf(Command.OpenApp(a), send)) else send
    }

    private val I = RegexOption.IGNORE_CASE
    private const val APPS = "whatsapp|telegram|messages|signal|instagram|messenger|sms|slack|discord"

    /** "send a message to Demon on Telegram", "message Demon on WhatsApp" (nothing said to send yet). */
    fun parse(raw: String): Command? {
        val s = raw.trim().trimEnd('.', '!', '?')
        val m = Regex("^(?:(?:send|write|dictate|compose|type|draft)\\s+)?(?:a\\s+|an\\s+)?(?:new\\s+)?(?:message|msg|text|sms|whatsapp message|telegram message)\\s+(?:to|for)\\s+[\"'“‘]?([a-z][\\w .'-]{0,30}?)[\"'”’]?(?:\\s+(?:on|in|via|using|through)\\s+($APPS))?$", I).find(s)
            ?: Regex("^(?:message|msg|text|dm)\\s+[\"'“‘]?([a-z][\\w'-]{0,20})[\"'”’]?\\s+(?:on|in|via|using|through)\\s+($APPS)$", I).find(s)
            ?: return null
        val name = m.groupValues[1].trim()
        if (Regex("\\b(saying|that|says|with|:)\\b", I).containsMatchIn(name)) return null
        return Command.AskMessage(name, m.groupValues[2].ifEmpty { null })
    }
}
