package com.jorgepnt.pintotv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class VodCategory(val id: String, val name: String)

class VodItem(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: String,
    val url: String?,          // Filme: direkte Adresse; Serien: null
    val isSeries: Boolean,
    val plot: String = "",
    val year: String = "",
    /** nur M3U-Serien: Folgen schon bekannt */
    val m3uEpisodes: List<Episode>? = null,
    /** wann beim Anbieter hinzugefügt (Sekunden), 0 = unbekannt */
    val added: Long = 0
) {
    // einmal berechnet und gemerkt (bei über 100 000 Filmen zählt jede Kleinigkeit)
    private var yearCache = -1
    private var ratingCache = -1.0

    /** Erscheinungsjahr – aus dem Feld oder aus „Titel (2015)“ */
    val yearNum: Int?
        get() {
            if (yearCache == -1) {
                yearCache = year.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
                    ?: YEAR_IN_NAME.findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 } ?: 0
            }
            return yearCache.takeIf { it > 0 }
        }
    val ratingNum: Double
        get() {
            if (ratingCache < 0) {
                val r = rating.replace(',', '.').toDoubleOrNull() ?: 0.0
                ratingCache = if (r > 10) r / 10 else if (r < 0) 0.0 else r
            }
            return ratingCache
        }

    companion object {
        private val YEAR_IN_NAME = Regex("[(\\[](\\d{4})[)\\]]")
    }
}

class Episode(val season: Int, val number: Int, val title: String, val url: String, val plot: String = "", val duration: String = "")

class VodInfo(val plot: String, val genre: String, val year: String, val duration: String, val cast: String, val director: String)

class VodCatalog(val categories: List<VodCategory>, val items: List<VodItem>)

/** Filme & Serien (Xtream-API oder aus M3U-Playlists) */
object Vod {

    private val vodExt = Regex("\\.(mp4|mkv|avi|mov|m4v|wmv|flv)(\\?.*)?$", RegexOption.IGNORE_CASE)
    private val seRegex = Regex("(?i)^(.*?)[\\s._-]*S(\\d{1,2})\\s*[._-]?\\s*E(\\d{1,3})")

    fun isVodUrl(url: String): Boolean =
        url.contains("/movie/") || url.contains("/series/") || vodExt.containsMatchIn(url)

    private fun isSeriesEntry(c: Channel) = c.url.contains("/series/") || seRegex.containsMatchIn(c.name)

    private val cache = HashMap<String, VodCatalog>()

    private fun <T> withUa(pid: String, ctx: Context, block: () -> T): T {
        Http.threadUA.set(Store(ctx).userAgent(pid) ?: Http.UA)
        try { return block() } finally { Http.threadUA.set(null) }
    }

