package com.demon.cmfassistant.action

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Quick answers from the web without an AI: Google News headlines, DuckDuckGo and Wikipedia. */
object WebInfo {
    private fun get(url: String, timeout: Int = 6000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) CmfAssistant/1.0")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun unescape(s: String): String = s
        .replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    class Headline(val title: String, val source: String)

    private fun feed(url: String): List<Headline> {
        val xml = get(url, 7000)
        val out = ArrayList<Headline>()
        for (m in Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val item = m.groupValues[1]
            var title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::unescape) ?: continue
            var source = Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL).find(item)?.groupValues?.get(1)?.let(::unescape).orEmpty()
            if (source.isNotEmpty() && title.endsWith(" - $source")) title = title.removeSuffix(" - $source")
            else if (source.isEmpty() && title.contains(" - ")) { source = title.substringAfterLast(" - "); title = title.substringBeforeLast(" - ") }
            out.add(Headline(title.trim(), source.trim()))
        }
        return out
    }

    /** Different stories, not the same one from ten outlets. */
    private fun distinct(list: List<Headline>, n: Int): List<Headline> {
        val out = ArrayList<Headline>()
        val seen = ArrayList<Set<String>>()
        for (h in list) {
            val words = h.title.lowercase(Locale.ROOT).split(Regex("\\W+")).filter { it.length > 3 }.toSet()
            if (words.isEmpty()) continue
            if (seen.any { o -> val common = o.intersect(words).size; common.toDouble() / minOf(o.size, words.size) > 0.55 }) continue
            seen.add(words)
            out.add(h)
            if (out.size >= n) break
        }
        return out
    }

    /** A spoken briefing of up to [count] headlines, about [topic] when given. */
    suspend fun news(topic: String, count: Int = 10): String = withContext(Dispatchers.IO) {
        val region = "hl=en-IN&gl=IN&ceid=IN:en"
        val url = if (topic.isBlank()) "https://news.google.com/rss?$region"
        else "https://news.google.com/rss/search?q=${URLEncoder.encode(topic, "UTF-8")}&$region"
        val list = runCatching { feed(url) }.getOrElse {
            return@withContext "I couldn't reach the news right now. Check your internet and try again."
        }
        val picks = distinct(list, count)
        if (picks.isEmpty()) return@withContext if (topic.isBlank()) "I couldn't find any headlines right now." else "I couldn't find any news about $topic."
        val head = if (topic.isBlank()) "Here are the top ${picks.size} headlines." else "Here's the latest on $topic."
        head + " " + picks.mapIndexed { i, h -> "${i + 1}. ${h.title.trimEnd('.')}" + if (h.source.isNotEmpty()) ", ${h.source}." else "." }.joinToString(" ")
    }

    private fun firstSentences(t: String, n: Int, max: Int = 360): String {
        val parts = t.replace(Regex("\\s*\\([^)]*\\)"), "").split(Regex("(?<=[.!?])\\s+(?=[A-Z])"))
        var out = ""
        for (p in parts.take(n)) { if ((out + p).length > max && out.isNotEmpty()) break; out += (if (out.isEmpty()) "" else " ") + p }
        return out.ifEmpty { t.take(max) }
    }

    private fun clean(q: String): String = q.trim().trimEnd('?', '.', '!')
        .replace(Regex("^(?:hey |ok |please )+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(?:what(?:'s| is| are| was| were)|who(?:'s| is| was| are)|where(?:'s| is)|when (?:is|was|did)|tell me about|tell me|explain|define|meaning of|what does .+ mean|about|can you tell me|do you know|i want to know|search for|look up|google)\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(?:a |an |the )", RegexOption.IGNORE_CASE), "").trim()

    /** A short factual answer for [question], or null when nothing good turns up. */
    suspend fun answer(question: String): String? = withContext(Dispatchers.IO) {
        val q = clean(question).ifBlank { question }
        coroutineScope {
            val ddg = async { runCatching { ddg(question, q) }.getOrNull() }
            val wiki = async { runCatching { wiki(q) }.getOrNull() }
            ddg.await()?.takeIf { it.isNotBlank() } ?: wiki.await()
        }
    }

    private fun ddg(original: String, q: String): String? {
        val j = JSONObject(get("https://api.duckduckgo.com/?q=${URLEncoder.encode(original, "UTF-8")}&format=json&no_html=1&skip_disambig=1&no_redirect=1"))
        val answer = j.optString("Answer").trim()
        if (answer.isNotEmpty()) return answer
        val abs = j.optString("AbstractText").trim()
        if (abs.isNotEmpty()) return firstSentences(abs, 2)
        return null
    }

    private fun wiki(q: String): String? {
        val s = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${URLEncoder.encode(q, "UTF-8")}&format=json&srlimit=1"))
        val hit = s.getJSONObject("query").getJSONArray("search").optJSONObject(0) ?: return null
        val title = hit.getString("title")
        val sum = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/${URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")}"))
        if (sum.optString("type") == "disambiguation") return null
        val ex = sum.optString("extract").trim()
        return if (ex.isEmpty()) null else firstSentences(ex, 2)
    }
}
