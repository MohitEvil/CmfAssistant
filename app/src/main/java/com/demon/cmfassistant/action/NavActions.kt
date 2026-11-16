package com.demon.cmfassistant.action

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.demon.cmfassistant.intent.Command
import com.demon.cmfassistant.intent.NavOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Navigation and map control: start navigating, find places nearby, distances, travel times and routes. */
class NavActions(
    private val context: Context,
    private val resolveApp: (String) -> String?,
    private val launch: (Intent) -> Boolean
) {
    private val geo = Geo(context)

    private val categoryTerm = mapOf(
        "pumps" to "fuel", "pump" to "fuel", "petrol pump" to "fuel", "petrol pumps" to "fuel", "petrol station" to "fuel", "petrol stations" to "fuel",
        "gas station" to "fuel", "gas stations" to "fuel", "fuel station" to "fuel", "fuel stations" to "fuel",
        "medical store" to "pharmacy", "medical stores" to "pharmacy", "chemist" to "pharmacy", "chemists" to "pharmacy",
        "kirana" to "convenience store", "dhaba" to "restaurant", "dhabas" to "restaurant", "food" to "restaurant", "places to eat" to "restaurant",
        "doctor" to "clinic", "doctors" to "clinic", "toilet" to "toilets", "restroom" to "toilets", "restrooms" to "toilets", "washroom" to "toilets", "washrooms" to "toilets",
        "movie theater" to "cinema", "movie theaters" to "cinema", "theatre" to "cinema", "theatres" to "cinema", "tourist places" to "tourist attraction",
        "tourist spots" to "tourist attraction", "attractions" to "tourist attraction", "police" to "police station", "water" to "drinking water",
        "mechanic" to "car repair", "mechanics" to "car repair", "garage" to "car repair", "garages" to "car repair",
        "taxi stand" to "taxi", "taxi stands" to "taxi", "mobile shops" to "mobile phone shop", "mobile shop" to "mobile phone shop"
    )

    private fun term(c: String) = categoryTerm[c.lowercase()] ?: c.lowercase().removeSuffix("s").ifEmpty { c }

    private fun navPkgFor(app: String?): String? = app?.let { resolveApp(it) }

    private fun mapsIntent(uri: String, pkg: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply { if (pkg != null) setPackage(pkg) }

    private fun isWaze(app: String?, pkg: String?) = app?.contains("waze", true) == true || pkg?.contains("waze") == true

    private fun gmModeCode(m: String) = when (m) { "walking" -> "w"; "cycling" -> "b"; else -> "d" }
    private fun gmModeWord(m: String) = when (m) { "walking" -> "walking"; "cycling" -> "bicycling"; "transit" -> "transit"; else -> "driving" }

    private fun directionsIntent(dest: Geo.Pt?, destName: String, origin: Geo.Pt?, mode: String, app: String?): Intent {
        val pkg = navPkgFor(app)
        val q = dest?.let { "${it.lat},${it.lon}" } ?: Uri.encode(destName)
        if (isWaze(app, pkg) || (app == null && pkg == null && false)) {
            val u = if (dest != null) "waze://?ll=${dest.lat},${dest.lon}&navigate=yes" else "waze://?q=${Uri.encode(destName)}&navigate=yes"
            return Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply { pkg?.let { setPackage(it) } }
        }
        if (pkg != null && !pkg.contains("google.android.apps.maps")) {
            // other navigation apps (Here, Sygic, OsmAnd, Magic Earth...) understand geo: with a query
            val g = if (dest != null) "geo:${dest.lat},${dest.lon}?q=${dest.lat},${dest.lon}(${Uri.encode(destName)})" else "geo:0,0?q=${Uri.encode(destName)}"
            return mapsIntent(g, pkg)
        }
        val gmPkg = pkg ?: if (isInstalled("com.google.android.apps.maps")) "com.google.android.apps.maps" else null
        if (gmPkg != null) {
            return if (origin == null || origin.exact || mode == "transit")
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q&mode=${gmModeCode(mode)}")).setPackage(gmPkg)
            else mapsIntent("https://www.google.com/maps/dir/?api=1&origin=${origin.lat},${origin.lon}&destination=$q&travelmode=${gmModeWord(mode)}", gmPkg)
        }
        val web = "https://www.google.com/maps/dir/?api=1&destination=$q&travelmode=${gmModeWord(mode)}" +
            (origin?.let { "&origin=${it.lat},${it.lon}" } ?: "")
        return mapsIntent(web)
    }

    private fun isInstalled(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    suspend fun run(c: Command.Nav): ActionResult = withContext(Dispatchers.IO) {
        runCatching { runInner(c) }.getOrElse {
            ActionResult.Failure(if (it is java.io.IOException) "I couldn't reach the map service. Check the internet and try again." else "Something went wrong with the map lookup.")
        }
    }

    private fun runInner(c: Command.Nav): ActionResult {
        return when (c.op) {
            NavOp.GO -> go(c)
            NavOp.NEARBY -> nearby(c)
            NavOp.DISTANCE -> between(c, wantTime = false)
            NavOp.TIME -> between(c, wantTime = true)
            NavOp.ROUTE -> route(c)
        }
    }

    private fun go(c: Command.Nav): ActionResult {
        val pkg = navPkgFor(c.app)
        if (c.app != null && pkg == null) return ActionResult.Failure("I couldn't find ${c.app} on this phone.")
        val dest = runCatching { geo.place(c.to, geo.here()) }.getOrNull()
        val label = dest?.name?.takeIf { it.isNotBlank() } ?: c.to
        val intent = directionsIntent(dest, c.to, null, c.mode, c.app)
        val via = c.app?.let { " with ${it.replaceFirstChar { ch -> ch.uppercase() }}" }.orEmpty()
        val modeWord = when (c.mode) { "walking" -> "Walking directions"; "cycling" -> "Cycling directions"; "transit" -> "Transit directions"; else -> "Navigating" }
        return ActionResult.Success("$modeWord to $label$via.") {
            if (!launch(intent)) launch(mapsIntent("geo:0,0?q=" + Uri.encode(c.to)))
        }
    }

    private fun nearby(c: Command.Nav): ActionResult {
        val found = if (c.from.isBlank()) geo.here() else (geo.place(c.from, geo.here()) ?: return ActionResult.Failure("I couldn't find ${c.from}."))
        val centre = found ?: return ActionResult.Failure("I couldn't work out where you are. Allow Location for me, or say a place, like hotels near Connaught Place.")
        val t = term(c.to)
        val list = geo.nearby(t, centre, 5)
        val areaText = if (c.from.isBlank()) "near you" else "near ${c.from}"
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${centre.lat},${centre.lon}?q=" + Uri.encode(t)))
        if (list.isEmpty()) {
            return ActionResult.Success("I didn't find any ${c.to} $areaText. Opening the map to search wider.") { launch(open) }
        }
        val approx = if (c.from.isBlank() && !centre.exact) " Location isn't allowed, so this is based on your network's rough area." else ""
        val spoken = list.take(3).joinToString("; ") { "${it.name.ifBlank { c.to }}, ${Geo.distanceText(it.meters)} away" }
        return ActionResult.Success("Closest ${c.to} $areaText: $spoken.$approx") { launch(open) }
    }

    private fun resolveEnds(c: Command.Nav): Pair<Geo.Pt, Geo.Pt>? {
        val here = geo.here()
        val a = if (c.from.isBlank()) here else geo.place(c.from, here)
        val b = geo.place(c.to, a ?: here)
        return if (a != null && b != null) a to b else null
    }

    private fun modeWord(m: String) = when (m) { "walking" -> "on foot"; "cycling" -> "by bike"; "transit" -> "by road"; else -> "by road" }

    private fun between(c: Command.Nav, wantTime: Boolean): ActionResult {
        val (a, b) = resolveEnds(c) ?: return ActionResult.Failure("I couldn't find ${if (c.from.isBlank()) c.to else "${c.from} or ${c.to}"}. Try the full place name.")
        val mode = if (c.mode == "transit") "driving" else c.mode
        val leg = runCatching { geo.route(a, b, mode) }.getOrNull()
        val from = if (c.from.isBlank()) "here" else a.name
        val spoken = if (leg == null) {
            val d = geo.straight(a, b)
            "It's about ${Geo.distanceText(d)} in a straight line from $from to ${b.name}."
        } else if (wantTime) {
            "It'll take about ${Geo.durationText(leg.seconds)} ${modeWord(c.mode)} to ${b.name}, ${Geo.distanceText(leg.meters)} from $from."
        } else {
            "${b.name} is ${Geo.distanceText(leg.meters)} from $from ${modeWord(c.mode)}, about ${Geo.durationText(leg.seconds)}."
        }
        val extra = if (c.mode == "transit") " That's the road estimate; bus and train times can differ." else ""
        return ActionResult.Success(spoken + extra)
    }

    private fun route(c: Command.Nav): ActionResult {
        val (a, b) = resolveEnds(c) ?: return ActionResult.Failure("I couldn't find ${c.to}. Try the full place name.")
        val mode = if (c.mode == "transit") "driving" else c.mode
        val leg = runCatching { geo.route(a, b, mode) }.getOrNull()
        val intent = directionsIntent(b, b.name, if (c.from.isBlank()) a else a, c.mode, c.app)
        val sum = leg?.let { "${Geo.distanceText(it.meters)}, about ${Geo.durationText(it.seconds)}" }
        val msg = if (sum != null) "Best route to ${b.name}: $sum ${modeWord(c.mode)}. Opening it on the map." else "Opening the route to ${b.name}."
        return ActionResult.Success(msg) { launch(intent) }
    }
}
