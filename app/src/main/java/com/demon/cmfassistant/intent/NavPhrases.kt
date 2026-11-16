package com.demon.cmfassistant.intent

/** Navigation and map phrases: go to, nearby places, distance, travel time, best route. */
object NavPhrases {
    private val I = RegexOption.IGNORE_CASE

    private const val CAT = "hotels?|restaurants?|cafes?|coffee shops?|parks?|gardens?|schools?|colleges?|universit(?:y|ies)|markets?|shops?|stores?|supermarkets?|malls?|hospitals?|clinics?|doctors?|pharmac(?:y|ies)|medical stores?|chemists?|atms?|banks?|petrol pumps?|petrol stations?|gas stations?|fuel stations?|pumps?|ev charging stations?|charging stations?|parking|bus stops?|bus stands?|bus stations?|metro stations?|railway stations?|train stations?|police stations?|temples?|mosques?|churches?|gurudwaras?|gyms?|salons?|bakery|bakeries|cinemas?|movie theaters?|theatres?|libraries|library|post offices?|toilets?|restrooms?|washrooms?|hostels?|lodges?|guest houses?|dhabas?|food|places to eat|grocery stores?|kirana|laundry|mechanics?|garages?|car wash|airports?|taxi stands?|tourist places?|tourist spots?|attractions?|lakes?|beach(?:es)?|stations?|bars?|pubs?|fast food|pizza|biryani|sweet shops?|stationery|book ?stores?|electronics|mobile shops?|repair shops?|water|police|fire station"

    private fun mode(t: String): String = when {
        Regex("\\b(walk|walking|on foot|by foot)\\b", I).containsMatchIn(t) -> "walking"
        Regex("\\b(bike|biking|cycle|cycling|bicycle|by bike)\\b", I).containsMatchIn(t) -> "cycling"
        Regex("\\b(bus|train|metro|transit|public transport|by bus|by train)\\b", I).containsMatchIn(t) -> "transit"
        else -> "driving"
    }

    private fun stripMode(p: String) = p.replace(Regex("\\s+(?:on foot|by foot|walking|by walk|by car|driving|by bike|cycling|by cycle|by bus|by train|by metro)$", I), "").trim()

    private fun clean(p: String) = stripMode(p.trim().trimEnd('?', '.', '!')).removePrefix("the ").trim()

