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

    private val stop = setOf("a", "an", "the", "is", "are", "was", "were", "of", "in", "on", "at", "to", "for", "and", "or", "what", "whats", "who", "whom", "which",
        "when", "where", "why", "how", "does", "do", "did", "me", "tell", "about", "please", "can", "you", "i", "my", "its", "it", "this", "that", "there", "with", "by", "s", "be", "as", "from", "know", "want", "explain")

    private fun stem(w: String): String {
        var x = w.lowercase()
        if (x.length > 4 && x.endsWith("ies")) x = x.dropLast(3) + "y"
        else if (x.length > 3 && x.endsWith("s") && !x.endsWith("ss")) x = x.dropLast(1)
        if (x.length > 5 && x.endsWith("ian")) x = x.dropLast(3)
        if (x.length > 4 && x.endsWith("an") && x != "japan") x = x.dropLast(2)
        return x
    }

    private fun tokens(q: String): List<String> = q.lowercase().replace("'", "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    private fun keywords(q: String): List<String> = tokens(q).filter { it !in stop && (it.length > 1 || it[0].isDigit()) }.map(::stem).distinct()

    /** "and its value?" after "what's the speed of light" becomes "speed of light value". Only chat turns count, not phone jobs. */
    fun standalone(question: String, lastQuestion: String?): String {
        val q = question.trim().trimEnd('?', '.', '!')
        val prev = lastQuestion?.let { clean(it) }?.takeIf { it.isNotBlank() } ?: return q
        val ql = q.lowercase().replace("’", "'")
        val pronoun = Regex("\\b(it|its|it's|that|this|those|these|they|their|them|he|his|him|she|her|there)\\b")
        val connector = Regex("^(?:and|also|then|so|but|what about|how about|what else|why|how come|and what|and how|and why|more|tell me more|explain more)\\b")
        val own = keywords(q)
        val shortAsk = ql.split(Regex("\\s+")).size <= 4 && !Regex("\\b(of|in|for|about|between)\\b").containsMatchIn(ql)
        val follow = pronoun.containsMatchIn(ql) || connector.containsMatchIn(ql) || own.size <= 1 || (shortAsk && own.size <= 2)
        if (!follow) return q
        // Already mentions the earlier subject? Then it stands on its own.
        val pk = keywords(prev)
        if (pk.isNotEmpty() && own.count { it in pk } >= minOf(2, pk.size) && own.size > pk.size) return q
        val stripped = ql.replace(Regex("^(?:and|also|then|so|but|what about|how about|and what about)\\s+"), "")
        return if (pronoun.containsMatchIn(stripped)) stripped.replace(pronoun, prev) else "$prev $stripped"
    }

    private val wantsNumber = Regex("\\b(value|number|speed|height|weight|population|price|cost|how (?:many|much|far|tall|long|old|fast|big|hot|heavy)|distance|age|year|date|when|size|area|length|temperature|percent|percentage|rate|count|total|born|died|founded)\\b")

    private fun sentences(t: String): List<String> =
        t.replace(Regex("\\s*\\[[^\\]]*]"), "").replace(Regex("\\s*\\([^)]*\\)"), "").split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"'])")).map { it.trim() }.filter { it.length > 15 }

    private class Cand(val text: String, val score: Double)

    private fun rank(text: String, kws: List<String>, nums: List<String>, number: Boolean, bonus: Double): Cand? {
        val low = tokens(text).map(::stem).toSet()
        if (kws.isEmpty()) return null
        var hit = kws.count { it in low || text.lowercase().contains(it) }.toDouble() / kws.size
        if (nums.isNotEmpty() && !nums.all { text.contains(it) }) hit *= 0.25
        if (number && Regex("\\d").containsMatchIn(text)) hit += 0.3
        if (text.length > 420) hit -= 0.1
        return Cand(text, hit + bonus)
    }

    /** A short factual answer for [question], or null when nothing good turns up. [lastQuestion] resolves follow-ups. */
    suspend fun answer(question: String, lastQuestion: String? = null): String? = withContext(Dispatchers.IO) {
        val full = standalone(question, lastQuestion)
        val q = clean(full).ifBlank { full }
        val kws = keywords(q)
        val nums = Regex("\\d+").findAll(q).map { it.value }.toList()
        val number = wantsNumber.containsMatchIn(q.lowercase())
        val pool = ArrayList<Cand>()
        coroutineScope {
            val a = async { runCatching { ddgInstant(full) }.getOrNull().orEmpty() }
            val b = async { runCatching { ddgHtml(full) }.getOrNull().orEmpty() }
            val c = async { runCatching { wikiSentences(q, kws, nums) }.getOrNull().orEmpty() }
            val d = async { runCatching { bingSnippets(full) }.getOrNull().orEmpty() }
            for (t in a.await()) rank(t, kws, nums, number, 0.15)?.let(pool::add)
            for (t in b.await()) rank(t, kws, nums, number, 0.1)?.let(pool::add)
            for (t in c.await()) rank(t, kws, nums, number, 0.05)?.let(pool::add)
            for (t in d.await()) rank(t, kws, nums, number, 0.08)?.let(pool::add)
        }
        val best = pool.maxByOrNull { it.score } ?: return@withContext null
        if (best.score < 0.45) return@withContext null
        shorten(best.text)
    }

    private fun shorten(t: String): String {
        val parts = sentences(t)
        if (parts.size <= 1) return t.take(380)
        var out = ""
        for (p in parts) { if ((out + p).length > 330 && out.isNotEmpty()) break; out += (if (out.isEmpty()) "" else " ") + p; if (out.length > 200) break }
        return out.ifEmpty { t.take(380) }
    }

    private fun ddgInstant(q: String): List<String> {
        val j = JSONObject(get("https://api.duckduckgo.com/?q=${URLEncoder.encode(q, "UTF-8")}&format=json&no_html=1&skip_disambig=1&no_redirect=1"))
        val out = ArrayList<String>()
        j.optString("Answer").trim().takeIf { it.isNotEmpty() }?.let(out::add)
        j.optString("Definition").trim().takeIf { it.isNotEmpty() }?.let(out::add)
        j.optString("AbstractText").trim().takeIf { it.isNotEmpty() }?.let { out.addAll(sentences(it).ifEmpty { listOf(it) }) }
        return out
    }

    private fun ddgHtml(q: String): List<String> {
        val html = get("https://html.duckduckgo.com/html/?q=${URLEncoder.encode(q, "UTF-8")}", 7000)
        val out = ArrayList<String>()
        for (m in Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val t = unescape(m.groupValues[1])
            if (t.length > 25) out.add(t)
            if (out.size >= 6) break
        }
        return out
    }

    private fun bingSnippets(q: String): List<String> {
        val html = get("https://www.bing.com/search?q=${URLEncoder.encode(q, "UTF-8")}&setlang=en&cc=IN", 7000)
        val out = ArrayList<String>()
        for (m in Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val p = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(m.value)?.groupValues?.get(1) ?: continue
            val t = unescape(p).replace(Regex("^[A-Z][a-z]{2} \\d{1,2}, \\d{4}\\s*[·\\-–]\\s*"), "")
            if (t.length > 25) out.add(t)
            if (out.size >= 5) break
        }
        return out
    }

    /** Looks at the top five Wikipedia matches, keeps the one whose title and snippet fit every key word and number, and returns its intro sentences. */
    private fun wikiSentences(q: String, kws: List<String>, nums: List<String>): List<String> {
        val s = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${URLEncoder.encode(q, "UTF-8")}&format=json&srlimit=5"))
        val arr = s.getJSONObject("query").getJSONArray("search")
        var bestTitle: String? = null
        var bestScore = -1.0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.getString("title")
            val hay = (title + " " + unescape(o.optString("snippet"))).lowercase()
            var sc = kws.count { hay.contains(it) }.toDouble() / maxOf(1, kws.size)
            if (kws.any { title.lowercase().contains(it) }) sc += 0.2
            if (nums.isNotEmpty() && !nums.all { hay.contains(it) }) sc -= 0.6
            sc -= i * 0.02
            if (sc > bestScore) { bestScore = sc; bestTitle = title }
        }
        val title = bestTitle ?: return emptyList()
        val r = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&exsentences=14&titles=${URLEncoder.encode(title, "UTF-8")}&format=json"))
        val pages = r.getJSONObject("query").getJSONObject("pages")
        val page = pages.getJSONObject(pages.keys().next())
        val text = page.optString("extract").trim()
        return if (text.isEmpty()) emptyList() else sentences(text)
    }
}
