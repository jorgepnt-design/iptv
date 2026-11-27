package com.jorgepnt.pintotv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val epgId: String?,
    val number: Int
)

data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

data class Playlist(
    val id: String,
    val name: String,
    val type: String, // "m3u" oder "xtream"
    val url: String,
    val server: String,
    val user: String,
    val pass: String,
    val epgUrl: String
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("type", type).put("url", url)
        .put("server", server).put("user", user).put("pass", pass).put("epgUrl", epgUrl)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            o.optString("id"), o.optString("name"), o.optString("type", "m3u"), o.optString("url"),
            o.optString("server"), o.optString("user"), o.optString("pass"), o.optString("epgUrl")
        )
    }
}

fun favKey(c: Channel) = c.name.trim().lowercase()

/** Land aus Gruppennamen wie „DE | Sport“, „|PT| Desporto“, „[UK] News“, „FR: Cinéma“ */
object Countries {
    // Ländercode am Anfang, gefolgt von einem beliebigen Trennzeichen: „DE |“, „TR ✪“, „PT:“, „[UK]“
    private val prefix = Regex("^[\\s|\\[(★✪•]*([A-Za-z]{2,3})\\s*[^\\p{L}\\p{N}\\s]")
    private val names = mapOf(
        "DE" to "Deutschland", "GER" to "Deutschland", "AT" to "Österreich", "CH" to "Schweiz", "PT" to "Portugal",
        "BR" to "Brasilien", "ES" to "Spanien", "FR" to "Frankreich", "IT" to "Italien", "UK" to "Großbritannien",
        "GB" to "Großbritannien", "US" to "USA", "USA" to "USA", "NL" to "Niederlande", "BE" to "Belgien",
        "PL" to "Polen", "TR" to "Türkei", "GR" to "Griechenland", "RO" to "Rumänien", "AR" to "Arabisch",
        "RU" to "Russland", "HR" to "Kroatien", "RS" to "Serbien", "AL" to "Albanien", "EX" to "Ex-Jugoslawien",
        "IN" to "Indien", "PK" to "Pakistan", "SE" to "Schweden", "NO" to "Norwegen", "DK" to "Dänemark",
        "FI" to "Finnland", "CZ" to "Tschechien", "HU" to "Ungarn", "BG" to "Bulgarien", "CA" to "Kanada",
        "MX" to "Mexiko", "LAT" to "Lateinamerika", "AF" to "Afrika", "KU" to "Kurdisch", "IR" to "Iran",
        "IL" to "Israel", "UA" to "Ukraine", "BA" to "Bosnien", "MK" to "Nordmazedonien", "SI" to "Slowenien"
    )
    private val words = listOf(
        "deutsch" to "DE", "germany" to "DE", "german" to "DE", "portug" to "PT", "brasil" to "BR", "brazil" to "BR",
        "españa" to "ES", "spain" to "ES", "france" to "FR", "français" to "FR", "francais" to "FR", "italia" to "IT", "italy" to "IT", "turk" to "TR",
        "türk" to "TR", "poland" to "PL", "polska" to "PL", "österreich" to "AT", "austria" to "AT", "schweiz" to "CH",
        "swiss" to "CH", "england" to "UK", "united kingdom" to "UK", "arab" to "AR"
    )

    /** Erwachsenen-Inhalte erkennen – eigene „Länder“-Gruppe, damit man sie gezielt ein-/ausblenden kann */
    private val adult = Regex("xxx|adult|18\\+|\\+18|porn|erotik|erotic|for adults|🔞", RegexOption.IGNORE_CASE)

    /** „(S|FR) Française Série“, „[M|DE] Filme“: Ländercode hinter einem Kürzel */
    private val tagged = Regex("^[\\s(\\[]*[A-Za-z]{1,3}\\s*[|/]\\s*([A-Za-z]{2,3})\\s*[)\\]|]")

    private val prefixSpace = Regex("^[\\s|\\[(★✪•]*([A-Z]{2,3})\\s+\\S")

    private fun norm(c: String) = when (c) { "GER" -> "DE"; "GB" -> "UK"; "USA" -> "US"; else -> c }

    /** Flaggen-Emoji wie 🇩🇪 am Anfang */
    private fun flag(g: String): String? {
        val t = g.trimStart()
        if (t.length < 4) return null
        val a = t.codePointAt(0); val b = t.codePointAt(Character.charCount(a))
        if (a !in 0x1F1E6..0x1F1FF || b !in 0x1F1E6..0x1F1FF) return null
        return norm("" + ('A' + (a - 0x1F1E6)) + ('A' + (b - 0x1F1E6)))
    }

