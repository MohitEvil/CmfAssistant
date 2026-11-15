package com.demon.cmfassistant.intent

/** Short memory for notification follow-ups ("what does Telegram say", "reply to her") and for a reply that is being dictated. */
object NotifyState {
    @Volatile var lastWho = ""
        private set
    @Volatile private var lastAt = 0L

    /** 0 nothing, 1 asked "want to reply?", 2 waiting for the text of the reply. */
    @Volatile var stage = 0
        private set
    @Volatile var key = ""
        private set
    @Volatile var name = ""
        private set
    @Volatile private var stageAt = 0L

    fun remember(who: String) { lastWho = who; lastAt = System.currentTimeMillis() }
    fun recentWho(): String? = lastWho.takeIf { it.isNotBlank() && System.currentTimeMillis() - lastAt < 4 * 60_000 }

    fun ask(k: String, n: String) { stage = 1; key = k; name = n; stageAt = System.currentTimeMillis() }
    fun awaitText() { stage = 2; stageAt = System.currentTimeMillis() }
    fun clear() { stage = 0 }
    fun active(): Boolean = stage != 0 && System.currentTimeMillis() - stageAt < 90_000
}
