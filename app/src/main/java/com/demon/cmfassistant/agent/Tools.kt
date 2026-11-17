package com.demon.cmfassistant.agent

import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.IntentEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's abilities written as tools (name, description, typed arguments).
 * The same list feeds Needle 3 (on-device) or an AI provider's function calling. A tool call becomes a plain
 * spoken sentence that the normal command parser already understands, so there is one source of truth for behaviour.
 */
object Tools {
    class Arg(val name: String, val type: String, val desc: String, val required: Boolean = true, val enum: List<String>? = null)
    class Tool(val name: String, val desc: String, val args: List<Arg>, val say: (JSONObject) -> String?)

    private fun s(o: JSONObject, k: String) = o.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }

    val all: List<Tool> = listOf(
        Tool("open_app", "Open an app by name.", listOf(Arg("name", "string", "App name"))) { a -> s(a, "name")?.let { "open $it" } },
        Tool("play_music", "Play a song or any music, optionally in a named app.",
            listOf(Arg("query", "string", "Song, artist or empty for random", false), Arg("app", "string", "App name", false))) { a ->
            val q = s(a, "query"); val app = s(a, "app")
            when { q != null && app != null -> "play $q on $app"; q != null -> "play $q"; app != null -> "play a random song on $app"; else -> "play a song" }
        },
        Tool("play_video", "Play a video, optionally searching for a title in an app.",
            listOf(Arg("query", "string", "Video title or empty", false), Arg("app", "string", "App name", false))) { a ->
            val q = s(a, "query"); val app = s(a, "app")
            when { q != null && app != null -> "play $q video on $app"; q != null -> "play $q video"; else -> "play a video" }
        },
        Tool("search_in_app", "Search for something inside a specific app.", listOf(Arg("app", "string", "App name"), Arg("query", "string", "What to search"))) { a ->
            val app = s(a, "app"); val q = s(a, "query"); if (app != null && q != null) "search $q on $app" else null
        },
        Tool("search_web", "Search the web for something.", listOf(Arg("query", "string", "What to search"))) { a -> s(a, "query")?.let { "search $it on google" } },
        Tool("navigate_to", "Start navigation to a place, optionally with a navigation app such as Waze or Google Maps.",
            listOf(Arg("destination", "string", "Place"), Arg("app", "string", "Navigation app", false))) { a ->
            val d = s(a, "destination"); val app = s(a, "app")
            if (d == null) null else if (app != null) "navigate to $d with $app" else "navigate to $d"
        },
        Tool("find_nearby", "Find places of a kind near the user or near another place.",
            listOf(Arg("category", "string", "hotels, parks, schools, markets, pharmacies, petrol pumps..."), Arg("near", "string", "Place, empty for near me", false))) { a ->
            val c = s(a, "category"); val n = s(a, "near")
            if (c == null) null else if (n != null) "find $c near $n" else "find $c near me"
        },
        Tool("travel_info", "Distance, travel time or best route to a place.",
            listOf(Arg("destination", "string", "Place"), Arg("what", "string", "distance, time or route", true, listOf("distance", "time", "route")),
                Arg("origin", "string", "Start, empty for here", false), Arg("mode", "string", "driving, walking or cycling", false, listOf("driving", "walking", "cycling")))) { a ->
            val d = s(a, "destination"); val o = s(a, "origin")
            val mode = when (s(a, "mode")) { "walking" -> " on foot"; "cycling" -> " by bike"; else -> "" }
            when (s(a, "what")) {
                "distance" -> if (d != null) "how far is $d" + (o?.let { " from $it" } ?: "") + mode else null
                "route" -> if (d != null) "best route to $d" + (o?.let { " from $it" } ?: "") + mode else null
                else -> if (d != null) "how long to reach $d" + (o?.let { " from $it" } ?: "") + mode else null
            }
        },
        Tool("call_contact", "Call a contact or phone number.", listOf(Arg("contact", "string", "Name or number"))) { a -> s(a, "contact")?.let { "call $it" } },
        Tool("send_message", "Send a text message to a contact, optionally through a messaging app (WhatsApp, Telegram...). Leave the message empty to dictate it.",
            listOf(Arg("contact", "string", "Name"), Arg("message", "string", "Text", false), Arg("app", "string", "Messaging app", false))) { a ->
            val c = s(a, "contact"); val m = s(a, "message"); val app = s(a, "app")
            when {
                c == null -> null
                m != null && app != null -> "send $m to $c on $app"
                m != null -> "send a text to $c saying $m"
                else -> "send a message to $c" + (app?.let { " on $it" } ?: "")
            }
        },
        Tool("set_timer", "Start a countdown timer.", listOf(Arg("seconds", "integer", "Length in seconds"))) { a ->
            a.optInt("seconds", 0).takeIf { it > 0 }?.let { "set a timer for $it seconds" }
        },
        Tool("set_alarm", "Set an alarm for a clock time (24-hour).", listOf(Arg("hour", "integer", "0-23"), Arg("minute", "integer", "0-59", false))) { a ->
            if (!a.has("hour")) null else "set an alarm for %d:%02d".format(a.optInt("hour"), a.optInt("minute", 0))
        },
        Tool("switch_setting", "Turn a phone setting on or off.",
            listOf(Arg("setting", "string", "wifi, bluetooth, mobile data, airplane mode, location, nfc, flashlight, do not disturb, dark mode, battery saver, hotspot", true),
                Arg("on", "boolean", "true for on, false for off"))) { a ->
            s(a, "setting")?.let { "turn ${if (a.optBoolean("on", true)) "on" else "off"} $it" }
        },
        Tool("set_level", "Set volume or brightness to a percent.", listOf(Arg("what", "string", "volume or brightness", true, listOf("volume", "brightness")), Arg("percent", "integer", "0-100"))) { a ->
            s(a, "what")?.let { "set $it to ${a.optInt("percent", 50)} percent" }
        },
        Tool("get_weather", "Weather now or tomorrow, for a place or here.", listOf(Arg("place", "string", "City", false), Arg("tomorrow", "boolean", "Tomorrow instead of now", false))) { a ->
            val p = s(a, "place"); if (a.optBoolean("tomorrow", false)) "weather tomorrow" + (p?.let { " in $it" } ?: "") else "weather" + (p?.let { " in $it" } ?: "")
        },
        Tool("get_news", "Read the latest news headlines, optionally about a topic.", listOf(Arg("topic", "string", "Topic", false))) { a -> s(a, "topic")?.let { "latest news about $it" } ?: "any new news" },
        Tool("tap_on_screen", "Tap, click, long-press or open something visible on the current screen.", listOf(Arg("target", "string", "What to tap, by its label"))) { a -> s(a, "target")?.let { "click on $it" } },
        Tool("type_text", "Type text into the field on screen.", listOf(Arg("text", "string", "Text"))) { a -> s(a, "text")?.let { "type $it" } },
        Tool("scroll_screen", "Scroll the current screen.", listOf(Arg("direction", "string", "up, down, left or right", true, listOf("up", "down", "left", "right")))) { a -> "scroll ${s(a, "direction") ?: "down"}" },
        Tool("go_back", "Go back.", emptyList()) { "go back" },
        Tool("go_home", "Go to the home screen.", emptyList()) { "go home" },
        Tool("keep_screen_awake", "Keep the screen on for some minutes.", listOf(Arg("minutes", "integer", "Minutes"))) { a -> "keep the screen awake for ${a.optInt("minutes", 60)} minutes" },
        Tool("schedule_task", "Run another command later or repeatedly, for example every day at 10 am.", listOf(Arg("command", "string", "What to do, in plain words"), Arg("when", "string", "every day at 10 am, tomorrow at 7, only on Sunday..."))) { a ->
            val c = s(a, "command"); val w = s(a, "when"); if (c != null && w != null) "$c $w" else null
        },
        Tool("do_in_apps", "Complete a longer job across apps by navigating their screens: order food, book a ride, reserve a table. Stops before paying.",
            listOf(Arg("goal", "string", "The whole job in plain words"))) { a -> s(a, "goal")?.let { "do this in the apps: $it" } },
        Tool("system_task", "Deep system job: audit location permissions, free up storage, clear app cache, turn animations off.",
            listOf(Arg("task", "string", "audit location permissions, storage left, clear cache, animations off", true))) { a ->
            when (s(a, "task")?.lowercase()) {
                null -> null
                else -> s(a, "task")
            }
        }
    )

    /** JSON the on-device engine reads: [{name, description, parameters}]. */
    fun schemaJson(): String {
        val arr = JSONArray()
        for (t in all) {
            val props = JSONObject()
            val req = JSONArray()
            for (a in t.args) {
                val p = JSONObject().put("type", a.type).put("description", a.desc)
                a.enum?.let { p.put("enum", JSONArray(it)) }
                props.put(a.name, p)
                if (a.required) req.put(a.name)
            }
            val params = JSONObject().put("type", "object").put("properties", props)
            if (req.length() > 0) params.put("required", req)
            arr.put(JSONObject().put("name", t.name).put("description", t.desc).put("parameters", params))
        }
        return arr.toString()
    }

    /** A line-per-tool description for chat models that don't have native function calling. */
    fun promptList(): String = all.joinToString("\n") { t ->
        "- ${t.name}(" + t.args.joinToString(", ") { it.name + (if (it.required) "" else "?") + ": " + it.type } + "): " + t.desc
    }

    /** Turns `{name, arguments}` into a command, or null if it isn't one of ours or lacks what it needs. */
    fun toCommand(name: String, args: JSONObject): Command? {
        val tool = all.firstOrNull { it.name == name } ?: return null
        val sentence = tool.say(args) ?: return null
        val c = IntentEngine.parse(sentence)
        return if (c is Command.Unknown || c is Command.Question) null else c
    }
}
