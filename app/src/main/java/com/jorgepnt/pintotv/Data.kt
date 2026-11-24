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

/** Einstellungen, Playlists, Favoriten, Verlauf */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pintotv", Context.MODE_PRIVATE)

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

    fun favorites(pid: String): Set<String> = sp.getStringSet("fav_$pid", emptySet())!!.toSet()

    fun toggleFavorite(pid: String, url: String): Boolean {
        val s = favorites(pid).toMutableSet()
        val added = if (url in s) { s.remove(url); false } else { s.add(url); true }
        sp.edit().putStringSet("fav_$pid", s).apply()
        return added
    }

    fun recents(pid: String): List<String> {
        val a = JSONArray(sp.getString("recent_$pid", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }

    fun addRecent(pid: String, url: String) {
        val l = recents(pid).toMutableList(); l.remove(url); l.add(0, url)
        val a = JSONArray(); l.take(30).forEach { a.put(it) }
        sp.edit().putString("recent_$pid", a.toString()).apply()
    }

    fun lastUrl(pid: String): String? = sp.getString("last_$pid", null)
    fun setLastUrl(pid: String, url: String) = sp.edit().putString("last_$pid", url).apply()

    var lastGroup: String?
        get() = sp.getString("lastGroup", null)
        set(v) = sp.edit().putString("lastGroup", v).apply()

    /** "ts" oder "m3u8" – nur für Xtream-Codes */
    var streamFormat: String
        get() = sp.getString("format", "ts")!!
        set(v) = sp.edit().putString("format", v).apply()
}

object Http {
    const val UA = "PintoTV/1.0 (Linux; Android)"

    fun open(url: String): InputStream {
        var u = url
        var hops = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                if (++hops > 6) throw IOException("Zu viele Weiterleitungen")
                continue
            }
            if (code !in 200..299) throw IOException("Server antwortet mit HTTP $code")
            var ins: InputStream = BufferedInputStream(c.inputStream, 65536)
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

    fun text(url: String): String = open(url).bufferedReader().use { it.readText() }
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
    fun base(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun auth(p: Playlist) = "username=${enc(p.user)}&password=${enc(p.pass)}"

    fun epgUrl(p: Playlist) = "${base(p.server)}/xmltv.php?${auth(p)}"

    fun load(p: Playlist, ext: String): List<Channel> {
        val b = base(p.server)
        val catsTxt = Http.text("$b/player_api.php?${auth(p)}&action=get_live_categories")
        val cats = try { JSONArray(catsTxt) } catch (e: Exception) {
            throw IOException("Anmeldung fehlgeschlagen – Server, Benutzer oder Passwort prüfen")
        }
        val catMap = HashMap<String, String>()
        for (i in 0 until cats.length()) {
            val o = cats.getJSONObject(i)
            catMap[o.optString("category_id")] = o.optString("category_name")
        }
        val streams = JSONArray(Http.text("$b/player_api.php?${auth(p)}&action=get_live_streams"))
        val out = ArrayList<Channel>(streams.length())
        for (i in 0 until streams.length()) {
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

    fun parse(input: InputStream, channels: List<Channel>): Map<String, List<Program>> {
        val ids = HashSet<String>()
        val names = HashSet<String>()
        channels.forEach { c -> c.epgId?.lowercase()?.let { ids.add(it) }; names.add("n:" + norm(c.name)) }
        val chanKeys = HashMap<String, MutableList<String>>()
        val out = HashMap<String, ArrayList<Program>>()
        val now = System.currentTimeMillis()
        val from = now - 12 * 3600_000L
        val to = now + 4 * 24 * 3600_000L

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
                        "desc" -> if (pKeys != null && desc.isEmpty()) desc = xp.nextText().trim().take(700)
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

    private const val MAX_AGE = 12 * 3600_000L

    suspend fun loadChannels(ctx: Context, p: Playlist, format: String, force: Boolean) = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "ch_${p.id}.json")
        var list: List<Channel>? = null
        var detectedEpg = ""
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) {
            try {
                val o = JSONObject(f.readText())
                detectedEpg = o.optString("epg")
                val a = o.getJSONArray("ch")
                list = (0 until a.length()).map {
                    val c = a.getJSONArray(it)
                    Channel(c.getString(0), c.getString(1), c.optString(2).ifBlank { null }, c.getString(3),
                        c.optString(4).ifBlank { null }, c.getInt(5))
                }
            } catch (e: Exception) { list = null }
        }
        if (list == null) {
            if (p.type == "xtream") {
                list = Xtream.load(p, format)
            } else {
                val r = Http.open(p.url).use { M3uParser.parse(it) }
                list = r.channels
                detectedEpg = r.epgUrl ?: ""
            }
            val a = JSONArray()
            list.forEach { c -> a.put(JSONArray().put(c.name).put(c.url).put(c.logo ?: "").put(c.group).put(c.epgId ?: "").put(c.number)) }
            f.writeText(JSONObject().put("epg", detectedEpg).put("ch", a).toString())
        }
        val g = LinkedHashMap<String, MutableList<Channel>>()
        list.forEach { g.getOrPut(it.group) { mutableListOf() }.add(it) }
        if (playlistId != p.id) epg = emptyMap()
        channels = list
        groups = g.keys.toList()
        byGroup = g
        playlistId = p.id
        epgUrl = p.epgUrl.ifBlank { if (p.type == "xtream") Xtream.epgUrl(p) else detectedEpg }
    }

    suspend fun loadEpg(ctx: Context, force: Boolean) = withContext(Dispatchers.IO) {
        val pid = playlistId ?: return@withContext
        val f = File(ctx.filesDir, "epg_$pid.bin")
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE) {
            try { epg = readEpg(f); return@withContext } catch (e: Exception) { }
        }
        if (epgUrl.isBlank()) throw IOException("Keine EPG-Adresse in der Playlist")
        val map = Http.open(epgUrl).use { XmlTv.parse(it, channels) }
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
