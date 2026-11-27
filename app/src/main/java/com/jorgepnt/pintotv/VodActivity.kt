package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Filme oder Serien: Kategorien links, Poster-Raster rechts */
class VodActivity : Activity() {

    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private var series = false
    /** alle Playlists; only = nur eine davon zeigen (null = alle zusammen) */
    private var pls: List<Playlist> = emptyList()
    private var only: String? = null
    /** geladene Kataloge je Playlist */
    private var parts: List<Pair<Playlist, VodCatalog>> = emptyList()
    /** zu welcher Playlist ein Eintrag gehört */
    private val owner = java.util.IdentityHashMap<VodItem, Playlist>()
    /** Playlist des gerade geöffneten Eintrags */
    private var cur: Playlist? = null
    private fun plOf(item: VodItem) = owner[item] ?: parts.firstOrNull()?.first ?: pls.first()
    private fun key(p: Playlist, catId: String) = p.id + "\u0001" + catId
    private var catKeys: List<String> = emptyList()     // "" = alle, "#resume" = weiterschauen, "#search" = Suche
    private var catLabels: List<String> = emptyList()
    private var searchResults: List<VodItem> = emptyList()
    private lateinit var catAdapter: TextAdapter
    private lateinit var gridAdapter: PosterAdapter
    private lateinit var cats: RecyclerView
    private lateinit var grid: RecyclerView
    private var shownCat: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vod)
        store = Store(this)
        series = intent.getBooleanExtra("series", false)
        pls = store.playlists()
        if (pls.isEmpty()) { finish(); return }
        findViewById<TextView>(R.id.vodTitle).text = if (series) "Serien" else "Filme"

        cats = findViewById(R.id.vodCats)
        grid = findViewById(R.id.vodGrid)
        catAdapter = TextAdapter(
            onFocus = { pos -> catKeys.getOrNull(pos)?.let { k -> ui.removeCallbacksAndMessages(null); ui.postDelayed({ showCat(k) }, 150) } },
            onClick = { pos -> catKeys.getOrNull(pos)?.let { showCat(it) }; focusGrid() },
            onLongClick = { pos -> if (catKeys.getOrNull(pos) == "#resume") confirmClearResume() },
            wrap = true
        )
        cats.layoutManager = LinearLayoutManager(this)
        cats.adapter = catAdapter
        cats.itemAnimator = null

        gridAdapter = PosterAdapter(store, onLongClick = { item, pos -> posterOptions(item, pos) }) { open(it) }
        // bei mehreren Playlists: in gemischten Listen zeigen, woher ein Film kommt
        gridAdapter.source = { item -> if (parts.size > 1 && (shownCat == null || shownCat!!.isEmpty() || shownCat in setOf("#new", "#resume", "#search"))) plOf(item).name else null }
        val cols = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 330) / 135).toInt().coerceIn(3, 8)
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.adapter = gridAdapter
        grid.itemAnimator = null

        findViewById<View>(R.id.vodSearch).setOnClickListener { search() }
        findViewById<View>(R.id.vodPlaylist).setOnClickListener { choosePlaylist() }
        updatePlaylistLabel()
        findViewById<View>(R.id.vodManage).setOnClickListener { manageCats() }
        findViewById<View>(R.id.vodFilter).setOnClickListener { sortFilterMenu() }
        sortMode = store.getString("vodSort_" + (if (series) "s" else "m"), "std")
        load()
    }

    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (e.action == android.view.KeyEvent.ACTION_DOWN &&
            (e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
            val f = currentFocus
            val rv = when {
                f != null && cats.findContainingItemView(f) != null -> cats
                f != null && grid.findContainingItemView(f) != null -> grid
                else -> null
            }
            if (rv != null) {
                val up = e.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                // oberste Kategorie + hoch → zu „Suchen“ / „Kategorien“
                if (rv == cats && up && cats.getChildAdapterPosition(cats.findContainingItemView(f!!)!!) == 0)
                    return super.dispatchKeyEvent(e)
                return ListNav.move(rv, f, if (up) -1 else 1, wrap = e.repeatCount == 0)
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onResume() {
        super.onResume()
        if (::gridAdapter.isInitialized) gridAdapter.notifyDataSetChanged() // Fortschrittsbalken aktualisieren
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    private fun updatePlaylistLabel() {
        val v = findViewById<TextView>(R.id.vodPlaylist)
        v.visibility = if (pls.size > 1) View.VISIBLE else View.GONE
        v.text = "⇄ Playlist: " + (pls.firstOrNull { it.id == only }?.name ?: "Alle")
    }

    /** alle Playlists zusammen oder nur eine zeigen */
    private fun choosePlaylist() {
        val labels = listOf((if (only == null) "● " else "    ") + "Alle Playlists zusammen") +
            pls.map { (if (it.id == only) "● " else "    ") + it.name + "   (" + (if (it.type == "xtream") "Xtream" else "M3U") + ")" }
        AlertDialog.Builder(this)
            .setTitle(if (series) "Serien aus welcher Playlist?" else "Filme aus welcher Playlist?")
            .setItems(labels.toTypedArray()) { _, w ->
                val n = if (w == 0) null else pls[w - 1].id
                if (n == only) return@setItems
                only = n
                updatePlaylistLabel()
                searchResults = emptyList(); shownCat = null
                gridAdapter.items = emptyList()
                catAdapter.items = emptyList()
                load()
            }.show()
    }

    private fun loading(text: String?, pct: Int = -1) {
        val box = findViewById<View>(R.id.vodLoading)
        if (text == null) { box.visibility = View.GONE; return }
        box.visibility = View.VISIBLE
        findViewById<TextView>(R.id.vodLoadText).text = text
        val bar = findViewById<ProgressBar>(R.id.vodLoadBar)
        if (pct < 0) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = pct }
    }

    private fun load() {
        loading(if (series) "Serien werden geladen …" else "Filme werden geladen …")
        scope.launch {
            val sel = if (only == null) pls else pls.filter { it.id == only }
            val res = ArrayList<Pair<Playlist, VodCatalog>>()
            val errs = ArrayList<String>()
            for (p in sel) {
                try {
                    val c = Vod.catalog(this@VodActivity, p, series) { t, pc -> runOnUiThread { loading(if (sel.size > 1) "${p.name}: $t" else t, pc) } }
                    if (c.items.isNotEmpty()) res.add(p to c)
                } catch (e: Exception) { errs.add("${p.name}: ${e.message}") }
            }
            liveAllowed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                res.associate { (p, _) -> p.id to Repo.liveCountries(this@VodActivity, p.id) }
            }
            loading(null)
            parts = res
            owner.clear()
            res.forEach { (p, c) -> c.items.forEach { owner[it] = p } }
            if (res.isEmpty()) {
                AlertDialog.Builder(this@VodActivity)
                    .setTitle(if (errs.isNotEmpty()) "Laden fehlgeschlagen" else if (series) "Keine Serien" else "Keine Filme")
                    .setMessage(errs.joinToString("\n\n").ifBlank { "Die Playlist enthält keine ${if (series) "Serien" else "Filme"}." })
                    .setPositiveButton(if (pls.size > 1) "Andere Playlist" else "Nochmal") { _, _ -> if (pls.size > 1) choosePlaylist() else load() }
                    .setNegativeButton("Zurück") { _, _ -> finish() }.setCancelable(false).show()
                return@launch
            }
            if (errs.isNotEmpty()) Toast.makeText(this@VodActivity, "Nicht geladen – ${errs.joinToString(" · ")}", Toast.LENGTH_LONG).show()
            buildCats()
            showCat(catKeys.first())
            cats.post { cats.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    /** im Live-TV gewählte Länder je Playlist (null = alle) */
    private var liveAllowed: Map<String, Set<String>?> = emptyMap()

    /** Kategorie ausgeblendet: selbst ausgeblendet oder Land im Live-TV abgewählt (ohne Land bleibt sichtbar) */
    private fun isHidden(p: Playlist, name: String, hid: Set<String>): Boolean {
        if (name in hid) return true
        val allowed = liveAllowed[p.id] ?: return false
        val c = Countries.code(name)
        return c != "?" && c !in allowed
    }

    private fun hiddenKey(p: Playlist) = p.id + if (series) ":series" else ":movies"
    private var visibleItems: List<VodItem> = emptyList()

    private fun manageCats() {
        if (parts.isEmpty()) return
        if (parts.size == 1) { manageCats(parts[0].first, parts[0].second); return }
        AlertDialog.Builder(this).setTitle("Kategorien welcher Playlist?")
            .setItems(parts.map { it.first.name }.toTypedArray()) { _, w -> manageCats(parts[w].first, parts[w].second) }.show()
    }

    private fun manageCats(p: Playlist, catalog: VodCatalog) {
        if (catalog.categories.isEmpty()) return
        val names = catalog.categories.map { it.name }
        val countByName = catalog.categories.associate { c -> c.name to catalog.items.count { it.categoryId == c.id } }
        Dialogs.manageGroups(this, names, { countByName[it] ?: 0 }, store.hiddenGroups(hiddenKey(p)),
            (if (series) "Serien" else "Filme") + if (parts.size > 1) " · ${p.name}" else "") { hide ->
            store.setHiddenGroups(hiddenKey(p), hide)
            shownCat = null
            buildCats()
            showCat(catKeys.firstOrNull { it == "" } ?: catKeys.first())
            Toast.makeText(this, "${catalog.categories.size - hide.size} von ${catalog.categories.size} Kategorien sichtbar", Toast.LENGTH_SHORT).show()
        }
    }

    /** Kategorie-Id → Einträge (einmal pro Aufbau, statt bei jeder Kategorie alles zu durchsuchen) */
    private var byCat: Map<String, List<VodItem>> = emptyMap()
    private var resumeCache: List<VodItem> = emptyList()
    private var newCache: List<VodItem> = emptyList()
    /** „Zuletzt hinzugefügt“ je Playlist */
    private var newByPl: Map<String, List<VodItem>> = emptyMap()
    private fun newest(l: List<VodItem>): List<VodItem> {
        val hasDate = l.any { it.added > 0 }
        return l.sortedByDescending { if (hasDate) it.added else (it.id.toLongOrNull() ?: 0L) }.take(200)
    }
    private val sortedCache = HashMap<String, List<VodItem>>()

    private fun buildCats() {
        val multi = parts.size > 1
        val vis = ArrayList<VodItem>()
        val bc = HashMap<String, List<VodItem>>()
        // Kategorien je Playlist: (Schlüssel, Name, Playlist)
        val catRows = ArrayList<Triple<String, String, Playlist>>()
        for ((p, catalog) in parts) {
            val hk = hiddenKey(p)
            store.applyAllowedCountries(hk, catalog.categories.map { it.name })
            val hid = store.hiddenGroups(hk)
            val hiddenIds = catalog.categories.filter { isHidden(p, it.name, hid) }.map { it.id }.toSet()
            val v = if (hiddenIds.isEmpty()) catalog.items else catalog.items.filter { it.categoryId !in hiddenIds }
            vis.addAll(v)
            v.groupBy { it.categoryId }.forEach { (id, l) -> bc[key(p, id)] = l }
            catalog.categories.filter { !isHidden(p, it.name, hid) }.forEach { c -> catRows.add(Triple(key(p, c.id), c.name, p)) }
        }
        visibleItems = vis
        byCat = bc
        resumeCache = if (series) emptyList() else store.resumeHashes().let { hs ->
            if (hs.isEmpty()) emptyList() else parts.flatMap { it.second.items }.filter { it.url != null && it.url.hashCode() in hs }
        }
        // „Zuletzt hinzugefügt“: Datum vom Anbieter, sonst die Nummer (neuere Einträge haben höhere Nummern)
        newCache = newest(visibleItems)
        newByPl = if (multi) parts.associate { (p, _) -> p.id to newest(visibleItems.filter { owner[it] === p }) } else emptyMap()
        sortedCache.clear()
        val filtering = yearFrom != null || yearBefore != null || minRating > 0
        val counts: Map<String, Int> = if (filtering) byCat.mapValues { e -> e.value.count { passes(it) } } else byCat.mapValues { it.value.size }
        val keys = ArrayList<String>(); val labels = ArrayList<String>(); val nums = ArrayList<String>(); val heads = ArrayList<String?>()
        if (resumeCache.isNotEmpty()) { keys.add("#resume"); labels.add("▶ Weiterschauen"); nums.add(resumeCache.size.toString()); heads.add(null) }
        if (searchResults.isNotEmpty()) { keys.add("#search"); labels.add("🔍 Suchergebnis"); nums.add(searchResults.size.toString()); heads.add(null) }
        if (newCache.isNotEmpty()) { keys.add("#new"); labels.add(if (multi) "🆕 Zuletzt hinzugefügt (alle)" else "🆕 Zuletzt hinzugefügt"); nums.add(newCache.size.toString()); heads.add(null) }
        // direkt darunter: Neuheiten je Playlist
        if (multi) parts.forEach { (p, _) ->
            val nl = newByPl[p.id].orEmpty()
            if (nl.isNotEmpty()) { keys.add("#new:" + p.id); labels.add("🆕 Zuletzt hinzugefügt (${p.name})"); nums.add(nl.size.toString()); heads.add(null) }
        }
        keys.add(""); labels.add(if (multi) "Alle (${parts.joinToString(" + ") { it.first.name }})" else "Alle")
        nums.add((if (filtering) counts.values.sum() else visibleItems.size).toString()); heads.add(null)
        var lastPl: String? = null
        catRows.forEach { (k, name, p) ->
            val n = counts[k] ?: 0
            if (filtering && n == 0) return@forEach   // beim Filtern leere Kategorien ausblenden
            keys.add(k); labels.add(name); nums.add(n.toString())
            heads.add(if (multi && p.id != lastPl) "📡  ${p.name.uppercase()}" else null)
            lastPl = p.id
        }
        catKeys = keys; catLabels = labels
        catAdapter.items = keys.indices.map { i ->
            val k = keys[i]
            TextItem(if (k.isEmpty() || k.startsWith("#")) labels[i] else Countries.pretty(labels[i]), nums[i], heads[i])
        }
    }

    private fun resumeItems(): List<VodItem> = resumeCache

    private fun itemsOf(cat: String): List<VodItem> {
        sortedCache[cat]?.let { return it }
        val r = when (cat) {
            "#resume" -> resumeCache
            "#new" -> newCache
            else -> if (cat.startsWith("#new:")) newByPl[cat.removePrefix("#new:")].orEmpty() else null
        } ?: when (cat) {
            "#search" -> applySortFilter(searchResults)
            "" -> applySortFilter(visibleItems)
            else -> applySortFilter(byCat[cat] ?: emptyList())
        }
        sortedCache[cat] = r
        return r
    }

    // ---------- Sortieren & Filtern ----------

    private var sortMode = "std"          // std, new, year, rating, name
    private var yearFrom: Int? = null      // „ab Jahr“: dieses Jahr und neuer
    private var yearBefore: Int? = null    // „vor Jahr“
    private var minRating = 0.0

    private fun applySortFilter(src: List<VodItem>): List<VodItem> {
        val l = if (yearFrom != null || yearBefore != null || minRating > 0) src.filter { passes(it) } else src
        return when (sortMode) {
            "new" -> l.sortedByDescending { it.added }
            "year" -> l.sortedWith(compareByDescending<VodItem> { it.yearNum ?: 0 }.thenByDescending { it.added })
            "rating" -> l.sortedByDescending { it.ratingNum }
            "name" -> l.sortedBy { it.name.lowercase() }
            else -> if (store.sortByName) l.sortedBy { it.name.lowercase() } else l
        }
    }

    private fun passes(it: VodItem): Boolean {
        yearFrom?.let { y -> if ((it.yearNum ?: 0) < y) return false }
        yearBefore?.let { y -> val n = it.yearNum ?: return false; if (n >= y) return false }
        if (minRating > 0 && it.ratingNum < minRating) return false
        return true
    }

    private fun yearLabel() = yearFrom?.let { "ab $it" } ?: yearBefore?.let { "vor $it" }

    private fun sortLabel() = when (sortMode) {
        "new" -> "Zuletzt hinzugefügt"; "year" -> "Neueste zuerst (Jahr)"; "rating" -> "Beste Bewertung"; "name" -> "Name A–Z"; else -> "Standard"
    }

    private fun filterInfo(): String {
        val parts = ArrayList<String>()
        if (sortMode != "std") parts.add(sortLabel())
        yearLabel()?.let { parts.add(it) }
        if (minRating > 0) parts.add("★ ab ${minRating.toInt()}")
        return if (parts.isEmpty()) "" else "  ·  " + parts.joinToString(" · ")
    }

    private fun sortFilterMenu() {
        val y = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val items = arrayOf(
            "Sortierung: ${sortLabel()} …",
            "Jahr: ${yearLabel() ?: "alle"} …",
            "Bewertung: ${if (minRating > 0) "ab ★ ${minRating.toInt()}" else "alle"} …",
            "✕ Alles zurücksetzen"
        )
        AlertDialog.Builder(this).setTitle("⇅ Sortieren & Filtern")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> {
                        val modes = listOf("std" to "Standard", "new" to "🆕 Zuletzt hinzugefügt", "year" to "📅 Neueste zuerst (Jahr)", "rating" to "★ Beste Bewertung", "name" to "🔤 Name A–Z")
                        AlertDialog.Builder(this).setTitle("Sortierung")
                            .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), modes.indexOfFirst { it.first == sortMode }) { d, i ->
                                sortMode = modes[i].first
                                store.putString("vodSort_" + (if (series) "s" else "m"), sortMode)
                                d.dismiss(); refilter()
                            }.show()
                    }
                    1 -> {
                        // „ab Jahr“ = dieses Jahr und alle neueren; ältere Filme bleiben über „Alle Jahre“ sichtbar
                        val opts = listOf<Triple<String, Int?, Int?>>(
                            Triple("Alle Jahre", null, null), Triple("ab $y", y, null), Triple("ab ${y - 1}", y - 1, null),
                            Triple("ab ${y - 3}", y - 3, null), Triple("ab ${y - 5}", y - 5, null), Triple("ab 2010", 2010, null),
                            Triple("ab 2000", 2000, null), Triple("vor 2000 (Klassiker)", null, 2000))
                        AlertDialog.Builder(this).setTitle("Erscheinungsjahr")
                            .setSingleChoiceItems(opts.map { it.first }.toTypedArray(), opts.indexOfFirst { it.second == yearFrom && it.third == yearBefore }.coerceAtLeast(0)) { d, i ->
                                yearFrom = opts[i].second; yearBefore = opts[i].third; d.dismiss(); refilter()
                            }.show()
                    }
                    2 -> {
                        val rs = listOf(0.0 to "Alle", 5.0 to "ab ★ 5", 6.0 to "ab ★ 6", 7.0 to "ab ★ 7", 8.0 to "ab ★ 8")
                        AlertDialog.Builder(this).setTitle("Bewertung")
                            .setSingleChoiceItems(rs.map { it.second }.toTypedArray(), rs.indexOfFirst { it.first == minRating }.coerceAtLeast(0)) { d, i ->
                                minRating = rs[i].first; d.dismiss(); refilter()
                            }.show()
                    }
                    3 -> { sortMode = "std"; yearFrom = null; yearBefore = null; minRating = 0.0; store.putString("vodSort_" + (if (series) "s" else "m"), "std"); refilter() }
                }
            }.show()
    }

    /** aktuelle Kategorie mit neuer Sortierung/Filter anzeigen */
    private fun refilter() {
        val cat = shownCat ?: ""
        shownCat = null
        buildCats()
        showCat(if (cat in catKeys) cat else "")
        val n = gridAdapter.itemCount
        if (n == 0) Toast.makeText(this, "Keine Treffer – Filter lockern", Toast.LENGTH_LONG).show()
        focusGrid()
    }

    private fun showCat(cat: String) {
        if (cat == shownCat) return
        shownCat = cat
        val l = itemsOf(cat)
        gridAdapter.items = l
        grid.scrollToPosition(0)
        catAdapter.activePos = catKeys.indexOf(cat)
        val lab = catLabels.getOrNull(catKeys.indexOf(cat)) ?: ""
        findViewById<TextView>(R.id.vodHeader).text = "${if (cat.isEmpty() || cat.startsWith("#")) lab else Countries.pretty(lab)}  ·  ${l.size} ${if (series) "Serien" else "Filme"}${if (cat == "#resume" || cat.startsWith("#new")) "" else filterInfo()}"
    }

    // ---------- Weiterschauen bearbeiten ----------

    /** OK lang auf ein Poster */
    private fun posterOptions(item: VodItem, pos: Int) {
        val url = item.url
        val hasResume = url != null && store.resumePos(url) > 0
        val opts = ArrayList<Pair<String, () -> Unit>>()
        opts.add("▶ Abspielen" to { open(item) })
        if (hasResume) {
            opts.add("✕ Aus „Weiterschauen“ entfernen" to { removeResume(item, pos) })
            opts.add("⟲ Von vorne abspielen" to { store.setResumePos(url!!, 0, 0); refreshAfterResume(pos, false); open(item) })
        }
        if (shownCat == "#resume") opts.add("🗑 Ganze Liste „Weiterschauen“ leeren" to { confirmClearResume() })
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, w -> opts[w].second() }
            .show()
    }

    private fun removeResume(item: VodItem, pos: Int) {
        val url = item.url ?: return
        store.setResumePos(url, 0, 0)
        refreshAfterResume(pos, true)
        Toast.makeText(this, "„${item.name}“ entfernt", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearResume() {
        val l = resumeItems()
        if (l.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("„Weiterschauen“ leeren?")
            .setMessage("${l.size} ${if (l.size == 1) "Film wird" else "Filme werden"} aus der Liste entfernt. Die Filme selbst bleiben erhalten.")
            .setPositiveButton("Leeren") { _, _ ->
                l.forEach { it.url?.let { u -> store.setResumePos(u, 0, 0) } }
                refreshAfterResume(0, true)
                Toast.makeText(this, "„Weiterschauen“ geleert", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Liste und Zähler neu aufbauen, Markierung bleibt möglichst an der Stelle */
    private fun refreshAfterResume(pos: Int, refocus: Boolean) {
        val wasResume = shownCat == "#resume"
        buildCats()
        if (wasResume) {
            val l = resumeItems()
            if (l.isEmpty()) {
                shownCat = null
                showCat("")
                cats.post { cats.findViewHolderForAdapterPosition(catKeys.indexOf(""))?.itemView?.requestFocus() }
                return
            }
            gridAdapter.items = l
            catAdapter.activePos = catKeys.indexOf("#resume")
            findViewById<TextView>(R.id.vodHeader).text = "▶ Weiterschauen  ·  ${l.size} Filme"
            if (refocus) {
                val p = pos.coerceAtMost(l.size - 1)
                grid.post { grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
            }
        } else gridAdapter.notifyDataSetChanged()
    }

    private fun focusGrid() {
        if (gridAdapter.itemCount == 0) return
        grid.scrollToPosition(0)
        grid.post { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun search() {
        val input = EditText(this).apply { hint = "Titel"; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH }
        val dlg = AlertDialog.Builder(this).setTitle(if (series) "Serie suchen" else "Film suchen").setView(input)
            .setPositiveButton("Suchen") { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton("Abbrechen", null).create()
        input.setOnEditorActionListener { _, _, _ -> dlg.dismiss(); runSearch(input.text.toString()); true }
        dlg.show()
    }

    private fun runSearch(q: String) {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return
        searchResults = visibleItems.filter { it.name.lowercase().contains(query) }
        if (searchResults.isEmpty()) { Toast.makeText(this, "Nichts gefunden für „$q“", Toast.LENGTH_SHORT).show(); return }
        buildCats()
        shownCat = null
        showCat("#search")
        focusGrid()
    }

    // ---------- Öffnen ----------

    private fun open(item: VodItem) {
        cur = plOf(item)
        if (item.isSeries) openSeries(item) else openMovie(item)
    }

    private fun openMovie(item: VodItem) {
        val url = item.url ?: return
        val dlg = AlertDialog.Builder(this).setTitle(item.name).setMessage("Infos werden geladen …").create()
        fun buttons(msg: String) {
            dlg.setMessage(msg)
            val pos = store.resumePos(url)
            if (pos > 0) {
                dlg.setButton(AlertDialog.BUTTON_POSITIVE, "▶ Fortsetzen (${fmt(pos)})") { _, _ -> play(item.name, url, pos) }
                dlg.setButton(AlertDialog.BUTTON_NEUTRAL, "Von vorne") { _, _ -> play(item.name, url, 0) }
            } else {
                dlg.setButton(AlertDialog.BUTTON_POSITIVE, "▶ Abspielen") { _, _ -> play(item.name, url, 0) }
            }
            dlg.setButton(AlertDialog.BUTTON_NEGATIVE, "Zurück") { _, _ -> }
        }
        buttons("Infos werden geladen …")
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        scope.launch {
            val info = Vod.movieInfo(this@VodActivity, plOf(item), item)
            val parts = ArrayList<String>()
            listOf(info?.year?.ifBlank { item.year } ?: item.year, info?.genre ?: "", info?.duration ?: "",
                item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "")
                .filter { it.isNotBlank() }.joinToString("  ·  ").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            info?.plot?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            info?.director?.takeIf { it.isNotBlank() }?.let { parts.add("Regie: $it") }
            info?.cast?.takeIf { it.isNotBlank() }?.let { parts.add("Mit: ${it.take(200)}") }
            if (dlg.isShowing) dlg.setMessage(parts.joinToString("\n\n").ifBlank { "Keine weiteren Infos" })
        }
    }

    private fun openSeries(item: VodItem) {
        loading("„${item.name}“ wird geladen …")
        scope.launch {
            val eps = try { Vod.episodes(this@VodActivity, plOf(item), item) } catch (e: Exception) {
                loading(null); Toast.makeText(this@VodActivity, "Folgen konnten nicht geladen werden: ${e.message}", Toast.LENGTH_LONG).show(); return@launch
            }
            loading(null)
            if (eps.isEmpty()) { Toast.makeText(this@VodActivity, "Keine Folgen gefunden", Toast.LENGTH_SHORT).show(); return@launch }
            val seasons = eps.map { it.season }.distinct().sorted()
            if (seasons.size == 1) pickEpisode(item, eps) else {
                AlertDialog.Builder(this@VodActivity)
                    .setTitle(item.name + (item.plot.takeIf { it.isNotBlank() }?.let { "" } ?: ""))
                    .setItems(seasons.map { s -> "Staffel $s   (${eps.count { it.season == s }} Folgen)" }.toTypedArray()) { _, w ->
                        pickEpisode(item, eps.filter { it.season == seasons[w] })
                    }.show()
            }
        }
    }

    private fun pickEpisode(item: VodItem, eps: List<Episode>) {
        val labels = eps.map { e ->
            val pos = store.resumePos(e.url); val dur = store.resumeDur(e.url)
            val mark = when { pos <= 0 -> ""; dur > 0 && pos > dur * 0.9 -> "  ✓"; else -> "  ▶ ${fmt(pos)}" }
            "S${e.season} E${e.number}  ${e.title.ifBlank { "Folge ${e.number}" }}$mark"
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(labels.toTypedArray()) { _, w ->
                val e = eps[w]
                val pos = store.resumePos(e.url)
                val title = "${item.name} · S${e.season} E${e.number}"
                if (pos > 0) {
                    AlertDialog.Builder(this).setTitle(title)
                        .setItems(arrayOf("▶ Fortsetzen (${fmt(pos)})", "Von vorne")) { _, a -> play(title, e.url, if (a == 0) pos else 0, eps, w) }
                        .show()
                } else play(title, e.url, 0, eps, w)
            }.show()
    }

    private fun play(title: String, url: String, pos: Long, eps: List<Episode>? = null, idx: Int = -1) {
        val i = Intent(this, VodPlayerActivity::class.java)
            .putExtra("title", title).putExtra("url", url).putExtra("pos", pos)
            .putExtra("pid", (cur ?: pls.first()).id)
        if (eps != null && idx >= 0 && idx + 1 < eps.size) {
            // nächste Folge für „Weiter“
            val n = eps[idx + 1]
            i.putExtra("nextUrl", n.url).putExtra("nextTitle", title.substringBefore(" · S") + " · S${n.season} E${n.number}")
        }
        startActivity(i)
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60000; return if (m >= 60) "${m / 60}:${String.format("%02d", m % 60)} h" else "$m min"
    }
}

class PosterAdapter(private val store: Store, private val onLongClick: (VodItem, Int) -> Unit = { _, _ -> }, private val onClick: (VodItem) -> Unit) : RecyclerView.Adapter<PosterAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val fallback: TextView = v.findViewById(R.id.posterFallback)
        val title: TextView = v.findViewById(R.id.posterTitle)
        val badge: TextView = v.findViewById(R.id.badge)
        val bar: ProgressBar = v.findViewById(R.id.resumeBar)
    }

    var items: List<VodItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    /** kleine Quelle vor dem Titel („pro · …“), wenn mehrere Playlists zusammen angezeigt werden */
    var source: ((VodItem) -> String?)? = null

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_poster, p, false))
    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val src = source?.invoke(item)
        h.title.text = if (src != null) android.text.SpannableString("$src · ${item.name}").apply {
            setSpan(android.text.style.ForegroundColorSpan(0xFF8FA8FF.toInt()), 0, src.length, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, src.length, 0)
        } else item.name
        h.fallback.text = item.name
        h.poster.load(item.poster) { crossfade(false); size(240, 360) }
        val r = item.rating.toDoubleOrNull()
        h.badge.visibility = if (r != null && r > 0) View.VISIBLE else View.GONE
        h.badge.text = r?.let { v -> "★ " + String.format(java.util.Locale.GERMANY, "%.1f", if (v > 10) v / 10 else v) } ?: ""
        val url = item.url
        val p = if (url != null) store.resumePos(url) else 0
        val d = if (url != null) store.resumeDur(url) else 0
        if (p > 0 && d > 0) { h.bar.visibility = View.VISIBLE; h.bar.progress = (p * 1000 / d).toInt() } else h.bar.visibility = View.GONE
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnLongClickListener { onLongClick(item, h.bindingAdapterPosition); true }
    }
}