    fun parse(s: String, l: String): Command? {
        val t = s.trim().trimEnd('?', '.', '!').replace("’", "'").replace(Regex("\\s+"), " ")
        if (t.length > 140) return null

        // nearby places
        Regex("^(?:find|show|search|search for|look for|locate|get|list|where(?:'s| is| are)|any|is there|are there|which|what are)(?: me)?(?: the| a| an| some| any)?(?: nearest| nearby| closest| best| good| top| famous| cheap)*\\s*($CAT)(?:\\s+(?:near me|nearby|around me|near here|around here|close by|close to me|close by me|around|near|in|around|close to|at)(?:\\s+(.+))?)?$", I).find(t)?.let { m ->
            val place = m.groupValues[2].trim()
            val tail = t.lowercase()
            return Command.Nav(NavOp.NEARBY, m.groupValues[1].lowercase(), from = if (place.lowercase() in setOf("me", "here", "my location", "my place")) "" else place)
        }
        Regex("^(?:nearest|closest|nearby)\\s+($CAT)$", I).find(t)?.let { return Command.Nav(NavOp.NEARBY, it.groupValues[1].lowercase()) }
        Regex("^what(?:'s| is) (?:the )?(?:nearest|closest) ($CAT)(?: to me| from here)?$", I).find(t)?.let { return Command.Nav(NavOp.NEARBY, it.groupValues[1].lowercase()) }

        // distance
        Regex("^how (?:far|many (?:km|kms|kilometers|kilometres|miles)) (?:is|are|to|from)(?: the)? (.+?) from (.+)$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.DISTANCE, clean(m.groupValues[1]), clean(m.groupValues[2]), mode(t))
        }
        Regex("^(?:what(?:'s| is) )?(?:the )?distance (?:between (.+?) and (.+)|from (.+?) to (.+)|to (.+))$", I).find(t)?.let { m ->
            val a = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val b = m.groupValues[2].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
            return Command.Nav(NavOp.DISTANCE, clean(b), clean(a), mode(t))
        }
        Regex("^how far(?: is it)?(?: (?:away|from here|to go|to reach|to get))?(?: to| is)? (.+)$", I).find(t)?.let { m ->
            val to = clean(m.groupValues[1])
            if (to.isNotEmpty() && !to.equals("it", true)) return Command.Nav(NavOp.DISTANCE, to, "", mode(t))
        }

        // time to travel
        Regex("^how (?:long|much time)(?: will it take| does it take| would it take| it will take| it takes| will i take| do i need| is needed)?(?: me)?(?: to)?(?: go| get| reach| drive| walk| travel| ride| cycle| commute| arrive)?(?: from (.+?))? (?:to|at) (.+)$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.TIME, clean(m.groupValues[2]), clean(m.groupValues[1]), mode(t))
        }
        Regex("^how (?:long|much time)(?: will it take| does it take| would it take)?(?: me)?(?: to)? (?:go|get|reach|drive|walk|travel|ride|cycle) (.+)$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.TIME, clean(m.groupValues[1]), "", mode(t))
        }
        Regex("^(?:eta|travel time|time) (?:to|for) (.+?)(?: from (.+))?$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.TIME, clean(m.groupValues[1]), clean(m.groupValues[2]), mode(t))
        }
        Regex("^(?:when|what time) (?:will|can|do) (?:i|we) (?:reach|get to|arrive at|arrive in) (.+)$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.TIME, clean(m.groupValues[1]), "", mode(t))
        }

        // best route / shortcut
        Regex("^(?:(?:find|show|get|give)(?: me)?(?: the| a)?\\s*)?(?:best|fastest|shortest|quickest|easiest|nearest|cheapest)?\\s*(?:route|way|path|shortcut|directions?|road)(?: to go| to get| to reach)?(?: from (.+?))? (?:to|for|near) (.+)$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.ROUTE, clean(m.groupValues[2]), clean(m.groupValues[1]), mode(t))
        }
        Regex("^how (?:do i|can i|to|should i) (?:get|go|reach|drive|walk) (?:to )?(.+?)(?: from (.+))?$", I).find(t)?.let { m ->
            return Command.Nav(NavOp.ROUTE, clean(m.groupValues[1]), clean(m.groupValues[2]), mode(t))
        }

        // go there with an app
        Regex("^(?:navigate|take me|drive me|guide me|start navigation|start navigating|directions|go|head|route|lead me|show me the way)(?: me)?(?: to| towards)? (.+?)(?:\\s+(?:using|with|on|via|in|through)\\s+(?:the\\s+)?(.+?))?(?: app)?$", I).find(t)?.let { m ->
            val to = clean(m.groupValues[1])
            val app = m.groupValues[2].trim().ifEmpty { null }
            val appLike = app?.let { !Regex("^(?:foot|car|bike|bus|train|walking|driving)$", I).matches(it) } ?: true
            if (to.isNotEmpty() && Regex("^(?:navigate|take me|drive me|guide me|start navigat|directions|head|route|lead me|show me the way)", I).containsMatchIn(t) || Regex("^go (?:to )", I).containsMatchIn(t) && to.length > 2) {
                if (!Regex("^(?:back|home screen|to sleep|ahead|on|off|down|up|away)$", I).matches(to) && !Regex("^(?:the )?(?:settings|home screen|camera|bluetooth|wifi|wi-fi|back)\\b", I).containsMatchIn(to)) {
                    return Command.Nav(NavOp.GO, to, "", mode(t), if (appLike) app else null)
                }
            }
        }
        return null
    }
}