    /** Flaggen-Emoji für einen Ländercode (leer, wenn es keine Flagge gibt) */
    fun flagEmoji(code: String): String {
        if (code == "XXX") return "🔞"
        val c = when (code) { "UK" -> "GB"; "EX", "AR", "LAT", "KU", "AF", "?" -> return ""; else -> code }
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    private val strip = Regex("^[\\s|\\[(★✪•]*[A-Za-z]{2,3}(\\s*[^\\p{L}\\p{N}\\s]+\\s*|\\s+)")

    /** Gruppenname ohne Länderkürzel vorne: „DE ✪ SKY SPORT“ → „SKY SPORT“ */
    fun bare(group: String): String {
        val c = code(group)
        if (c == "?" || c == "XXX") return group.trim()
        tagged.find(group)?.let { t -> if (norm(t.groupValues[1].uppercase()) == c) return group.substring(t.range.last + 1).trim().ifEmpty { group.trim() } }
        val m = strip.find(group) ?: return group.trim()
        if (norm(m.value.trim().takeWhile { it.isLetter() }.uppercase()) != c) return group.trim()
        return group.substring(m.range.last + 1).trim().ifEmpty { group.trim() }
    }

    /** Anzeige mit Flagge: „🇩🇪 SKY SPORT“ */
    fun pretty(group: String): String {
        val c = code(group)
        val f = flagEmoji(c)
        if (c == "XXX") return "🔞 " + group.trim()
        if (f.isEmpty()) return group.trim()
        return "$f ${bare(group)}"
    }

    fun code(group: String): String {
        if (adult.containsMatchIn(group)) return "XXX"
        flag(group)?.let { return it }
        // „DE VIP SPORT“, „PT SPORT“: großgeschriebener Ländercode mit Leerzeichen dahinter
        tagged.find(group)?.let { m -> val c = m.groupValues[1].uppercase(); if (c in names) return norm(c) }
        prefixSpace.find(group)?.let { m -> val c = m.groupValues[1]; if (c in names) return norm(c) }
        prefix.find(group)?.let { return norm(it.groupValues[1].uppercase()) }
        val g = group.lowercase()
        words.firstOrNull { g.contains(it.first) }?.let { return it.second }
        return "?"
    }

    fun label(code: String) = when (code) {
        "?" -> "Sonstige / ohne Land"
        "XXX" -> "🔞 Erwachsene (XXX)"
        else -> names[code]?.let { "$it ($code)" } ?: code
    }
}

/** Einstellungen, Playlists, Favoriten, Verlauf */
class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("pintotv", Context.MODE_PRIVATE)

