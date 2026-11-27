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
    /** Erscheinungsjahr – aus dem Feld oder aus „Titel (2015)“ */
    val yearNum: Int? by lazy {
        year.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            ?: Regex("[(\\[](\\d{4})[)\\]]").findAll(name).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
    }
    val ratingNum: Double by lazy {
        val r = rating.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (r > 10) r / 10 else r
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

    private fun readDisk(f: File): VodCatalog? {
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > DISK_AGE) return null
        return try {
            val o = JSONObject(f.readText())
            val ca = o.getJSONArray("c"); val ia = o.getJSONArray("i")
            val cats = (0 until ca.length()).map { val c = ca.getJSONArray(it); VodCategory(c.getString(0), c.getString(1)) }
            val items = ArrayList<VodItem>(ia.length())
            for (k in 0 until ia.length()) {
                val a = ia.getJSONArray(k)
                items.add(VodItem(a.getString(0), a.getString(1), a.optString(2).ifBlank { null }, a.getString(3), a.optString(4),
                    a.optString(5).ifBlank { null }, a.optBoolean(6), a.optString(7), a.optString(8), null, a.optLong(9)))
            }
            VodCatalog(cats, items)
        } catch (e: Exception) { null }
    }

    private fun writeDisk(f: File, c: VodCatalog) {
        val ca = JSONArray(); c.categories.forEach { ca.put(JSONArray().put(it.id).put(it.name)) }
        val ia = JSONArray()
        c.items.forEach { v -> ia.put(JSONArray().put(v.id).put(v.name).put(v.poster ?: "").put(v.categoryId).put(v.rating)
            .put(v.url ?: "").put(v.isSeries).put(v.plot).put(v.year).put(v.added)) }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(JSONObject().put("c", ca).put("i", ia).toString())
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
        val txt = Http.text("$b/player_api.php?$a&action=" + if (series) "get_series" else "get_vod_streams", Http.bytesTo(label, progress))
        progress?.invoke("Wird verarbeitet …", -1)
        val arr = try { JSONArray(txt) } catch (e: Exception) { throw IOException(if (series) "Keine Serien gefunden" else "Keine Filme gefunden") }
        val items = ArrayList<VodItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (series) {
                items.add(VodItem(str(o, "series_id"), str(o, "name"), str(o, "cover").ifBlank { null }, str(o, "category_id"),
                    str(o, "rating"), null, true, str(o, "plot"), str(o, "releaseDate").take(4),
                    added = str(o, "last_modified").toLongOrNull() ?: 0))
            } else {
                val id = str(o, "stream_id")
                val ext = str(o, "container_extension").ifBlank { "mp4" }
                items.add(VodItem(id, str(o, "name"), str(o, "stream_icon").ifBlank { null }, str(o, "category_id"),
                    str(o, "rating"), "$b/movie/${p.user}/${p.pass}/$id.$ext", false, year = str(o, "year"),
                    added = str(o, "added").toLongOrNull() ?: 0))
            }
        }
        return VodCatalog(categories.filter { c -> items.any { it.categoryId == c.id } }, items)
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