    private fun str(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" } ?: ""

    suspend fun catalog(ctx: Context, p0: Playlist, series: Boolean, progress: Progress?): VodCatalog = withContext(Dispatchers.IO) {
        val key = p0.id + if (series) ":s" else ":m"
        cache[key]?.let { return@withContext it }
        val result = if (p0.type == "xtream") {
            // gespeicherter Katalog (12 Std.) – schont den Anbieter, der sonst bei vielen Abrufen sperrt (z. B. HTTP 456)
            val f = File(ctx.filesDir, "vod_${p0.id}_${if (series) "s" else "m"}.json")
            readDisk(f) ?: run {
                val fresh = fetchWithRetry(ctx, p0, series, progress)
                try { writeDisk(f, fresh) } catch (_: Exception) { }
                fresh
            }
        } else m3uCatalog(Repo.vodOf(p0.id), series)
        if (result.items.isNotEmpty() || p0.type == "xtream") cache[key] = result
        result
    }

    /** Katalog vorab herunterladen und speichern, damit „Filme“/„Serien“ sofort da sind.
     *  true = es wurde wirklich beim Anbieter abgefragt */
    suspend fun prefetch(ctx: Context, p0: Playlist, series: Boolean, force: Boolean, since: Long = 0): Boolean = withContext(Dispatchers.IO) {
        if (p0.type != "xtream") return@withContext false
        val key = p0.id + if (series) ":s" else ":m"
        val f = File(ctx.filesDir, "vod_${p0.id}_${if (series) "s" else "m"}.json")
        if (force && f.exists() && f.lastModified() >= since) return@withContext false  // gerade erst geholt
        if (!force && (cache.containsKey(key) || (f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_AGE))) return@withContext false
        val fresh = fetchWithRetry(ctx, p0, series, null)
        try { writeDisk(f, fresh) } catch (_: Exception) { }
        cache.remove(key)   // beim nächsten Öffnen frisch von der Platte
        true
    }

    fun clearCache(ctx: Context? = null) {
        cache.clear()
        ctx?.filesDir?.listFiles()?.filter { it.name.startsWith("vod_") }?.forEach { it.delete() }
    }

    private const val DISK_AGE = 12 * 3600_000L

    /** bei Ablehnung: kurz warten und mit anderer Player-Kennung erneut versuchen */
    private fun fetchWithRetry(ctx: Context, p0: Playlist, series: Boolean, progress: Progress?): VodCatalog {
        val store = Store(ctx)
        val first = store.userAgent(p0.id) ?: Http.UA
        val tries = (listOf(first) + Http.CANDIDATES).distinct().take(4)
        var last: Exception? = null
        for ((i, ua) in tries.withIndex()) {
            if (i > 0) {
                progress?.invoke("Anbieter lehnt ab – neuer Versuch in ${i * 3} s …", -1)
                Thread.sleep(i * 3000L)
            }
            Http.threadUA.set(ua)
            try {
                return xtreamCatalog(Xtream.normalize(p0), series, progress)
            } catch (e: HttpStatusException) {
                last = e
            } catch (e: java.net.SocketTimeoutException) {
                last = e
            } finally { Http.threadUA.set(null) }
        }
        val e = last
        if (e is HttpStatusException && (e.code == 456 || e.code == 429 || e.code == 403 || e.code == 509))
            throw IOException("Der Anbieter sperrt gerade die Abfrage (HTTP ${e.code}) – meist wegen zu vieler Abrufe in kurzer Zeit. Bitte ein paar Minuten warten und dann erneut öffnen.")
        throw e ?: IOException("Unbekannter Fehler")
    }

    /** JSON-Wert als Text lesen, egal ob Text, Zahl, true/false oder null (Objekte/Listen werden übersprungen) */
    private fun jstr(r: android.util.JsonReader): String = when (r.peek()) {
        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> r.nextString()
        android.util.JsonToken.BOOLEAN -> r.nextBoolean().toString()
        android.util.JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    private fun readDisk(f: File): VodCatalog? {
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > DISK_AGE) return null
        return try {
            val cats = ArrayList<VodCategory>()
            val items = ArrayList<VodItem>()
            val ids = HashMap<String, String>()
            android.util.JsonReader(java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), Charsets.UTF_8), 65536)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "c" -> { r.beginArray(); while (r.hasNext()) { r.beginArray(); val id = jstr(r); val n = jstr(r); while (r.hasNext()) r.skipValue(); r.endArray(); cats.add(VodCategory(id, n)) }; r.endArray() }
                        "i" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginArray()
                                val v = arrayOfNulls<String>(10)
                                var k = 0
                                while (r.hasNext()) { val x = jstr(r); if (k < 10) v[k] = x; k++ }
                                r.endArray()
                                val cat = v[3] ?: ""
                                items.add(VodItem(v[0] ?: "", v[1] ?: "", v[2]?.ifBlank { null }, ids.getOrPut(cat) { cat }, v[4] ?: "",
                                    v[5]?.ifBlank { null }, v[6] == "true", v[7] ?: "", v[8] ?: "", null, v[9]?.toLongOrNull() ?: 0))
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            VodCatalog(cats, items)
        } catch (e: Throwable) { null }
    }

    private fun writeDisk(f: File, c: VodCatalog) {
        val tmp = File(f.path + ".tmp")
        android.util.JsonWriter(java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8), 65536)).use { w ->
            w.beginObject()
            w.name("c").beginArray(); c.categories.forEach { w.beginArray().value(it.id).value(it.name).endArray() }; w.endArray()
            w.name("i").beginArray()
            c.items.forEach { v ->
                w.beginArray().value(v.id).value(v.name).value(v.poster ?: "").value(v.categoryId).value(v.rating)
                    .value(v.url ?: "").value(v.isSeries).value(v.plot).value(v.year).value(v.added).endArray()
            }
            w.endArray()
            w.endObject()
        }
        tmp.renameTo(f)
    }

    private fun xtreamCatalog(p: Playlist, series: Boolean, progress: Progress?): VodCatalog {
        val b = Xtream.base(p.server)
        val a = Xtream.auth(p)
        progress?.invoke("Kategorien werden geladen …", -1)
        val catsTxt = Http.text("$b/player_api.php?$a&action=" + if (series) "get_series_categories" else "get_vod_categories")
        val cats = try { JSONArray(catsTxt) } catch (e: Exception) { JSONArray() }
        val categories = (0 until cats.length()).map { val o = cats.getJSONObject(it); VodCategory(str(o, "category_id"), str(o, "category_name")) }
        val label = if (series) "Serien werden geladen …" else "Filme werden geladen …"
        // Stück für Stück lesen: manche Anbieter liefern mehrere hundert MB – am Stück passt das nicht in den Speicher
        val items = ArrayList<VodItem>()
        val ids = HashMap<String, String>()
        Http.open("$b/player_api.php?$a&action=" + if (series) "get_series" else "get_vod_streams", Http.bytesTo(label, progress)).use { ins ->
            val r = android.util.JsonReader(java.io.InputStreamReader(ins, Charsets.UTF_8))
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) throw IOException(if (series) "Keine Serien gefunden" else "Keine Filme gefunden")
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                var id = ""; var name = ""; var icon = ""; var cat = ""; var rating = ""; var ext = ""; var plot = ""; var year = ""; var added = ""
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "stream_id", "series_id" -> id = jstr(r)
                        "name" -> name = jstr(r)
                        "stream_icon", "cover" -> { val x = jstr(r); if (x.isNotBlank() && x != "null") icon = x }
                        "category_id" -> cat = jstr(r)
                        "rating" -> rating = jstr(r)
                        "container_extension" -> ext = jstr(r)
                        "plot" -> plot = if (series) jstr(r).take(400) else { r.skipValue(); "" }
                        "releaseDate" -> { val x = jstr(r); if (series) year = x.take(4) }
                        "year" -> { val x = jstr(r); if (!series) year = x }
                        "added", "last_modified" -> { val x = jstr(r); if (x.isNotBlank()) added = x }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                fun clean(x: String) = if (x == "null") "" else x
                val c = ids.getOrPut(clean(cat)) { clean(cat) }
                if (series) {
                    items.add(VodItem(clean(id), clean(name), icon.ifBlank { null }, c, clean(rating), null, true, clean(plot), clean(year),
                        added = added.toLongOrNull() ?: 0))
                } else {
                    val e = clean(ext).ifBlank { "mp4" }
                    items.add(VodItem(clean(id), clean(name), icon.ifBlank { null }, c, clean(rating), "$b/movie/${p.user}/${p.pass}/${clean(id)}.$e", false,
                        year = clean(year), added = added.toLongOrNull() ?: 0))
                }
            }
        }
        progress?.invoke("Wird verarbeitet …", -1)
        val used = items.mapTo(HashSet()) { it.categoryId }
        return VodCatalog(categories.filter { it.id in used }, items)
    }

    private fun m3uCatalog(all: List<Channel>, series: Boolean): VodCatalog {
        val entries = all.filter { isSeriesEntry(it) == series }
        val groups = entries.map { it.group }.distinct()
        val categories = groups.mapIndexed { i, g -> VodCategory(i.toString(), g) }
        val catId = groups.withIndex().associate { it.value to it.index.toString() }
        if (!series) {
            return VodCatalog(categories, entries.map { c ->
                VodItem(c.url, c.name, c.logo, catId[c.group]!!, "", c.url, false)
            })
        }
        // Folgen zu Serien zusammenfassen
        val bySeries = LinkedHashMap<String, MutableList<Pair<Channel, Episode>>>()
        for (c in entries) {
            val m = seRegex.find(c.name)
            val show = (m?.groupValues?.get(1)?.trim()?.trimEnd('-', '_', '.', ' ') ?: c.name).ifBlank { c.name }
            val season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val num = m?.groupValues?.get(3)?.toIntOrNull() ?: 0
            bySeries.getOrPut(show + "\u0000" + c.group) { mutableListOf() }.add(c to Episode(season, num, c.name, c.url))
        }
        val items = bySeries.map { (k, l) ->
            val name = k.substringBefore('\u0000')
            val first = l.first().first
            VodItem(k, name, first.logo, catId[first.group]!!, "", null, true,
                m3uEpisodes = l.map { it.second }.sortedWith(compareBy({ it.season }, { it.number })))
        }
        return VodCatalog(categories, items)
    }

    suspend fun movieInfo(ctx: Context, p0: Playlist, item: VodItem): VodInfo? = withContext(Dispatchers.IO) {
        if (p0.type != "xtream") return@withContext null
        try {
            withUa(p0.id, ctx) {
                val p = Xtream.normalize(p0)
                val o = JSONObject(Http.text("${Xtream.base(p.server)}/player_api.php?${Xtream.auth(p)}&action=get_vod_info&vod_id=${item.id}"))
                val i = o.optJSONObject("info") ?: JSONObject()
                VodInfo(str(i, "plot").ifBlank { str(i, "description") }, str(i, "genre"), str(i, "releasedate").take(4),
                    str(i, "duration"), str(i, "cast").ifBlank { str(i, "actors") }, str(i, "director"))
            }
        } catch (e: Exception) { null }
    }

    suspend fun episodes(ctx: Context, p0: Playlist, item: VodItem): List<Episode> = withContext(Dispatchers.IO) {
        item.m3uEpisodes?.let { return@withContext it }
        withUa(p0.id, ctx) {
            val p = Xtream.normalize(p0)
            val b = Xtream.base(p.server)
            val o = JSONObject(Http.text("$b/player_api.php?${Xtream.auth(p)}&action=get_series_info&series_id=${item.id}"))
            val out = ArrayList<Episode>()
            fun add(season: Int, e: JSONObject) {
                val id = str(e, "id")
                val ext = str(e, "container_extension").ifBlank { "mp4" }
                val info = e.optJSONObject("info") ?: JSONObject()
                out.add(Episode(e.optString("season").toIntOrNull() ?: season, e.optString("episode_num").toIntOrNull() ?: 0,
                    str(e, "title"), "$b/series/${p.user}/${p.pass}/$id.$ext", str(info, "plot"), str(info, "duration")))
            }
            when (val eps = o.opt("episodes")) {
                is JSONObject -> eps.keys().forEach { k ->
                    val arr = eps.optJSONArray(k) ?: return@forEach
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(k.toIntOrNull() ?: 1, it) }
                }
                is JSONArray -> for (i in 0 until eps.length()) {
                    when (val x = eps.opt(i)) {
                        is JSONArray -> for (j in 0 until x.length()) x.optJSONObject(j)?.let { add(i + 1, it) }
                        is JSONObject -> add(1, x)
                    }
                }
            }
            out.sortedWith(compareBy({ it.season }, { it.number }))
        }
    }
}
