package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
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

        if (store.playlists().isEmpty()) {
            showStatus("Willkommen bei PintoTV!\nBitte zuerst eine Playlist hinzufügen.")
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
                if (p == null) showStatus("Keine Playlist vorhanden.\nMenü-Taste drücken → Playlists")
                else if (p.id != playlist?.id || resultCode == RESULT_OK) load(resultCode == RESULT_OK)
            }
            REQ_GUIDE, REQ_MULTI -> {
                val url = data?.getStringExtra("url") ?: return
                val ch = Repo.channels.firstOrNull { it.url == url } ?: return
                playingGroup = viewingGroup
                play(ch, channelsOf(viewingGroup))
            }
        }
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
        val list = zapList.ifEmpty { Repo.channels }
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
        showStatus(if (force) "Aktualisiere „${p.name}“ …" else "Lade „${p.name}“ …")
        scope.launch {
            try {
                Repo.loadChannels(this@PlayerActivity, p, store.streamFormat, force)
            } catch (e: Exception) {
                loading = false
                showStatus("Playlist konnte nicht geladen werden:\n${e.message}\n\nMenü-Taste → Playlists")
                return@launch
            }
            loading = false
            hideStatus()
            buildGroups()
            val last = store.lastUrl(p.id)
            playingGroup = store.lastGroup?.takeIf { it in groupKeys } ?: G_ALL
            viewingGroup = playingGroup
            val list = channelsOf(playingGroup)
            val ch = Repo.channels.firstOrNull { it.url == last } ?: list.firstOrNull() ?: Repo.channels.firstOrNull()
            if (ch != null) play(ch, if (ch in list) list else Repo.channels)
            loadEpg(force)
        }
    }

    private fun loadEpg(force: Boolean) {
        scope.launch {
            try {
                if (force || Repo.epg.isEmpty()) toast("EPG wird geladen …")
                Repo.loadEpg(this@PlayerActivity, force)
                channelAdapter.notifyDataSetChanged()
                if (banner.visibility == View.VISIBLE) showBanner()
                if (force) toast("EPG aktualisiert")
            } catch (e: Exception) {
                toast("EPG nicht verfügbar: ${e.message}")
            }
        }
    }

    private fun buildGroups() {
        groupKeys = listOf(G_FAV, G_RECENT, G_ALL) + Repo.groups
    }

    private fun channelsOf(g: String): List<Channel> {
        val pid = playlist?.id ?: return emptyList()
        return when (g) {
            G_FAV -> store.favorites(pid).let { f -> Repo.channels.filter { it.url in f } }
            G_RECENT -> { val m = Repo.channels.associateBy { it.url }; store.recents(pid).mapNotNull { m[it] } }
            G_ALL -> Repo.channels
            searchKey -> searchResults
            else -> Repo.byGroup[g] ?: emptyList()
        }
    }

    // ---------- Listen / Overlay ----------

    private val menuItems get() = listOf(
        "TV-Guide", "Multi-View", "Suche", "Playlists", "Aktualisieren",
        "Stream-Format: " + if (store.streamFormat == "ts") "TS" else "HLS", "Beenden"
    )

    private fun initLists() {
        menuAdapter = TextAdapter(onClick = { onMenu(it) })
        menuList.layoutManager = LinearLayoutManager(this)
        menuList.adapter = menuAdapter

        groupAdapter = TextAdapter(
            onFocus = { pos -> groupKeys.getOrNull(pos)?.let { g -> ui.removeCallbacks(showGroupTask); pendingGroup = g; ui.postDelayed(showGroupTask, 120) } },
            onClick = { focusChannel(-1) }
        )
        groupList.layoutManager = LinearLayoutManager(this)
        groupList.adapter = groupAdapter
        groupList.itemAnimator = null

        channelAdapter = ChannelAdapter(
            onFocus = { showDetails(it) },
            onClick = { c -> playingGroup = viewingGroup; play(c, channelAdapter.items); hideOverlay() },
            onLongClick = { c ->
                val pid = playlist?.id ?: return@ChannelAdapter
                val added = store.toggleFavorite(pid, c.url)
                toast(if (added) "★ ${c.name} zu Favoriten hinzugefügt" else "${c.name} aus Favoriten entfernt")
                channelAdapter.favorites = store.favorites(pid)
                val i = channelAdapter.indexOf(c.url)
                if (i >= 0) channelAdapter.notifyItemChanged(i)
                refreshGroupCounts()
            }
        )
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = channelAdapter
        channelList.itemAnimator = null
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
        channelAdapter.favorites = store.favorites(pid)
        viewingGroup = playingGroup.takeIf { it in groupKeys } ?: G_ALL
        refreshGroupCounts()
        channelAdapter.items = channelsOf(viewingGroup)
        channelAdapter.playingUrl = current?.url
        overlay.visibility = View.VISIBLE
        updateClock()
        when (focus) {
            "menu" -> showMenu()
            "groups" -> { menuPane.visibility = View.GONE; focusGroup() }
            else -> { menuPane.visibility = View.GONE; focusChannel(channelAdapter.indexOf(current?.url)) }
        }
        current?.let { showDetails(it) }
        armOverlayTimer()
    }

    private fun hideOverlay() {
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
        when (i) {
            0 -> openGuide()
            1 -> openMulti()
            2 -> search()
            3 -> { hideOverlay(); startActivityForResult(Intent(this, PlaylistActivity::class.java), REQ_PLAYLIST) }
            4 -> { hideOverlay(); load(true) }
            5 -> {
                store.streamFormat = if (store.streamFormat == "ts") "m3u8" else "ts"
                menuAdapter.items = menuItems.map { TextItem(it) }
                focusAt(menuList, 5)
                if (playlist?.type == "xtream") { hideOverlay(); load(true) }
                else toast("Gilt nur für Xtream-Codes-Playlists")
            }
            6 -> finish()
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
        searchResults = Repo.channels.filter { it.name.lowercase().contains(query) }
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
