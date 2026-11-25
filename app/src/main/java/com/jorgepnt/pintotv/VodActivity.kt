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
    private lateinit var playlist: Playlist
    private var series = false
    private var catalog = VodCatalog(emptyList(), emptyList())
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
        playlist = store.active() ?: run { finish(); return }
        findViewById<TextView>(R.id.vodTitle).text = if (series) "Serien" else "Filme"

        cats = findViewById(R.id.vodCats)
        grid = findViewById(R.id.vodGrid)
        catAdapter = TextAdapter(
            onFocus = { pos -> catKeys.getOrNull(pos)?.let { k -> ui.removeCallbacksAndMessages(null); ui.postDelayed({ showCat(k) }, 150) } },
            onClick = { pos -> catKeys.getOrNull(pos)?.let { showCat(it) }; focusGrid() },
            wrap = true
        )
        cats.layoutManager = LinearLayoutManager(this)
        cats.adapter = catAdapter
        cats.itemAnimator = null

        gridAdapter = PosterAdapter(store) { open(it) }
        val cols = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 330) / 135).toInt().coerceIn(3, 8)
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.adapter = gridAdapter
        grid.itemAnimator = null

        findViewById<View>(R.id.vodSearch).setOnClickListener { search() }
        findViewById<View>(R.id.vodPlaylist).setOnClickListener { choosePlaylist() }
        updatePlaylistLabel()
        findViewById<View>(R.id.vodManage).setOnClickListener { manageCats() }
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
        findViewById<TextView>(R.id.vodPlaylist).text = "⇄ Playlist: ${playlist.name}"
    }

    /** andere Playlist für Filme/Serien wählen */
    private fun choosePlaylist() {
        val pls = store.playlists()
        AlertDialog.Builder(this)
            .setTitle("Filme/Serien aus welcher Playlist?")
            .setItems(pls.map { (if (it.id == playlist.id) "● " else "    ") + it.name + "   (" + (if (it.type == "xtream") "Xtream" else "M3U") + ")" }.toTypedArray()) { _, w ->
                val p = pls[w]
                if (p.id == playlist.id) return@setItems
                if (p.type != "xtream" && p.id != Repo.playlistId) {
                    Toast.makeText(this, "Bei M3U-Playlists bitte zuerst im Live-TV auf „${p.name}“ wechseln", Toast.LENGTH_LONG).show()
                    return@setItems
                }
                playlist = p
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
            try {
                catalog = Vod.catalog(this@VodActivity, playlist, series) { t, p -> runOnUiThread { loading(t, p) } }
            } catch (e: Exception) {
                loading(null)
                AlertDialog.Builder(this@VodActivity).setTitle("Laden fehlgeschlagen").setMessage(e.message)
                    .setPositiveButton("Andere Playlist") { _, _ -> choosePlaylist() }
                    .setNegativeButton("Zurück") { _, _ -> finish() }.setCancelable(false).show()
                return@launch
            }
            loading(null)
            if (catalog.items.isEmpty()) {
                AlertDialog.Builder(this@VodActivity)
                    .setTitle(if (series) "Keine Serien" else "Keine Filme")
                    .setMessage("Die Playlist „${playlist.name}“ enthält keine ${if (series) "Serien" else "Filme"}.")
                    .setPositiveButton("Andere Playlist") { _, _ -> choosePlaylist() }
                    .setNegativeButton("Zurück") { _, _ -> finish() }.setCancelable(false).show()
                return@launch
            }
            buildCats()
            showCat(catKeys.first())
            cats.post { cats.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        }
    }

    private val hiddenKey get() = playlist.id + if (series) ":series" else ":movies"
    private var hidden: Set<String> = emptySet()
    private var visibleItems: List<VodItem> = emptyList()

    private fun manageCats() {
        if (catalog.categories.isEmpty()) return
        val names = catalog.categories.map { it.name }
        val countByName = catalog.categories.associate { c -> c.name to catalog.items.count { it.categoryId == c.id } }
        Dialogs.manageGroups(this, names, { countByName[it] ?: 0 }, store.hiddenGroups(hiddenKey),
            if (series) "Serien" else "Filme") { hide ->
            store.setHiddenGroups(hiddenKey, hide)
            shownCat = null
            buildCats()
            showCat(catKeys.firstOrNull { it == "" } ?: catKeys.first())
            Toast.makeText(this, "${catalog.categories.size - hide.size} von ${catalog.categories.size} Kategorien sichtbar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildCats() {
        store.applyAllowedCountries(hiddenKey, catalog.categories.map { it.name })
        hidden = store.hiddenGroups(hiddenKey)
        val hiddenIds = catalog.categories.filter { it.name in hidden }.map { it.id }.toSet()
        visibleItems = if (hiddenIds.isEmpty()) catalog.items else catalog.items.filter { it.categoryId !in hiddenIds }
        val keys = ArrayList<String>(); val labels = ArrayList<String>()
        if (resumeItems().isNotEmpty()) { keys.add("#resume"); labels.add("▶ Weiterschauen") }
        if (searchResults.isNotEmpty()) { keys.add("#search"); labels.add("🔍 Suchergebnis") }
        keys.add(""); labels.add("Alle")
        catalog.categories.filter { it.name !in hidden }.forEach { keys.add(it.id); labels.add(it.name) }
        catKeys = keys; catLabels = labels
        catAdapter.items = keys.indices.map { i -> TextItem(labels[i], itemsOf(keys[i]).size.toString()) }
    }

    private fun resumeItems(): List<VodItem> =
        if (series) emptyList() else catalog.items.filter { it.url != null && store.resumePos(it.url) > 0 }

    private fun itemsOf(cat: String): List<VodItem> {
        val l = when (cat) {
            "" -> visibleItems
            "#resume" -> resumeItems()
            "#search" -> searchResults
            else -> catalog.items.filter { it.categoryId == cat }
        }
        return if (store.sortByName && cat != "#resume") l.sortedBy { it.name.lowercase() } else l
    }

    private fun showCat(cat: String) {
        if (cat == shownCat) return
        shownCat = cat
        val l = itemsOf(cat)
        gridAdapter.items = l
        grid.scrollToPosition(0)
        catAdapter.activePos = catKeys.indexOf(cat)
        findViewById<TextView>(R.id.vodHeader).text = "${catLabels.getOrNull(catKeys.indexOf(cat)) ?: ""}  ·  ${l.size} ${if (series) "Serien" else "Filme"}"
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
            val info = Vod.movieInfo(this@VodActivity, playlist, item)
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
            val eps = try { Vod.episodes(this@VodActivity, playlist, item) } catch (e: Exception) {
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
            .putExtra("pid", playlist.id)
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

class PosterAdapter(private val store: Store, private val onClick: (VodItem) -> Unit) : RecyclerView.Adapter<PosterAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val fallback: TextView = v.findViewById(R.id.posterFallback)
        val title: TextView = v.findViewById(R.id.posterTitle)
        val badge: TextView = v.findViewById(R.id.badge)
        val bar: ProgressBar = v.findViewById(R.id.resumeBar)
    }

    var items: List<VodItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_poster, p, false))
    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.title.text = item.name
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
    }
}