    /** komplette Sicherung aller Einstellungen als JSON */
    fun exportJson(): String {
        val prefs = JSONObject()
        sp.all.forEach { (k, v) ->
            when (v) {
                is String -> prefs.put(k, JSONObject().put("t", "s").put("v", v))
                is Boolean -> prefs.put(k, JSONObject().put("t", "b").put("v", v))
                is Int -> prefs.put(k, JSONObject().put("t", "i").put("v", v))
                is Long -> prefs.put(k, JSONObject().put("t", "l").put("v", v))
                is Float -> prefs.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
                is Set<*> -> prefs.put(k, JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() })))
            }
        }
        return JSONObject().put("pintotv", 1).put("created", System.currentTimeMillis())
            .put("device", android.os.Build.MODEL).put("prefs", prefs).toString()
    }

    /** kurze Beschreibung einer Sicherung für die Rückfrage */
    fun describe(json: String): String = try {
        val prefs = JSONObject(json).getJSONObject("prefs")
        val pl = prefs.optJSONObject("playlists")?.optString("v")?.let { JSONArray(it).length() } ?: 0
        val fl = (prefs.optJSONObject("favlists") ?: prefs.keys().asSequence().firstOrNull { it.startsWith("favlists_") }?.let { prefs.optJSONObject(it) })
            ?.optString("v")?.let { JSONArray(it) }
        val favs = fl?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).getJSONArray("k").length() } } ?: 0
        "$pl Playlist${if (pl == 1) "" else "s"}, ${fl?.length() ?: 1} Favoritenliste(n) mit $favs Sendern, Gruppen-/Länderauswahl und Einstellungen"
    } catch (e: Exception) { "Einstellungen" }

    fun importJson(json: String) {
        val prefs = JSONObject(json).getJSONObject("prefs")
        val e = sp.edit().clear()
        prefs.keys().forEach { k ->
            val o = prefs.getJSONObject(k)
            when (o.getString("t")) {
                "s" -> e.putString(k, o.getString("v"))
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "set" -> { val a = o.getJSONArray("v"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        e.commit()
    }

    fun playlists(): List<Playlist> {
        val a = JSONArray(sp.getString("playlists", "[]"))
        return (0 until a.length()).map { Playlist.fromJson(a.getJSONObject(it)) }
    }

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        sp.edit().putString("playlists", a.toString()).apply()
    }

    var activeId: String?
        get() = sp.getString("active", null)
        set(v) = sp.edit().putString("active", v).apply()

    fun active(): Playlist? = playlists().let { l -> l.firstOrNull { it.id == activeId } ?: l.firstOrNull() }

    // ---------- Favoritenlisten (über den Sendernamen – bleiben bei Neuladen/Formatwechsel erhalten) ----------

    class FavList(val name: String, val keys: MutableSet<String>, var manual: Boolean = false)

    /** Favoritenlisten gehören jetzt zu einer Playlist (Standard: die aktive) */
    private fun favScope(pid: String?) = pid ?: active()?.id ?: ""

    fun favLists(pid: String? = null): MutableList<FavList> {
        val scope = favScope(pid)
        var raw = sp.getString("favlists_$scope", null)
        if (raw == null && scope.isNotEmpty()) {
            migrateGlobalFavs()
            raw = sp.getString("favlists_$scope", null)
        }
        if (raw == null) return mutableListOf(FavList("Favoriten", mutableSetOf()))
        return parseFav(raw)
    }

    private fun parseFav(raw: String): MutableList<FavList> {
        val a = JSONArray(raw)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val k = o.getJSONArray("k")
            FavList(o.getString("n"), (0 until k.length()).map { k.getString(it) }.toMutableSet(), o.optBoolean("m", false))
        }.toMutableList()
    }

    private fun favJson(l: List<FavList>): String {
        val a = JSONArray()
        l.forEach { f -> a.put(JSONObject().put("n", f.name).put("k", JSONArray(f.keys.toList())).put("m", f.manual)) }
        return a.toString()
    }

    fun saveFavLists(l: List<FavList>, pid: String? = null) {
        sp.edit().putString("favlists_${favScope(pid)}", favJson(l)).commit()
    }

    /** Listen aller Playlists, gleichnamige zusammengeführt (zum Senden an andere Geräte) */
    fun allFavLists(): List<FavList> {
        val m = LinkedHashMap<String, FavList>()
        playlists().forEach { p -> favLists(p.id).forEach { f ->
            m.getOrPut(f.name) { FavList(f.name, mutableSetOf(), f.manual) }.keys.addAll(f.keys)
        } }
        return m.values.toList()
    }

    /** Frühere gemeinsame Favoriten (für alle Playlists) der Playlist zuordnen, zu der sie am besten passen.
     *  Die anderen Playlists starten mit leeren Listen. */
    private fun migrateGlobalFavs() {
        val raw = sp.getString("favlists", null)
        val old = if (raw != null) parseFav(raw) else {
            val o = sp.getStringSet("favs", null) ?: return
            mutableListOf(FavList("Favoriten", o.toMutableSet()))
        }
        val keys = old.flatMap { it.keys }.toSet()
        val pls = playlists()
        if (pls.isEmpty()) return
        var best = active()?.id ?: pls.first().id
        var bestHits = -1
        pls.forEach { p ->
            val f = java.io.File(ctx.filesDir, "ch_${p.id}.json")
            val hits = try {
                if (!f.exists()) 0 else {
                    val a = JSONObject(f.readText()).getJSONArray("ch")
                    (0 until a.length()).count { a.getJSONArray(it).getString(0).trim().lowercase() in keys }
                }
            } catch (e: Exception) { 0 }
            if (hits > bestHits) { bestHits = hits; best = p.id }
        }
        sp.edit().putString("favlists_$best", favJson(old)).remove("favlists").remove("favs").commit()
    }

    /** alle Favoriten aus allen Listen (für den Stern in der Liste) */
    fun favorites(): Set<String> = favLists().flatMap { it.keys }.toSet()

    fun isFavorite(c: Channel) = favKey(c) in favorites()

    /** alte Favoriten (gespeichert über die Stream-Adresse) übernehmen */
    fun migrateFavorites(pid: String, channels: List<Channel>) {
        val old = sp.getStringSet("fav_$pid", null) ?: return
        val l = favLists(pid)
        channels.filter { it.url in old }.forEach { l[0].keys.add(favKey(it)) }
        saveFavLists(l, pid)
        sp.edit().remove("fav_$pid").commit()
    }

    var sortByName: Boolean
        get() = sp.getBoolean("sortName", false)
        set(v) = sp.edit().putBoolean("sortName", v).apply()

    /** Wiedergabeposition für Filme/Folgen */
    fun resumePos(url: String): Long = sp.getLong("pos_" + url.hashCode(), 0)
    /** alle Filme mit gemerkter Position (nur die Kennungen – schnell, ohne jeden Film einzeln abzufragen) */
    fun resumeHashes(): Set<Int> = sp.all.keys.asSequence().filter { it.startsWith("pos_") }
        .mapNotNull { it.removePrefix("pos_").toIntOrNull() }.toHashSet()

    fun resumeDur(url: String): Long = sp.getLong("dur_" + url.hashCode(), 0)
    fun setResumePos(url: String, ms: Long, dur: Long) {
        val e = sp.edit()
        if (ms <= 0) e.remove("pos_" + url.hashCode()).remove("dur_" + url.hashCode())
        else e.putLong("pos_" + url.hashCode(), ms).putLong("dur_" + url.hashCode(), dur)
        e.apply()
    }

    /** eigene Reihenfolge der Gruppen (je Playlist) */
    fun groupOrder(pid: String): List<String> = JSONArray(sp.getString("gorder_$pid", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun setGroupOrder(pid: String, l: List<String>) { sp.edit().putString("gorder_$pid", JSONArray(l).toString()).commit() }
    /** Länder zuerst, z. B. ["DE","PT"] */
    fun countryOrder(pid: String): List<String> = JSONArray(sp.getString("corder_$pid", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
    fun setCountryOrder(pid: String, l: List<String>) { sp.edit().putString("corder_$pid", JSONArray(l).toString()).commit() }
    /** eigene Sendernummern (über den Sendernamen) */
    fun customNumbers(pid: String): Map<String, Int> {
        val o = JSONObject(sp.getString("chnum_$pid", "{}"))
        return o.keys().asSequence().associateWith { o.getInt(it) }
    }
    fun setCustomNumber(pid: String, key: String, n: Int?) {
        val o = JSONObject(sp.getString("chnum_$pid", "{}"))
        if (n == null) o.remove(key) else o.put(key, n)
        sp.edit().putString("chnum_$pid", o.toString()).commit()
    }

    /** ausgeblendete Gruppen je Playlist */
    fun getString(k: String, def: String): String = sp.getString(k, def) ?: def
    fun putString(k: String, v: String) = sp.edit().putString(k, v).apply()

    /** Verwaltung: Personen mit eigenen Zugangsdaten */
    fun people(): JSONArray = try { JSONArray(sp.getString("people", "[]")) } catch (e: Exception) { JSONArray() }
    fun savePeople(a: JSONArray) = sp.edit().putString("people", a.toString()).commit()

    /** gespeicherte Auswahl-Vorlagen fürs Senden an andere Geräte */
    fun sendTemplates(): JSONArray = try { JSONArray(sp.getString("send_templates", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveSendTemplates(a: JSONArray) = sp.edit().putString("send_templates", a.toString()).commit()

    /** Länderauswahl aus der Fern-Einrichtung vormerken (wird beim ersten Laden angewendet) */
    fun setAllowedCountries(key: String, codes: Set<String>) = sp.edit().putStringSet("allowc_$key", codes).commit()

    /** vorgemerkte Länderauswahl anwenden: alle anderen Gruppen/Kategorien ausblenden */
    fun applyAllowedCountries(key: String, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val raw = sp.getStringSet("allowc_$key", null) ?: return false
        val a = raw.toMutableSet()
        if ("DE" in a) a.add("GER"); if ("UK" in a) a.add("GB"); if ("US" in a) a.add("USA")
        setHiddenGroups(key, names.filter { Countries.code(it) !in a }.toSet())
        sp.edit().remove("allowc_$key").commit()
        return true
    }

    fun hiddenGroups(pid: String): Set<String> = sp.getStringSet("hidden_$pid", emptySet())!!.toSet()
    fun setHiddenGroups(pid: String, groups: Set<String>) = sp.edit().putStringSet("hidden_$pid", groups).commit()

    fun recents(pid: String): List<String> {
        val a = JSONArray(sp.getString("recent_$pid", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }

    fun addRecent(pid: String, url: String) {
        val l = recents(pid).toMutableList(); l.remove(url); l.add(0, url)
        val a = JSONArray(); l.take(30).forEach { a.put(it) }
        sp.edit().putString("recent_$pid", a.toString()).apply()
    }

    fun clearRecents(pid: String) { sp.edit().remove("recent_$pid").commit() }
    fun removeRecent(pid: String, url: String) {
        val a = JSONArray(); recents(pid).filter { it != url }.forEach { a.put(it) }
        sp.edit().putString("recent_$pid", a.toString()).commit()
    }

    fun lastUrl(pid: String): String? = sp.getString("last_$pid", null)
    fun setLastUrl(pid: String, url: String) { sp.edit().putString("last_$pid", url).commit() }

    var lastGroup: String?
        get() = sp.getString("lastGroup", null)
        set(v) = sp.edit().putString("lastGroup", v).apply()

    fun userAgent(pid: String): String? = sp.getString("ua_$pid", null)
    fun setUserAgent(pid: String, ua: String) = sp.edit().putString("ua_$pid", ua).apply()
    fun userAgentFixed(pid: String) = sp.getBoolean("uafix_$pid", false)
    fun setUserAgentFixed(pid: String, ua: String?) {
        if (ua == null) sp.edit().remove("ua_$pid").putBoolean("uafix_$pid", false).apply()
        else sp.edit().putString("ua_$pid", ua).putBoolean("uafix_$pid", true).apply()
    }

    /** "ts" oder "m3u8" – nur für Xtream-Codes */
    var streamFormat: String
        get() = sp.getString("format", "ts")!!
        set(v) = sp.edit().putString("format", v).apply()
}

/** Fortschritt: Text + Prozent (−1 = unbestimmt) */
typealias Progress = (String, Int) -> Unit

fun mb(b: Long): String = String.format(java.util.Locale.GERMANY, "%.1f MB", b / 1048576.0)

class CountingInputStream(inner: InputStream, private val total: Long, private val cb: (Long, Long) -> Unit) : FilterInputStream(inner) {
    private var count = 0L
    private var lastReport = 0L
    private fun add(n: Long) {
        if (n <= 0) return
        count += n
        val now = System.currentTimeMillis()
        if (now - lastReport > 250) { lastReport = now; cb(count, total) }
    }
    override fun read(): Int { val r = super.read(); if (r >= 0) add(1); return r }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); add(r.toLong()); return r }
}

class HttpStatusException(val code: Int, val body: String) :
    IOException("Server antwortet mit HTTP $code" + if (body.isNotBlank()) "\n„${body.take(120)}“" else "")

fun appVersion(ctx: Context): String = try {
    "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
} catch (e: Exception) { "" }

object Http {
    /** Kennungen, die viele Anbieter zulassen – werden der Reihe nach probiert */
    val CANDIDATES = listOf(
        "TiviMate/5.1.6 (Android 11)",
        "IPTVSmartersPlayer",
        "okhttp/4.12.0",
        "VLC/3.0.21 LibVLC/3.0.21",
        "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Lavf/60.16.100",
        "Dalvik/2.1.0 (Linux; U; Android 11; SHIELD Android TV Build/RQ1A.210105.003)",
        "PintoTV/1.0 (Linux; Android)"
    )

    @Volatile var UA: String = CANDIDATES[0]
    /** Kennung für den aktuellen Lade-Thread (für Playlists, die nicht aktiv sind) */
    val threadUA = ThreadLocal<String?>()
    private fun ua() = threadUA.get() ?: UA

    /** onBytes(gelesen, gesamt) – gesamt ist −1, wenn der Server keine Größe nennt */
    fun open(url: String, onBytes: ((Long, Long) -> Unit)? = null): InputStream {
        var u = url
        var hops = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 90000
            c.setRequestProperty("User-Agent", ua())
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = try { c.responseCode } catch (e: java.net.SocketTimeoutException) {
                throw java.net.SocketTimeoutException("Zeitüberschreitung – der Server antwortet nicht")
            }
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                if (++hops > 6) throw IOException("Zu viele Weiterleitungen")
                continue
            }
            if (code !in 200..299) {
                val body = try { (c.errorStream ?: c.inputStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (e: Exception) { "" }
                c.disconnect()
                throw HttpStatusException(code, body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim())
            }
            var raw: InputStream = c.inputStream
            if (onBytes != null) raw = CountingInputStream(raw, c.contentLengthLong, onBytes)
            var ins: InputStream = BufferedInputStream(raw, 65536)
            if ("gzip".equals(c.contentEncoding, true)) ins = BufferedInputStream(GZIPInputStream(ins, 65536), 65536)
            return gunzipIfNeeded(ins)
        }
    }

    /** .gz-Dateien (z. B. EPG) erkennen, auch ohne Header */
    fun gunzipIfNeeded(i: InputStream): InputStream {
        val b = if (i.markSupported()) i else BufferedInputStream(i, 65536)
        b.mark(4)
        val x = b.read(); val y = b.read()
        b.reset()
        return if (x == 0x1f && y == 0x8b) BufferedInputStream(GZIPInputStream(b, 65536), 65536) else b
    }

    fun text(url: String, onBytes: ((Long, Long) -> Unit)? = null): String =
        open(url, onBytes).bufferedReader().use { it.readText() }

    /** Standard-Fortschrittsanzeige für Downloads */
    fun bytesTo(label: String, progress: Progress?): ((Long, Long) -> Unit)? = progress?.let { pr ->
        { done: Long, total: Long ->
            if (total > 0) pr("$label  ${mb(done)} von ${mb(total)}", ((done * 100) / total).toInt().coerceIn(0, 100))
            else pr("$label  ${mb(done)}", -1)
        }
    }
}

object M3uParser {
    class Result(val channels: List<Channel>, val epgUrl: String?)

    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")

    private fun titleComma(line: String): Int {
        var q = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') q = !q else if (c == ',' && !q) return i
        }
        return -1
    }

    fun parse(input: InputStream): Result {
        val list = ArrayList<Channel>()
        var epg: String? = null
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        var id: String? = null
        var chno: Int? = null
        var started = false
        input.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim().removePrefix("﻿")
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#EXTM3U") -> {
                        started = true
                        attr.findAll(line).forEach { m ->
                            val k = m.groupValues[1].lowercase()
                            if ((k == "x-tvg-url" || k == "url-tvg") && m.groupValues[2].isNotBlank())
                                epg = m.groupValues[2].split(",").first().trim()
                        }
                    }
                    line.startsWith("#EXTINF") -> {
                        started = true
                        val comma = titleComma(line)
                        val head = if (comma >= 0) line.substring(0, comma) else line
                        name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                        logo = null; group = null; id = null; chno = null
                        attr.findAll(head).forEach { m ->
                            val v = m.groupValues[2]
                            when (m.groupValues[1].lowercase()) {
                                "tvg-logo" -> logo = v
                                "group-title" -> group = v
                                "tvg-id" -> id = v
                                "tvg-chno" -> chno = v.toIntOrNull()
                                "tvg-name" -> if (name.isNullOrBlank()) name = v
                            }
                        }
                    }
                    line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                    line.startsWith("#") -> {}
                    else -> {
                        if (name != null || started) {
                            list.add(
                                Channel(
                                    name?.takeIf { it.isNotBlank() } ?: line.substringAfterLast('/'),
                                    line,
                                    logo?.takeIf { it.isNotBlank() },
                                    group?.takeIf { it.isNotBlank() } ?: "Ohne Gruppe",
                                    id?.takeIf { it.isNotBlank() },
                                    chno ?: (list.size + 1)
                                )
                            )
                        }
                        name = null; logo = null; group = null; id = null; chno = null
                    }
                }
            }
        }
        if (list.isEmpty()) throw IOException("Keine Kanäle in der Playlist gefunden")
        return Result(list, epg)
    }
}

object Xtream {
    /** Falls ein kompletter Link eingefügt wurde (…/get.php?username=…&password=…), Zugangsdaten herausziehen */
    fun normalize(p: Playlist): Playlist {
        val raw = p.server.trim()
        if (!raw.contains("?") && !raw.contains(".php")) return p
        return try {
            val u = URL(if (raw.startsWith("http")) raw else "http://$raw")
            val q = (u.query ?: "").split("&").associate { val i = it.indexOf('='); if (i > 0) it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8") else it to "" }
            val base = u.protocol + "://" + u.host + (if (u.port > 0) ":${u.port}" else "")
            p.copy(server = base, user = p.user.ifBlank { q["username"] ?: "" }, pass = p.pass.ifBlank { q["password"] ?: "" })
        } catch (e: Exception) { p }
    }

    fun base(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun auth(p: Playlist) = "username=${enc(p.user)}&password=${enc(p.pass)}"

    /** erlaubte gleichzeitige Verbindungen laut Anbieter (0 = unbekannt) */
    @Volatile var maxConnections = 0
    val lastMax = ThreadLocal<Int>()

    fun epgUrl(p0: Playlist): String { val p = normalize(p0); return "${base(p.server)}/xmltv.php?${auth(p)}" }

    fun load(p0: Playlist, ext: String, progress: Progress? = null): List<Channel> {
        val p = normalize(p0)
        val b = base(p.server)
        progress?.invoke("Anmeldung beim Server …", -1)
        try {
            val info = JSONObject(Http.text("$b/player_api.php?${auth(p)}")).optJSONObject("user_info")
            if (info != null) {
                if (info.optString("auth") == "0") throw IOException("Anmeldung fehlgeschlagen – Benutzer oder Passwort falsch")
                lastMax.set(info.optString("max_connections").toIntOrNull() ?: 0)
            }
        } catch (e: org.json.JSONException) { }
        val catsTxt = Http.text("$b/player_api.php?${auth(p)}&action=get_live_categories")
        val cats = try { JSONArray(catsTxt) } catch (e: Exception) {
            throw IOException("Anmeldung fehlgeschlagen – Server, Benutzer oder Passwort prüfen")
        }
        val catMap = HashMap<String, String>()
        for (i in 0 until cats.length()) {
            val o = cats.getJSONObject(i)
            catMap[o.optString("category_id")] = o.optString("category_name")
        }
        progress?.invoke("${cats.length()} Gruppen gefunden – Senderliste wird geladen …", -1)
        val streamsTxt = Http.text("$b/player_api.php?${auth(p)}&action=get_live_streams", Http.bytesTo("Senderliste wird geladen …", progress))
        progress?.invoke("Senderliste wird verarbeitet …", -1)
        val streams = JSONArray(streamsTxt)
        val out = ArrayList<Channel>(streams.length())
        val total = streams.length()
        for (i in 0 until total) {
            if (progress != null && i % 500 == 0) progress("Sender werden verarbeitet: $i von $total", i * 100 / total)
            val o = streams.getJSONObject(i)
            val sid = o.optString("stream_id")
            out.add(
                Channel(
                    o.optString("name").trim(),
                    "$b/live/${p.user}/${p.pass}/$sid.$ext",
                    o.optString("stream_icon").takeIf { it.isNotBlank() && it != "null" },
                    catMap[o.optString("category_id")] ?: "Ohne Gruppe",
                    o.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
                    o.optInt("num", i + 1)
                )
            )
        }
        if (out.isEmpty()) throw IOException("Keine Live-Kanäle gefunden")
        // nach Kategorie-Reihenfolge des Anbieters sortieren
        val order = catMap.values.withIndex().associate { it.value to it.index }
        return out.sortedWith(compareBy({ order[it.group] ?: Int.MAX_VALUE }, { it.number }))
    }
}

object XmlTv {
    private val prefix = Regex("^[a-z]{2,3}\\s*[:|]\\s*")
    private val quality = Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|raw)\\b")
    private val nonAlnum = Regex("[^\\p{L}\\p{N}]")

    fun norm(s: String): String =
        s.lowercase().replace(prefix, "").replace(quality, "").replace(nonAlnum, "")

    private val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    fun parseTime(s: String?): Long {
        if (s == null || s.length < 12) return 0
        return try {
            cal.clear()
            cal.set(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(),
                if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0
            )
            var t = cal.timeInMillis
            val tz = s.substring(minOf(14, s.length)).trim()
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val sign = if (tz[0] == '-') -1 else 1
                t -= sign * (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()) * 60000L
            }
            t
        } catch (e: Exception) { 0 }
    }

    fun parse(input: InputStream, channels: List<Channel>, withDesc: Boolean = true): Map<String, List<Program>> {
        val ids = HashSet<String>()
        val names = HashSet<String>()
        channels.forEach { c -> c.epgId?.lowercase()?.let { ids.add(it) }; names.add("n:" + norm(c.name)) }
        val chanKeys = HashMap<String, MutableList<String>>()
        val out = HashMap<String, ArrayList<Program>>()
        val now = System.currentTimeMillis()
        val from = now - 6 * 3600_000L
        val to = now + 2 * 24 * 3600_000L

        val xp = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        xp.setInput(input, null)
        var curKeys: MutableList<String>? = null
        var pKeys: List<String>? = null
        var pStart = 0L; var pStop = 0L
        var title = ""; var desc = ""
        try {
            var ev = xp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (xp.name) {
                        "channel" -> {
                            val id = xp.getAttributeValue(null, "id") ?: ""
                            val keys = mutableListOf<String>()
                            if (id.lowercase() in ids) keys.add(id.lowercase())
                            curKeys = keys
                            chanKeys[id] = keys
                        }
                        "display-name" -> curKeys?.let { k ->
                            val n = "n:" + norm(xp.nextText())
                            if (n in names && n !in k) k.add(n)
                        }
                        "programme" -> {
                            curKeys = null
                            pKeys = null
                            val cid = xp.getAttributeValue(null, "channel") ?: ""
                            val keys: List<String> = chanKeys[cid]
                                ?: if (cid.lowercase() in ids) listOf(cid.lowercase()) else emptyList()
                            if (keys.isNotEmpty()) {
                                val s = parseTime(xp.getAttributeValue(null, "start"))
                                val e = parseTime(xp.getAttributeValue(null, "stop"))
                                if (s > 0 && e > s && e > from && s < to) {
                                    pKeys = keys; pStart = s; pStop = e; title = ""; desc = ""
                                }
                            }
                        }
                        "title" -> if (pKeys != null && title.isEmpty()) title = xp.nextText().trim()
                        "desc" -> if (pKeys != null && desc.isEmpty() && withDesc) desc = xp.nextText().trim().take(300)
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (xp.name) {
                        "channel" -> curKeys = null
                        "programme" -> {
                            pKeys?.forEach { k -> out.getOrPut(k) { ArrayList() }.add(Program(pStart, pStop, title, desc)) }
                            pKeys = null
                        }
                    }
                }
                ev = xp.next()
            }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            // fehlerhaftes Dateiende: bisher gelesene Daten behalten
            if (out.isEmpty()) throw IOException("EPG konnte nicht gelesen werden: ${e.message}")
        }
        val result = HashMap<String, List<Program>>()
        out.forEach { (k, l) ->
            l.sortBy { it.start }
            val clean = ArrayList<Program>(l.size)
            for (p in l) if (clean.isEmpty() || p.start >= clean.last().stop) clean.add(p)
            result[k] = clean
        }
        return result
    }
}

/** Zentraler Datenspeicher der App */
object Repo {
    var playlistId: String? = null; private set
    var channels: List<Channel> = emptyList(); private set
    var groups: List<String> = emptyList(); private set
    var byGroup: Map<String, List<Channel>> = emptyMap(); private set
    var epgUrl: String = ""; private set
    @Volatile var epg: Map<String, List<Program>> = emptyMap(); private set
    /** Filme/Serien-Einträge aus M3U-Playlists */
    var m3uVod: List<Channel> = emptyList(); private set

    private const val MAX_AGE = 12 * 3600_000L

    class Loaded(val playlist: Playlist, val channels: List<Channel>, val epgUrl: String, val maxConnections: Int, val ua: String,
                 val vod: List<Channel> = emptyList()) {
        val groups: List<String> by lazy { channels.map { it.group }.distinct() }
        val byGroup: Map<String, List<Channel>> by lazy { channels.groupBy { it.group } }
    }

    /** weitere geladene Playlists (für Multi-View) */
    private val others = java.util.concurrent.ConcurrentHashMap<String, Loaded>()
    /** aktive Playlist so, wie sie geladen wurde (für schnellen Wechsel zurück) */
    @Volatile private var activeLoaded: Loaded? = null

    /** Senderliste einer Playlist holen, ohne die aktive Playlist zu verändern */
    fun fetch(ctx: Context, p: Playlist, format: String, force: Boolean, progress: Progress?): Loaded {
        val f = File(ctx.filesDir, "ch_${p.id}.json")
        val store = Store(ctx)
        var ua = store.userAgent(p.id) ?: Http.CANDIDATES[0]
        var list: List<Channel>? = null
        var detectedEpg = ""
        var maxc = 0
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) {
            try {
                progress?.invoke("Gespeicherte Senderliste wird geladen …", -1)
                val o = JSONObject(f.readText())
                detectedEpg = o.optString("epg")
                maxc = o.optInt("maxc", 0)
                val a = o.getJSONArray("ch")
                list = (0 until a.length()).map {
                    val c = a.getJSONArray(it)
                    Channel(c.getString(0), c.getString(1), c.optString(2).ifBlank { null }, c.getString(3),
                        c.optString(4).ifBlank { null }, c.getInt(5))
                }
            } catch (e: Exception) { list = null }
        }
        if (list == null) {
            val fixed = store.userAgentFixed(p.id)
            val tries = if (fixed) listOf(ua) else (listOf(ua) + Http.CANDIDATES).distinct()
            var lastError: Exception? = null
            try {
                for (t in tries) {
                    Http.threadUA.set(t)
                    Xtream.lastMax.set(0)
                    if (t != tries.first()) progress?.invoke("Server lehnt ab – versuche andere Player-Kennung …", -1)
                    try {
                        if (p.type == "xtream") {
                            list = Xtream.load(p, format, progress)
                            maxc = Xtream.lastMax.get() ?: 0
                        } else {
                            progress?.invoke("Verbinde mit Server …", -1)
                            val r = Http.open(p.url, Http.bytesTo("Playlist wird geladen …", progress)).use { M3uParser.parse(it) }
                            list = r.channels
                            detectedEpg = r.epgUrl ?: ""
                        }
                        ua = t
                        store.setUserAgent(p.id, t)
                        break
                    } catch (e: HttpStatusException) {
                        lastError = e // anderer Kennung eine Chance geben
                    } catch (e: java.net.SocketTimeoutException) {
                        // manche Anbieter lassen unbekannte Apps ins Leere laufen
                        lastError = java.net.SocketTimeoutException("Zeitüberschreitung – der Server antwortet nicht")
                    } catch (e: Exception) {
                        // Anmeldefehler können bei manchen Anbietern auch an der Kennung liegen
                        lastError = e
                        if (e !is IOException || e.message?.startsWith("Anmeldung") != true) throw e
                    }
                }
            } finally {
                Http.threadUA.set(null)
            }
            val l = list ?: throw lastError ?: IOException("Unbekannter Fehler")
            progress?.invoke("${l.size} Sender werden gespeichert …", 100)
            val a = JSONArray()
            l.forEach { c -> a.put(JSONArray().put(c.name).put(c.url).put(c.logo ?: "").put(c.group).put(c.epgId ?: "").put(c.number)) }
            f.writeText(JSONObject().put("epg", detectedEpg).put("maxc", maxc).put("ch", a).toString())
        }
        val epgU = p.epgUrl.ifBlank { if (p.type == "xtream") Xtream.epgUrl(p) else detectedEpg }
        // Filme/Serien aus M3U-Listen von den Live-Sendern trennen
        val (vod, live) = list!!.partition { Vod.isVodUrl(it.url) }
        return Loaded(p, live.ifEmpty { list!! }, epgU, maxc, ua, vod)
    }

    /** eigene Sendernummern anwenden (nach dem Laden und nach Änderungen) */
    fun applyCustomNumbers(ctx: Context) {
        val pid = playlistId ?: return
        val custom = Store(ctx).customNumbers(pid)
        if (custom.isEmpty() && original.isEmpty()) return
        val base = original.ifEmpty { channels }
        channels = base.map { c -> custom[favKey(c)]?.let { c.copy(number = it) } ?: c }
        byGroup = channels.groupBy { it.group }
    }
    private var original: List<Channel> = emptyList()

    suspend fun loadChannels(ctx: Context, p: Playlist, format: String, force: Boolean, progress: Progress? = null) = withContext(Dispatchers.IO) {
        // schon im Hintergrund geladen → sofort umschalten
        val l = (if (!force) others[p.id] else null) ?: fetch(ctx, p, format, force, progress)
        activeLoaded?.let { if (it.playlist.id != p.id) others[it.playlist.id] = it }
        activeLoaded = l
        if (playlistId != p.id) epg = emptyMap()
        Http.UA = l.ua
        Xtream.maxConnections = l.maxConnections
        m3uVod = l.vod
        channels = l.channels
        original = l.channels
        groups = l.groups
        byGroup = l.byGroup
        playlistId = p.id
        applyCustomNumbers(ctx)
        epgUrl = l.epgUrl
        others.remove(p.id)
    }

    /** andere Playlist im Hintergrund laden (für sofortigen Wechsel und Filme/Serien) */
    suspend fun preload(ctx: Context, p: Playlist, format: String, force: Boolean): Loaded = withContext(Dispatchers.IO) {
        if (p.id == playlistId) activeLoaded?.let { return@withContext it }
        if (!force) others[p.id]?.let { return@withContext it }
        fetch(ctx, p, format, force, null).also { if (p.id != playlistId) others[p.id] = it }
    }

    /** schon geladene Playlist (aktiv oder vorgeladen) */
    fun loaded(pid: String): Loaded? = if (pid == playlistId) activeLoaded else others[pid]

    /** Gruppennamen einer Playlist (geladen oder aus der gespeicherten Senderliste) */
    fun groupsOf(ctx: Context, pid: String): List<String> {
        loaded(pid)?.let { return it.groups }
        val f = File(ctx.filesDir, "ch_$pid.json")
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONObject(f.readText()).getJSONArray("ch")
            val out = LinkedHashSet<String>()
            for (i in 0 until a.length()) out.add(a.getJSONArray(i).getString(3))
            out.toList()
        } catch (e: Exception) { emptyList() }
    }

    /** Länder, die im Live-TV sichtbar sind (null = keine Einschränkung) – gilt auch für Filme/Serien */
    fun liveCountries(ctx: Context, pid: String): Set<String>? {
        val groups = groupsOf(ctx, pid)
        if (groups.isEmpty()) return null
        val store = Store(ctx)
        store.applyAllowedCountries(pid, groups)
        val hidden = store.hiddenGroups(pid)
        if (hidden.isEmpty()) return null
        val all = groups.map { Countries.code(it) }.toSet()
        val vis = groups.filter { it !in hidden }.map { Countries.code(it) }.toSet()
        return if (vis.containsAll(all)) null else vis
    }

    /** Filme/Serien-Einträge einer M3U-Playlist */
    fun vodOf(pid: String): List<Channel> = if (pid == playlistId) m3uVod else others[pid]?.vod ?: emptyList()

    /** TV-Programm einer anderen Playlist vorab herunterladen und speichern (nicht im Speicher halten) */
    suspend fun prefetchEpg(ctx: Context, l: Loaded, force: Boolean) = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "epg_${l.playlist.id}.bin")
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) return@withContext
        if (l.epgUrl.isBlank()) return@withContext
        Http.threadUA.set(l.ua)
        try {
            val map = try {
                Http.open(l.epgUrl, null).use { XmlTv.parse(it, l.channels) }
            } catch (e: OutOfMemoryError) {
                System.gc()
                Http.open(l.epgUrl, null).use { XmlTv.parse(it, l.channels, withDesc = false) }
            }
            val tmp = File(f.path + ".tmp")
            writeEpg(tmp, map)
            tmp.renameTo(f)
        } finally { Http.threadUA.set(null) }
    }

    /** aktive Playlist als Loaded */
    fun current(): Loaded? {
        val pid = playlistId ?: return null
        return Loaded(Playlist(pid, "", "", "", "", "", "", ""), channels, epgUrl, Xtream.maxConnections, Http.UA)
    }

    /** beliebige Playlist für Multi-View laden (aktive direkt, andere aus Speicher/Netz) */
    suspend fun forMultiView(ctx: Context, p: Playlist, format: String, progress: Progress?): Loaded = withContext(Dispatchers.IO) {
        if (p.id == playlistId) return@withContext current()!!
        others[p.id]?.let { return@withContext it }
        fetch(ctx, p, format, false, progress).also { others[p.id] = it }
    }

    suspend fun loadEpg(ctx: Context, force: Boolean, progress: Progress? = null) = withContext(Dispatchers.IO) {
        val pid = playlistId ?: return@withContext
        val f = File(ctx.filesDir, "epg_$pid.bin")
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) {
            try { epg = readEpg(f); return@withContext } catch (e: Exception) { }
        }
        if (epgUrl.isBlank()) throw IOException("Keine EPG-Adresse in der Playlist")
        progress?.invoke("TV-Programm: Verbinde …", -1)
        epg = emptyMap() // alten Stand freigeben, bevor neu eingelesen wird
        val map = try {
            Http.open(epgUrl, Http.bytesTo("TV-Programm", progress)).use { XmlTv.parse(it, channels) }
        } catch (e: OutOfMemoryError) {
            System.gc()
            progress?.invoke("TV-Programm sehr groß – lade ohne Beschreibungen …", -1)
            Http.open(epgUrl, Http.bytesTo("TV-Programm (kompakt)", progress)).use { XmlTv.parse(it, channels, withDesc = false) }
        }
        progress?.invoke("TV-Programm wird gespeichert …", 100)
        writeEpg(f, map)
        epg = map
    }

    private fun writeEpg(f: File, m: Map<String, List<Program>>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(f), 65536)).use { o ->
            o.writeInt(m.size)
            m.forEach { (k, l) ->
                o.writeUTF(k); o.writeInt(l.size)
                l.forEach { p -> o.writeLong(p.start); o.writeLong(p.stop); o.writeUTF(p.title.take(300)); o.writeUTF(p.desc) }
            }
        }
    }

    private fun readEpg(f: File): Map<String, List<Program>> {
        DataInputStream(BufferedInputStream(FileInputStream(f), 65536)).use { i ->
            val n = i.readInt()
            val m = HashMap<String, List<Program>>(n * 2)
            repeat(n) {
                val k = i.readUTF(); val c = i.readInt()
                m[k] = List(c) { Program(i.readLong(), i.readLong(), i.readUTF(), i.readUTF()) }
            }
            return m
        }
    }

    private val normCache = HashMap<String, String>()

    fun programs(c: Channel): List<Program> {
        val e = epg
        if (e.isEmpty()) return emptyList()
        c.epgId?.let { id -> e[id.lowercase()]?.let { return it } }
        val n = normCache.getOrPut(c.name) { "n:" + XmlTv.norm(c.name) }
        return e[n] ?: emptyList()
    }

    /** Index der ersten Sendung, die nach t endet */
    fun firstEndingAfter(l: List<Program>, t: Long): Int {
        var lo = 0; var hi = l.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (l[mid].stop <= t) lo = mid + 1 else hi = mid }
        return lo
    }

    fun nowNext(c: Channel, t: Long = System.currentTimeMillis()): Pair<Program?, Program?> {
        val l = programs(c)
        val i = firstEndingAfter(l, t)
        val cur = l.getOrNull(i)?.takeIf { it.start <= t }
        val next = if (cur != null) l.getOrNull(i + 1) else l.getOrNull(i)
        return cur to next
    }
}
