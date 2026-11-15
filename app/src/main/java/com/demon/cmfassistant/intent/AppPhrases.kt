package com.demon.cmfassistant.intent

/**
 * "Search X on Instagram" (open it and use its search box), "search X" (use the search box on screen),
 * "play X on Namida", "play a random song", "play a video", "install X".
 * The web is used only when it is named: "search X on Google / the web".
 */
object AppPhrases {
    private val I = RegexOption.IGNORE_CASE
    private val webApps = setOf("google", "web", "the web", "internet", "the internet", "browser", "chrome", "bing", "duckduckgo", "online", "google search", "the browser")
    private val hereWords = setOf("screen", "this screen", "page", "this page", "this app", "here", "search bar", "search box", "the search bar", "the search box", "the screen", "app", "this")
    private val notApps = setOf("repeat", "loop", "shuffle", "full volume", "max volume", "bluetooth", "speaker", "speakers", "headphones", "earphones", "tv", "my tv", "the tv", "loud", "low volume")
    private val videoWords = Regex("\\b(video|videos|clip|clips|trailer|trailers|reel|reels|shorts?|movie|movies|episode|vlog|vlogs|film|tutorial)\\b", I)
    private val randomThing = Regex("^(?:a |an |any |some |one )?(?:random |any |some |good |nice )*(?:music|song|songs|track|tracks|tune|tunes|something|anything|audio|video|videos|playlist|stuff|clip)(?: for me| please)?$", I)

    fun parse(s: String, l: String): Command? {
        val t = s.trim().trimEnd('.', '!', '?').replace("’", "'").replace(Regex("\\s+"), " ")

        // install
        Regex("^(?:install|download)(?: me)?(?: the)? (.+?)(?: app| application)?(?: (?:from|on|in|using|via) (?:the )?(?:play ?store|google play|app ?store))?$", I).find(t)?.let { m ->
            val whole = m.value.lowercase()
            val name = m.groupValues[1].trim()
            val appish = whole.startsWith("install") || whole.contains("app") || whole.contains("play")
            if (name.isNotEmpty() && appish && !Regex("^(?:the |a )?(?:update|updates|file|files|video|song|music|pdf|image|photo)s?$", I).matches(name)) return Command.InstallApp(name)
        }

        // search on a named app / the web / this screen
        Regex("^(?:search|find|look up|look for|search for|google)(?: for)? (.+?) (?:on|in|inside|within|using|from|at|through) (?:the |my )?(.+?)(?: app)?$", I).find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            val where = m.groupValues[2].trim().lowercase()
            if (q.isEmpty() || where.isEmpty()) return@let
            if (where in webApps) return Command.Search(q)
            if (where in hereWords) return if (t.lowercase().startsWith("search")) Command.SmartSearch(q) else null
            // "find bluetooth in settings" stays a settings lookup when the "app" is really the settings app
            return Command.AppSearch(where, q)
        }
        // plain search: the search box on screen first
        Regex("^(?:search|look up|search for)(?: for)? (.+)$", I).find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            if (Regex("^(?:the )?(?:web|internet|google|online)\\b", I).containsMatchIn(q)) return null
            if (Regex("\\b(?:on|in) (?:the )?(?:web|google|internet|online)$", I).containsMatchIn(q)) return null
            if (q.isNotEmpty() && !Regex("^(?:the )?(?:search )?(?:bar|box|field|icon|button)$", I).matches(q)) return Command.SmartSearch(q)
        }

        // play
        if (!Regex("^play\\b", I).containsMatchIn(t)) return null
        Regex("^play(?: me)? (.+?) (?:on|in|using|with|from|through|via|inside) (?:the |my )?(.+?)(?: app)?$", I).find(t)?.let { m ->
            val thing = m.groupValues[1].trim()
            val app = m.groupValues[2].trim()
            if (app.lowercase() in notApps || thing.isEmpty()) return@let
            val yt = Regex("\\b(?:you ?tube|yt)\\b", I).containsMatchIn(app)
            val video = yt || videoWords.containsMatchIn(thing) && !Regex("music", I).containsMatchIn(app)
            val q = if (randomThing.matches(thing)) null else cleanThing(thing)
            return Command.PlayIn(app, q, video)
        }
        Regex("^play(?: me)? (?:a |an |any |some )((?:random |any |good |nice )*(?:music|song|songs|track|tune|video|videos|something|clip|trailer|movie))(?: for me| please)?$", I).find(t)?.let { m ->
            return Command.PlayIn(null, null, videoWords.containsMatchIn(m.groupValues[1]))
        }
        Regex("^play(?: me)? (?:random|any|some|anything|something)(?: random)?(?: music| song| songs| video| videos| tune)?(?: for me| please)?$", I).find(t)?.let { return Command.PlayIn(null, null, videoWords.containsMatchIn(t)) }
        Regex("^play(?: me)? (.+)$", I).find(t)?.let { m ->
            val thing = m.groupValues[1].trim()
            if (thing.lowercase() in setOf("music", "song", "songs", "it", "that", "this", "again", "next", "previous", "pause", "something", "audio", "media", "the music", "the song") ||
                Regex("^(?:the )?(?:next|previous|last|prev)\\b", I).containsMatchIn(thing) || Regex("^(?:it|that|this)\\b", I).matches(thing)
            ) return null
            if (randomThing.matches(thing)) return Command.PlayIn(null, null, videoWords.containsMatchIn(thing))
            return Command.PlayIn(null, cleanThing(thing), videoWords.containsMatchIn(thing))
        }
        return null
    }

    /** "the song believer" -> "believer"; keeps "video" words out of the search text. */
    private fun cleanThing(t: String): String =
        t.replace(Regex("^(?:the |a |an )?(?:song |track |music |video |tune |clip )", I), "").replace(Regex("\\s+(?:song|video|track|music)$", I), "").trim().ifEmpty { t }
}
