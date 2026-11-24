package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlayerActivity : Activity() {

    companion object {
        const val G_FAV = "★ Favoriten"
        const val G_RECENT = "Zuletzt gesehen"
        const val G_ALL = "Alle Kanäle"
        const val REQ_GUIDE = 1
        const val REQ_PLAYLIST = 2
        const val REQ_MULTI = 3
    }

    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var player: ExoPlayer

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var numberEntry: TextView
    private lateinit var banner: View
    private lateinit var overlay: View
    private lateinit var menuPane: View
    private lateinit var menuList: RecyclerView
    private lateinit var groupList: RecyclerView
    private lateinit var channelList: RecyclerView

    private lateinit var menuAdapter: TextAdapter
    private lateinit var groupAdapter: TextAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var playlist: Playlist? = null
    private var current: Channel? = null
    private var previous: Channel? = null
    private var zapList: List<Channel> = emptyList()
    private var groupKeys: List<String> = emptyList()
    private var viewingGroup = G_ALL
    private var playingGroup = G_ALL
    private var searchResults: List<Channel> = emptyList()
    private var searchKey: String? = null
    private var retries = 0
    private var lastBack = 0L
    private var digits = ""
    private var loading = false

    // ---------- Lebenszyklus ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        store = Store(this)

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        numberEntry = findViewById(R.id.numberEntry)
        banner = findViewById(R.id.banner)
        overlay = findViewById(R.id.overlay)
        menuPane = findViewById(R.id.menuPane)
        menuList = findViewById(R.id.menuList)
        groupList = findViewById(R.id.groupList)
        channelList = findViewById(R.id.channelList)

        initPlayer()
        initLists()
        initTouch()
        val v = appVersion(this)
        findViewById<TextView>(R.id.menuTitle).text = "PintoTV $v"
        findViewById<TextView>(R.id.overlayVersion).text = "PintoTV $v"
        findViewById<TextView>(R.id.loadVersion).text = "PintoTV $v"
        findViewById<TextView>(R.id.versionTag).text = "PintoTV $v"

        if (store.playlists().isEmpty()) {
            showStatus("Willkommen bei PintoTV ${appVersion(this)}!\nBitte zuerst eine Playlist hinzufügen.")
            startActivityForResult(Intent(this, PlaylistActivity::class.java), REQ_PLAYLIST)
        } else {
            load(false)
        }
    }

    override fun onStart() {
        super.onStart()
        current?.let { startStream(it) }
    }

    override fun onStop() {
        super.onStop()
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        scope.cancel()
        player.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PLAYLIST -> {
                val p = store.active()
                if (p == null) {
                    player.stop(); current = null; playlist = null
                    showStatus("Keine Playlist vorhanden.\nOK oder Menü drücken → Playlists")
                }
                else if (p.id != playlist?.id || resultCode == RESULT_OK) load(resultCode == RESULT_OK)
            }
            REQ_GUIDE, REQ_MULTI -> {
                val url = data?.getStringExtra("url") ?: return
                val pid = data.getStringExtra("pid")
                if (pid != null && pid != playlist?.id) {
                    // Sender aus einer anderen Playlist im Vollbild: Playlist wechseln
                    store.setLastUrl(pid, url)
                    store.activeId = pid
                    load(false)
                    return
                }
                val ch = Repo.channels.firstOrNull { it.url == url } ?: return
                playingGroup = viewingGroup
                play(ch, channelsOf(viewingGroup))
            }
        }
    }

    // ---------- Touch (Handy/Tablet) ----------

    private val isTouchDevice by lazy {
        packageManager.hasSystemFeature("android.hardware.touchscreen") &&
            !packageManager.hasSystemFeature("android.software.leanback")
    }

    private fun initTouch() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { showOverlay(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { showBanner(); return true }
            override fun onLongPress(e: MotionEvent) { showOverlay("menu") }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 80) { zap(if (dy < 0) 1 else -1); return true }
                if (dx > 120) { showOverlay("groups"); return true }
                return false
            }
        })
        playerView.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
        // Tippen neben die Listen schließt die Kanalliste
        overlay.isClickable = true
        overlay.setOnClickListener { hideOverlay() }
        overlay.isFocusable = false
    }

    // ---------- Player ----------

    private fun initPlayer() {
        player = Players.create(this)
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retries = 0; if (!loading) hideStatus() }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                val ch = current ?: return
                if (retries < 3) {
                    retries++
                    showStatus("Verbindung unterbrochen – neuer Versuch ($retries/3) …")
                    ui.postDelayed({ if (current == ch) startStream(ch) }, 2500)
                } else {
                    showStatus("${ch.name}\nSender nicht verfügbar\n(${error.errorCodeName})")
                }
            }
        })
    }

    private fun startStream(c: Channel) {
        player.setMediaItem(Players.item(c.url))
        player.prepare()
        player.playWhenReady = true
    }

    private fun play(c: Channel, list: List<Channel>) {
        val pid = playlist?.id ?: return
        if (current?.url != c.url) previous = current
        current = c
        zapList = list.ifEmpty { Repo.channels }
        retries = 0
        hideStatus()
        store.setLastUrl(pid, c.url)
        store.addRecent(pid, c.url)
        store.lastGroup = playingGroup
        startStream(c)
        channelAdapter.playingUrl = c.url
        channelAdapter.notifyDataSetChanged()
        showBanner()
    }

    private fun zap(step: Int) {
        val list = zapList.ifEmpty { visible.ifEmpty { Repo.channels } }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.url == current?.url }
        val n = if (i < 0) 0 else ((i + step) % list.size + list.size) % list.size
        play(list[n], list)
    }

    // ---------- Laden ----------

    private fun load(force: Boolean) {
        val p = store.active() ?: return
        playlist = p
        loading = true
        hideStatus()
        showLoading(if (force) "„${p.name}“ wird aktualisiert" else "„${p.name}“ wird geladen", "Starte …", -1)
        scope.launch {
            try {
                Repo.loadChannels(this@PlayerActivity, p, store.streamFormat, force) { step, pct ->
                    runOnUiThread { showLoading(null, step, pct) }
                }
            } catch (e: Exception) {
                loading = false
                hideLoading()
                showStatus("Playlist konnte nicht geladen werden:\n${e.message}\n\nMenü → Playlists → Playlist lang drücken → Player-Kennung wählen")
                return@launch
            }
            loading = false
            hideLoading()
            hideStatus()
            store.migrateFavorites(p.id, Repo.channels)
            buildGroups()
            toast("${Repo.channels.size} Sender in ${Repo.groups.size} Gruppen geladen")
            val last = store.lastUrl(p.id)
            playingGroup = store.lastGroup?.takeIf { it in groupKeys } ?: G_ALL
            viewingGroup = playingGroup
            val list = channelsOf(playingGroup)
            val ch = Repo.channels.firstOrNull { it.url == last } ?: list.firstOrNull() ?: Repo.channels.firstOrNull()
            if (ch != null) play(ch, if (ch in list) list else Repo.channels)
            loadEpg(force)
        }
    }

    private fun showLoading(title: String?, step: String, pct: Int) {
        findViewById<View>(R.id.loadPane).visibility = View.VISIBLE
        findViewById<View>(R.id.versionTag).visibility = View.VISIBLE
        title?.let { findViewById<TextView>(R.id.loadTitle).text = it }
        findViewById<TextView>(R.id.loadStep).text = step
        val bar = findViewById<ProgressBar>(R.id.loadBar)
        val pctView = findViewById<TextView>(R.id.loadPercent)
        if (pct < 0) { bar.isIndeterminate = true; pctView.text = "" }
        else { bar.isIndeterminate = false; bar.progress = pct; pctView.text = "$pct %" }
    }

    private fun hideLoading() {
        findViewById<View>(R.id.loadPane).visibility = View.GONE
        findViewById<View>(R.id.versionTag).visibility = View.GONE
    }

    private fun loadEpg(force: Boolean) {
        val chip = findViewById<TextView>(R.id.epgChip)
        scope.launch {
            try {
                Repo.loadEpg(this@PlayerActivity, force) { step, pct ->
                    runOnUiThread {
                        chip.visibility = View.VISIBLE
                        chip.text = if (pct >= 0) "$step  ·  $pct %" else step
                    }
                }
                chip.visibility = View.GONE
                channelAdapter.notifyDataSetChanged()
                if (banner.visibility == View.VISIBLE) showBanner()
                if (force) toast("TV-Programm aktualisiert")
            } catch (e: Exception) {
                chip.visibility = View.GONE
                toast("TV-Programm nicht verfügbar: ${e.message}")
            }
        }
    }

    private var hidden: Set<String> = emptySet()
    /** Gruppenname in der Liste → Name der Favoritenliste */
    private var favGroups: Map<String, String> = emptyMap()
    private var visible: List<Channel> = emptyList()

    private fun buildGroups() {
        val pid = playlist?.id ?: return
        hidden = store.hiddenGroups(pid)
        visible = if (hidden.isEmpty()) Repo.channels else Repo.channels.filter { it.group !in hidden }
        favGroups = store.favLists().associate { "★ ${it.name}" to it.name }
        groupKeys = favGroups.keys.toList() + listOf(G_RECENT, G_ALL) + Repo.groups.filter { it !in hidden }
    }

    private fun channelsOf(g: String): List<Channel> {
        playlist?.id ?: return emptyList()
        return when (g) {
            in favGroups.keys -> {
                val keys = store.favLists().firstOrNull { it.name == favGroups[g] }?.keys ?: emptySet<String>()
                val byKey = Repo.channels.groupBy { favKey(it) }
                keys.mapNotNull { byKey[it]?.firstOrNull() }.sortedBy { it.number }
            }
            G_RECENT -> { val m = Repo.channels.associateBy { it.url }; store.recents(playlist!!.id).mapNotNull { m[it] } }
            G_ALL -> visible
            searchKey -> searchResults
            else -> Repo.byGroup[g] ?: emptyList()
        }
    }

    // ---------- Listen / Overlay ----------

    private val menuItems get() = listOf(
        "TV-Guide", "Multi-View", "Suche", "Favoritenlisten", "Gruppen / Länder", "Playlists", "Aktualisieren",
        "Stream-Format: " + if (store.streamFormat == "ts") "TS" else "HLS", "Beenden"
    )

    private fun initLists() {
        menuAdapter = TextAdapter(onClick = { onMenu(it) })
        menuList.layoutManager = LinearLayoutManager(this)
        menuList.adapter = menuAdapter

        groupAdapter = TextAdapter(
            onFocus = { pos -> groupKeys.getOrNull(pos)?.let { g -> ui.removeCallbacks(showGroupTask); pendingGroup = g; ui.postDelayed(showGroupTask, 120) } },
            onClick = { pos -> groupKeys.getOrNull(pos)?.let { showGroup(it) }; focusChannel(-1) }
        )
        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        groupList.itemAnimator = null

        channelAdapter = ChannelAdapter(
            onFocus = { showDetails(it) },
            onClick = { c ->
                if (c.url == current?.url && !isTouchDevice) hideOverlay() // zweites OK = Vollbild
                else {
                    playingGroup = viewingGroup
                    play(c, channelAdapter.items)
                    if (isTouchDevice) hideOverlay() else { showDetails(c); armOverlayTimer() }
                }
            },
            onLongClick = { c -> channelOptions(c) }
        )
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
    }

    /** OK lang drücken auf einem Sender – wie bei TiviMate ein Auswahlmenü */
    private fun channelOptions(c: Channel) {
        armOverlayTimer()
        val fav = store.isFavorite(c)
        AlertDialog.Builder(this)
            .setTitle("${c.number}  ${c.name}")
            .setItems(arrayOf(
                if (fav) "★ Favoritenlisten bearbeiten …" else "★ Zu Favoriten hinzufügen …",
                "In Multi-View öffnen",
                "Abspielen"
            )) { _, w ->
                when (w) {
                    0 -> chooseFavLists(c)
                    1 -> {
                        val tiles = MultiViewActivity.tiles
                        val srcs = MultiViewActivity.sources
                        if (c.url !in tiles) {
                            current?.url?.let { u -> if (u !in tiles) tiles.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { tiles[it] = u; srcs[it] = null } }
                            val free = tiles.indexOfFirst { it == null }
                            val i = if (free >= 0) free else 3
                            tiles[i] = c.url; srcs[i] = null
                        }
                        openMulti()
                    }
                    2 -> { playingGroup = viewingGroup; play(c, channelAdapter.items); hideOverlay() }
                }
            }.show()
    }

    // ---------- Favoritenlisten ----------

    /** Sender in Favoritenlisten aufnehmen/entfernen (Haken je Liste) */
    private fun chooseFavLists(c: Channel) {
        val lists = store.favLists()
        val k = favKey(c)
        val checked = BooleanArray(lists.size) { k in lists[it].keys }
        val dlg = AlertDialog.Builder(this)
            .setTitle("„${c.name}“ in Favoritenlisten")
            .setMultiChoiceItems(lists.map { "★ ${it.name}  (${it.keys.size})" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Übernehmen") { _, _ ->
                lists.forEachIndexed { i, l -> if (checked[i]) l.keys.add(k) else l.keys.remove(k) }
                store.saveFavLists(lists)
                val n = checked.count { it }
                toast(if (n == 0) "${c.name} aus allen Favoriten entfernt" else "★ ${c.name} in $n Liste${if (n == 1) "" else "n"}")
                afterFavChange(c)
            }
            .setNeutralButton("+ Neue Liste") { _, _ -> newFavList { name ->
                val l = store.favLists(); l.firstOrNull { it.name == name }?.keys?.add(k); store.saveFavLists(l)
                toast("★ ${c.name} in „$name“"); afterFavChange(c)
            } }
            .setNegativeButton("Abbrechen", null)
            .create()
        dlg.show()
    }

    private fun afterFavChange(c: Channel?) {
        channelAdapter.favorites = store.favorites()
        val oldView = viewingGroup
        buildGroups()
        if (viewingGroup !in groupKeys) viewingGroup = groupKeys.first()
        if (playingGroup !in groupKeys) playingGroup = G_ALL
        refreshGroupCounts()
        channelAdapter.items = channelsOf(viewingGroup)
        if (overlay.visibility == View.VISIBLE) {
            if (c != null && oldView == viewingGroup && channelAdapter.indexOf(c.url) >= 0) focusChannel(channelAdapter.indexOf(c.url))
            else focusGroup()
        }
    }

    private fun newFavList(then: (String) -> Unit) {
        val input = EditText(this).apply { hint = "Name, z. B. Sport, Kinder, Portugal"; isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle("Neue Favoritenliste")
            .setView(input)
            .setPositiveButton("Anlegen") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val l = store.favLists()
                if (l.any { it.name.equals(name, true) }) { toast("Liste „$name“ gibt es schon"); return@setPositiveButton }
                l.add(Store.FavList(name, mutableSetOf()))
                store.saveFavLists(l)
                then(name)
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun manageFavLists() {
        val lists = store.favLists()
        val labels = lists.map { "★ ${it.name}  (${it.keys.size} Sender)" } + "+ Neue Liste anlegen"
        AlertDialog.Builder(this)
            .setTitle("Favoritenlisten")
            .setItems(labels.toTypedArray()) { _, w ->
                if (w == lists.size) { newFavList { toast("Liste „$it“ angelegt"); afterFavChange(null) }; return@setItems }
                val fl = lists[w]
                AlertDialog.Builder(this)
                    .setTitle("★ ${fl.name}")
                    .setItems(arrayOf("Umbenennen", "Nach oben verschieben", "Leeren", "Löschen")) { _, a ->
                        val all = store.favLists()
                        val i = all.indexOfFirst { it.name == fl.name }
                        if (i < 0) return@setItems
                        when (a) {
                            0 -> {
                                val input = EditText(this).apply { setText(fl.name); isSingleLine = true }
                                AlertDialog.Builder(this).setTitle("Umbenennen").setView(input)
                                    .setPositiveButton("Speichern") { _, _ ->
                                        val n = input.text.toString().trim()
                                        if (n.isNotEmpty() && all.none { it.name.equals(n, true) && it !== all[i] }) {
                                            all[i] = Store.FavList(n, all[i].keys); store.saveFavLists(all); afterFavChange(null)
                                        }
                                    }.setNegativeButton("Abbrechen", null).show()
                            }
                            1 -> if (i > 0) { all.add(i - 1, all.removeAt(i)); store.saveFavLists(all); afterFavChange(null) }
                            2 -> { all[i].keys.clear(); store.saveFavLists(all); afterFavChange(null); toast("„${fl.name}“ geleert") }
                            3 -> {
                                if (all.size == 1) { toast("Die letzte Liste kann nicht gelöscht werden – du kannst sie leeren"); return@setItems }
                                AlertDialog.Builder(this).setTitle("Liste „${fl.name}“ löschen?")
                                    .setPositiveButton("Löschen") { _, _ -> all.removeAt(i); store.saveFavLists(all); afterFavChange(null); toast("„${fl.name}“ gelöscht") }
                                    .setNegativeButton("Abbrechen", null).show()
                            }
                        }
                    }.show()
            }.show()
    }

    // ---------- Gruppen verwalten ----------

    private fun manageGroups() {
        val pid = playlist?.id ?: return
        if (Repo.groups.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Gruppen verwalten")
            .setItems(arrayOf("Nach Ländern auswählen", "Gruppen einzeln ein-/ausblenden", "Alle Gruppen einblenden")) { _, w ->
                when (w) {
                    0 -> chooseCountries(pid)
                    1 -> chooseGroups(pid)
                    2 -> { store.setHiddenGroups(pid, emptySet()); applyGroupChange("Alle Gruppen eingeblendet") }
                }
            }.show()
    }

    /** Mehrfachauswahl – Knöpfe stehen OBEN, damit man nicht durch die ganze Liste scrollen muss */
    private fun multiChoice(title: String, labels: Array<String>, checked: BooleanArray, onApply: () -> Unit) {
        val d = resources.displayMetrics.density
        val head = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (18 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        head.addView(TextView(this).apply { text = title; textSize = 20f; setTextColor(android.graphics.Color.WHITE) })
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, (12 * d).toInt(), 0, 0) }
        fun btn(t: String) = android.widget.Button(this).apply {
            text = t; isAllCaps = false; textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.button_bg)
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (10 * d).toInt() }
        }
        val bApply = btn("✓ Übernehmen"); val bAll = btn("Alle an"); val bNone = btn("Alle aus"); val bCancel = btn("Abbrechen")
        listOf(bApply, bAll, bNone, bCancel).forEach { row.addView(it) }
        head.addView(row)
        val dlg = AlertDialog.Builder(this)
            .setCustomTitle(head)
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .create()
        fun setAll(on: Boolean) {
            for (i in checked.indices) { checked[i] = on; dlg.listView.setItemChecked(i, on) }
            toast(if (on) "Alle ausgewählt" else "Alle abgewählt")
        }
        bAll.setOnClickListener { setAll(true) }
        bNone.setOnClickListener { setAll(false) }
        bCancel.setOnClickListener { dlg.dismiss() }
        bApply.setOnClickListener { dlg.dismiss(); onApply() }
        dlg.setOnShowListener { bApply.requestFocus() }
        dlg.show()
    }

    private fun chooseCountries(pid: String) {
        val byCountry = LinkedHashMap<String, MutableList<String>>()
        Repo.groups.forEach { g -> byCountry.getOrPut(Countries.code(g)) { mutableListOf() }.add(g) }
        val codes = byCountry.keys.sortedWith(compareBy({ it == "?" }, { -(byCountry[it]?.size ?: 0) }))
        val hiddenNow = store.hiddenGroups(pid)
        val checked = BooleanArray(codes.size) { i -> byCountry[codes[i]]!!.any { it !in hiddenNow } }
        val labels = codes.map { c ->
            val n = byCountry[c]!!.sumOf { g -> Repo.byGroup[g]?.size ?: 0 }
            "${Countries.label(c)}  ·  ${byCountry[c]!!.size} Gruppen, $n Sender"
        }.toTypedArray()
        multiChoice("Welche Länder anzeigen?", labels, checked) {
            val hide = HashSet<String>()
            codes.forEachIndexed { i, c -> if (!checked[i]) hide.addAll(byCountry[c]!!) }
            if (hide.size == Repo.groups.size) { toast("Mindestens ein Land muss sichtbar bleiben"); return@multiChoice }
            store.setHiddenGroups(pid, hide)
            applyGroupChange("${codes.indices.count { checked[it] }} Länder ausgewählt")
        }
    }

    private fun chooseGroups(pid: String) {
        val groups = Repo.groups
        val hiddenNow = store.hiddenGroups(pid)
        val checked = BooleanArray(groups.size) { groups[it] !in hiddenNow }
        val labels = groups.map { "$it  (${Repo.byGroup[it]?.size ?: 0})" }.toTypedArray()
        multiChoice("Sichtbare Gruppen (Haken = anzeigen)", labels, checked) {
            val hide = groups.filterIndexed { i, _ -> !checked[i] }.toSet()
            if (hide.size == groups.size) { toast("Mindestens eine Gruppe muss sichtbar bleiben"); return@multiChoice }
            store.setHiddenGroups(pid, hide)
            applyGroupChange("${groups.size - hide.size} von ${groups.size} Gruppen sichtbar")
        }
    }

    private fun applyGroupChange(msg: String) {
        buildGroups()
        if (playingGroup !in groupKeys) playingGroup = G_ALL
        viewingGroup = playingGroup
        zapList = channelsOf(playingGroup)
        refreshGroupCounts()
        channelAdapter.items = channelsOf(viewingGroup)
        toast(msg)
        if (overlay.visibility == View.VISIBLE) { menuPane.visibility = View.GONE; focusGroup() }
    }

    private var pendingGroup: String? = null
    private val showGroupTask = Runnable { pendingGroup?.let { showGroup(it) } }

    private fun showGroup(g: String) {
        if (g == viewingGroup && channelAdapter.items.isNotEmpty()) return
        viewingGroup = g
        channelAdapter.items = channelsOf(g)
        groupAdapter.activePos = groupKeys.indexOf(g)
        channelList.scrollToPosition(0)
    }

    private fun refreshGroupCounts() {
        groupAdapter.items = groupKeys.map { g -> TextItem(g, channelsOf(g).size.toString()) }
        groupAdapter.activePos = groupKeys.indexOf(viewingGroup)
    }

    private fun showOverlay(focus: String = "channels") {
        if (Repo.channels.isEmpty()) { showMenu(); overlay.visibility = View.VISIBLE; return }
        hideBanner()
        val pid = playlist?.id ?: return
        channelAdapter.favorites = store.favorites()
        viewingGroup = playingGroup.takeIf { it in groupKeys } ?: G_ALL
        refreshGroupCounts()
        channelAdapter.items = channelsOf(viewingGroup)
        channelAdapter.playingUrl = current?.url
        overlay.visibility = View.VISIBLE
        updateClock()
        if (isTouchDevice) {
            findViewById<View>(R.id.detailPane).visibility = View.GONE
            channelList.layoutParams = (channelList.layoutParams as android.widget.LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
            menuAdapter.items = menuItems.map { TextItem(it) }
            menuPane.visibility = View.VISIBLE
            current?.let { showDetails(it) }
            armOverlayTimer()
            return
        }
        when (focus) {
            "menu" -> showMenu()
            "groups" -> { menuPane.visibility = View.GONE; focusGroup() }
            else -> { menuPane.visibility = View.GONE; focusChannel(channelAdapter.indexOf(current?.url)) }
        }
        current?.let { showDetails(it) }
        previewOn()
        armOverlayTimer()
    }

    /** Video als kleines Vorschaubild rechts oben in der Kanalliste */
    private fun previewOn() {
        if (isTouchDevice) return
        val slot = findViewById<View>(R.id.previewSlot)
        val pane = findViewById<View>(R.id.detailPane)
        pane.post {
            val w = pane.width - pane.paddingLeft - pane.paddingRight
            if (w <= 0) return@post
            val h = w * 9 / 16
            if (slot.layoutParams.height != h) { slot.layoutParams = slot.layoutParams.apply { height = h }; slot.requestLayout() }
            slot.post {
                val root = IntArray(2); val pos = IntArray(2)
                (playerView.parent as View).getLocationInWindow(root)
                slot.getLocationInWindow(pos)
                playerView.layoutParams = (playerView.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                    width = slot.width; height = slot.height
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    leftMargin = pos[0] - root[0]; topMargin = pos[1] - root[1]
                }
                playerView.elevation = 20f
                playerView.requestLayout()
            }
        }
    }

    private fun previewOff() {
        playerView.layoutParams = (playerView.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            width = -1; height = -1; leftMargin = 0; topMargin = 0
        }
        playerView.elevation = 0f
        playerView.requestLayout()
    }

    private fun hideOverlay() {
        previewOff()
        overlay.visibility = View.GONE
        menuPane.visibility = View.GONE
        ui.removeCallbacks(overlayTimeout)
        playerView.requestFocus()
    }

    private val overlayTimeout = Runnable { hideOverlay() }
    private fun armOverlayTimer() { ui.removeCallbacks(overlayTimeout); ui.postDelayed(overlayTimeout, 25000) }

    private fun showMenu() {
        menuAdapter.items = menuItems.map { TextItem(it) }
        menuPane.visibility = View.VISIBLE
        focusAt(menuList, 0)
    }

    private fun hideMenu() { menuPane.visibility = View.GONE }

    private fun focusGroup() = focusAt(groupList, groupKeys.indexOf(viewingGroup).coerceAtLeast(0))

    private fun focusChannel(pos: Int) {
        val p = if (pos >= 0) pos else channelAdapter.indexOf(current?.url).coerceAtLeast(0)
        if (channelAdapter.itemCount == 0) return
        focusAt(channelList, p)
    }

    private fun focusAt(rv: RecyclerView, pos: Int) {
        val n = rv.adapter?.itemCount ?: 0
        if (n == 0) return
        val p = pos.coerceIn(0, n - 1)
        val lm = rv.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(p, rv.height / 3)
        rv.post {
            val vh = rv.findViewHolderForAdapterPosition(p)
            if (vh != null) vh.itemView.requestFocus()
            else rv.postDelayed({ rv.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }, 50)
        }
    }

    private fun showDetails(c: Channel) {
        val (cur, next) = Repo.nowNext(c)
        findViewById<TextView>(R.id.detailChannel).text = "${c.number}  ${c.name}"
        findViewById<TextView>(R.id.detailTitle).text = cur?.title ?: "Keine Programminformationen"
        findViewById<TextView>(R.id.detailTime).text = cur?.let { Fmt.range(it) } ?: c.group
        findViewById<TextView>(R.id.detailDesc).text = cur?.desc ?: ""
        findViewById<TextView>(R.id.detailNext).text = next?.let { "Danach: ${Fmt.time(it.start)}  ${it.title}" } ?: ""
    }

    private fun updateClock() {
        findViewById<TextView>(R.id.clock).text = Fmt.time(System.currentTimeMillis())
    }

    private fun onMenu(i: Int) {
        val item = menuItems.getOrNull(i) ?: return
        when {
            item == "TV-Guide" -> openGuide()
            item == "Multi-View" -> openMulti()
            item == "Suche" -> search()
            item == "Favoritenlisten" -> manageFavLists()
            item == "Gruppen / Länder" -> manageGroups()
            item == "Playlists" -> { hideOverlay(); startActivityForResult(Intent(this, PlaylistActivity::class.java), REQ_PLAYLIST) }
            item == "Aktualisieren" -> { hideOverlay(); load(true) }
            item.startsWith("Stream-Format") -> {
                store.streamFormat = if (store.streamFormat == "ts") "m3u8" else "ts"
                menuAdapter.items = menuItems.map { TextItem(it) }
                focusAt(menuList, i)
                if (playlist?.type == "xtream") { hideOverlay(); load(true) }
                else toast("Gilt nur für Xtream-Codes-Playlists")
            }
            item == "Beenden" -> finish()
        }
    }

    private fun openMulti() {
        if (Repo.channels.isEmpty()) return
        hideOverlay()
        startActivityForResult(Intent(this, MultiViewActivity::class.java).putExtra("url", current?.url), REQ_MULTI)
    }

    private fun openGuide() {
        if (Repo.channels.isEmpty()) return
        val list = channelAdapter.items.ifEmpty { Repo.channels }
        GuideActivity.channels = list
        GuideActivity.startUrl = current?.url
        hideOverlay()
        startActivityForResult(Intent(this, GuideActivity::class.java), REQ_GUIDE)
    }

    private fun search() {
        val input = EditText(this).apply {
            hint = "Sendername"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Kanal suchen")
            .setView(input)
            .setPositiveButton("Suchen") { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton("Abbrechen", null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> dlg.dismiss(); runSearch(input.text.toString()); true }
        dlg.show()
    }

    private fun runSearch(q: String) {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return
        searchResults = visible.filter { it.name.lowercase().contains(query) }
        searchKey?.let { old -> groupKeys = groupKeys - old }
        val key = "Suche: $q"
        searchKey = key
        groupKeys = listOf(key) + groupKeys
        viewingGroup = key
        refreshGroupCounts()
        channelAdapter.items = searchResults
        menuPane.visibility = View.GONE
        if (searchResults.isEmpty()) { toast("Nichts gefunden für „$q“"); focusGroup() } else focusChannel(0)
    }

    // ---------- Info-Leiste ----------

    private val hideBannerTask = Runnable { hideBanner() }

    private fun showBanner() {
        val c = current ?: return
        if (overlay.visibility == View.VISIBLE) return
        val now = System.currentTimeMillis()
        val (cur, next) = Repo.nowNext(c, now)
        findViewById<TextView>(R.id.bannerNumber).text = c.number.toString()
        findViewById<ImageView>(R.id.bannerLogo).load(c.logo)
        findViewById<TextView>(R.id.bannerName).text = c.name
        findViewById<TextView>(R.id.bannerNow).text =
            cur?.let { "${Fmt.time(it.start)} – ${Fmt.time(it.stop)}   ${it.title}" } ?: "Keine Programminformationen"
        val pb = findViewById<ProgressBar>(R.id.bannerProgress)
        pb.visibility = if (cur != null) View.VISIBLE else View.INVISIBLE
        cur?.let { pb.progress = Fmt.progress(it, now) }
        findViewById<TextView>(R.id.bannerNext).text = next?.let { "Danach  ${Fmt.time(it.start)}   ${it.title}" } ?: ""
        findViewById<TextView>(R.id.bannerClock).text = Fmt.time(now)
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBannerTask)
        ui.postDelayed(hideBannerTask, 5000)
    }

    private fun hideBanner() { banner.visibility = View.GONE }

    // ---------- Status ----------

    private fun showStatus(t: String) { status.text = t; status.visibility = View.VISIBLE }
    private fun hideStatus() { status.visibility = View.GONE }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- Zifferneingabe ----------

    private val digitTask = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        numberEntry.visibility = View.GONE
        val ch = Repo.channels.firstOrNull { it.number == n }
        if (ch != null) { playingGroup = G_ALL; play(ch, Repo.channels) } else if (n != null) toast("Kanal $n nicht gefunden")
    }

    private fun digit(d: Int) {
        if (digits.length >= 5) return
        digits += d
        numberEntry.text = digits
        numberEntry.visibility = View.VISIBLE
        ui.removeCallbacks(digitTask)
        ui.postDelayed(digitTask, 1600)
    }

    // ---------- Fernbedienung ----------

    private fun listContaining(v: View?): RecyclerView? {
        if (v == null) return null
        return arrayOf(menuList, groupList, channelList).firstOrNull { it.findContainingItemView(v) != null }
    }

    private var swallowCenter = false
    private val centerKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        // der OK-Druck, der die Liste öffnet, darf dort nichts auslösen (kein Favorit durch Halten)
        if (swallowCenter && code in centerKeys) {
            if (e.action == KeyEvent.ACTION_UP) swallowCenter = false
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (overlay.visibility == View.VISIBLE) {
            armOverlayTimer()
            val rv = listContaining(currentFocus)
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (menuPane.visibility == View.VISIBLE && Repo.channels.isNotEmpty()) { hideMenu(); focusGroup() } else hideOverlay()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (menuPane.visibility == View.VISIBLE) { hideMenu(); focusGroup() } else showMenu()
                    return true
                }
                KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> when (rv) {
                    channelList -> { focusGroup(); return true }
                    groupList -> { showMenu(); return true }
                    menuList -> return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (rv) {
                    menuList -> { if (Repo.channels.isNotEmpty()) { hideMenu(); focusGroup() }; return true }
                    groupList -> { focusChannel(channelAdapter.indexOf(current?.url).let { if (it < 0) 0 else it }); return true }
                    channelList -> return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (rv != null) {
                    val item = rv.findContainingItemView(currentFocus!!)!!
                    val pos = rv.getChildAdapterPosition(item)
                    val n = rv.adapter?.itemCount ?: 0
                    if (code == KeyEvent.KEYCODE_DPAD_UP && pos <= 0) return true
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN && pos >= n - 1) return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (rv != null) { pageList(rv, -8); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (rv != null) { pageList(rv, 8); return true }
            }
            if (rv == null && code in arrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // Fokus verloren -> zurück in die Kanalliste
                focusChannel(-1); return true
            }
            return super.dispatchKeyEvent(e)
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { swallowCenter = true; showOverlay(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { showOverlay("groups"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showBanner(); return true }
            KeyEvent.KEYCODE_MENU -> { showOverlay("menu"); return true }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> { openGuide(); return true }
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { openMulti(); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                previous?.let { playingGroup = G_ALL; play(it, zapList) }; return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player.playWhenReady = !player.playWhenReady; return true }
            KeyEvent.KEYCODE_BACK -> {
                if (banner.visibility == View.VISIBLE) { hideBanner(); return true }
                val now = System.currentTimeMillis()
                if (now - lastBack < 2500) finish() else { lastBack = now; toast("Nochmal Zurück drücken zum Beenden") }
                return true
            }
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digit(code - KeyEvent.KEYCODE_0); return true }
        if (code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) { digit(code - KeyEvent.KEYCODE_NUMPAD_0); return true }
        return super.dispatchKeyEvent(e)
    }

    private fun pageList(rv: RecyclerView, step: Int) {
        val item = currentFocus?.let { rv.findContainingItemView(it) } ?: return
        focusAt(rv, rv.getChildAdapterPosition(item) + step)
    }
}
