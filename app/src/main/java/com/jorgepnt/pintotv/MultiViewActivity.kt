package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Ton-Verschiebung für den Hauptplayer (Mikrosekunden, + = Ton später) */
object AudioSync {
    @Volatile var offsetUs: Long = 0
}

/** Audio-Ausgabe mit verschiebbarer Uhr: Bild richtet sich nach dem Ton, also verschiebt das Ton gegenüber Bild */
class SyncRenderersFactory(ctx: Context, private val forcePcm: Boolean, private val sync: Boolean) :
    androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
    init { setEnableDecoderFallback(true) }
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
        val b = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        // Stereo erzwingen: kein Durchreichen von Dolby/AC3 an Soundbar/AV-Receiver (häufige Ursache für Versatz)
        if (forcePcm) b.setAudioCapabilities(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        val sink = b.build()
        if (!sync) return sink
        return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val p = super.getCurrentPositionUs(sourceEnded)
                if (p == androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET) return p
                return (p + AudioSync.offsetUs).coerceAtLeast(0)
            }
        }
    }
}

object Players {
    fun create(ctx: Context, recordable: Boolean = false, ua: () -> String = { Http.UA }): ExoPlayer {
        // Kennung wird bei jedem Stream neu gelesen (passend zur Playlist)
        val http = androidx.media3.datasource.DataSource.Factory {
            val src = DefaultHttpDataSource.Factory()
                .setUserAgent(ua())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000)
                .createDataSource()
            // bei laufender Aufnahme die empfangenen Daten zusätzlich in die Datei schreiben
            // immer eingehängt: schreibt nur, solange eine Aufnahme läuft (prüft bei jedem neuen Stück)
            if (recordable) androidx.media3.datasource.TeeDataSource(src, Recorder.sink()) else src
        }
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val pcm = Store(ctx).getString("forcePcm", "0") == "1"
        return ExoPlayer.Builder(ctx, SyncRenderersFactory(ctx, pcm, sync = recordable))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors))
            .build()
    }

    fun item(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }
}

/** Mehrere Sender gleichzeitig: 2 nebeneinander oder 4 im Raster */
class MultiViewActivity : Activity() {

    companion object {
        /** bleibt erhalten, solange die App läuft */
        val tiles = arrayOfNulls<String>(4)
        /** Quelle je Fenster (null = aktive Playlist) */
        val sources = arrayOfNulls<Repo.Loaded>(4)
        var four = true
    }

    private inner class Tile(val index: Int) {
        val frame = FrameLayout(this@MultiViewActivity)
        val view = PlayerView(this@MultiViewActivity).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            isFocusable = false
        }
        val label = TextView(this@MultiViewActivity).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundResource(R.drawable.label_bg)
            setPadding(16, 6, 16, 6)
        }
        val hint = TextView(this@MultiViewActivity).apply {
            setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 18f
            gravity = Gravity.CENTER
            text = "＋\nOK drücken, um einen Sender zu wählen"
        }
        var player: ExoPlayer? = null
        var channel: Channel? = null
        var errors = 0
        var src: Repo.Loaded? = null
        private val isOther get() = src != null && src?.playlist?.id != Repo.playlistId
        private val hideLabel = Runnable { label.visibility = View.GONE }

        /** Sendername kurz einblenden, dann verschwindet er, damit das Bild frei ist */
        fun flashLabel() {
            if (channel == null) return
            label.removeCallbacks(hideLabel)
            label.visibility = View.VISIBLE
            label.postDelayed(hideLabel, 3000)
        }

        /** Fehlertext bleibt stehen */
        fun showLabel(t: String) {
            label.removeCallbacks(hideLabel)
            label.text = t
            label.visibility = View.VISIBLE
        }

        init {
            frame.setBackgroundColor(Color.BLACK)
            frame.addView(view, FrameLayout.LayoutParams(-1, -1))
            frame.addView(hint, FrameLayout.LayoutParams(-1, -1))
            frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(16, 16, 16, 16) })
            frame.foreground = getDrawable(R.drawable.tile_normal)
            // Touch: Fenster antippen = auswählen (Ton), nochmal antippen = Sender wählen, lang = Optionen
            frame.setOnClickListener {
                if (sel != index) { sel = index; highlight() }
                else if (channel == null) pickGroup() else tileMenu()
            }
            frame.setOnLongClickListener { sel = index; highlight(); tileMenu(); true }
            // nur für Touch – auf der Fernbedienung steuert die Activity die Auswahl
            frame.isFocusable = false
            frame.isFocusableInTouchMode = false
        }

        fun set(c: Channel?, source: Repo.Loaded? = src) {
            channel = c
            src = if (c == null) null else source
            tiles[index] = c?.url
            sources[index] = src
            if (c == null) {
                player?.release(); player = null; view.player = null
                label.visibility = View.GONE; hint.visibility = View.VISIBLE
                return
            }
            hint.visibility = View.GONE
            hint.text = "＋\nOK drücken, um einen Sender zu wählen"
            errors = 0
            val (cur, _) = Repo.nowNext(c)
            label.text = (if (isOther) "[${src?.playlist?.name}]  " else "") + "${c.number}  ${c.name}" + (cur?.let { "  ·  ${it.title}" } ?: "")
            flashLabel()
            val p = player ?: Players.create(this@MultiViewActivity) { src?.ua ?: Http.UA }.also { np ->
                player = np
                view.player = np
                np.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            if (errors > 0) { channel?.let { ch -> label.text = "${ch.number}  ${ch.name}" }; flashLabel() }
                            errors = 0
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            np.seekToDefaultPosition(); np.prepare()
                        } else if (++errors <= 2) {
                            showLabel("${channel?.name ?: ""}  ·  neuer Versuch …")
                            view.postDelayed({ if (player === np && channel != null) { np.prepare(); np.play() } }, 3000)
                        } else {
                            showLabel("${channel?.name ?: ""}  ·  kein Bild")
                            val m = src?.maxConnections ?: Xtream.maxConnections
                            hint.text = "Sender lädt nicht.\nOft erlaubt der Anbieter nur ${if (m > 0) "$m Verbindung(en)" else "wenige Verbindungen"} gleichzeitig.\nTipp: Sender aus einer anderen Playlist wählen.\nOK: anderen Sender wählen"
                            hint.visibility = View.VISIBLE
                        }
                    }
                })
            }
            p.setMediaItem(Players.item(c.url))
            p.prepare()
            p.playWhenReady = true
            applyAudio()
        }

        fun start() { channel?.let { set(it) } }
        fun stop() { player?.release(); player = null; view.player = null }
    }

    private val list = ArrayList<Tile>()
    private var sel = 0
    private lateinit var grid: LinearLayout
    private lateinit var row2: LinearLayout
    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this)
        row2 = LinearLayout(this)
        repeat(4) { list.add(Tile(it)) }
        val lp = { LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(3, 3, 3, 3) } }
        row1.addView(list[0].frame, lp()); row1.addView(list[1].frame, lp())
        row2.addView(list[2].frame, lp()); row2.addView(list[3].frame, lp())
        grid.addView(row1, LinearLayout.LayoutParams(-1, 0, 1f))
        grid.addView(row2, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))

        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundResource(R.drawable.label_bg)
            setPadding(24, 10, 24, 10)
            text = (if (Xtream.maxConnections > 0) "Dein Anbieter erlaubt ${Xtream.maxConnections} Verbindung(en)  ·  " else "") + "Multi-View  ·  Fenster wählen = Ton  ·  OK/Antippen: Sender  ·  Menü/lang drücken: 2 oder 4 Fenster  ·  Zurück: Ende"
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 20 })
        info.postDelayed({ info.visibility = View.GONE }, 6000)
        setContentView(root)

        // aktueller Sender aus dem Hauptbild ins erste freie Fenster
        intent.getStringExtra("url")?.let { u -> if (u !in tiles) { val i = tiles.indexOfFirst { it == null }; if (i >= 0) { tiles[i] = u; sources[i] = null } } }
        applyLayout()
        val byUrl = Repo.channels.associateBy { it.url }
        for (i in 0 until 4) {
            val other = sources[i]?.takeIf { it.playlist.id != Repo.playlistId }
            list[i].src = other ?: Repo.current()
            list[i].channel = tiles[i]?.let { u -> other?.channels?.firstOrNull { it.url == u } ?: byUrl[u] }
        }
        sel = tiles.indexOfFirst { it == intent.getStringExtra("url") }.coerceAtLeast(0)
        highlight()
    }

    override fun onStart() {
        super.onStart()
        list.take(count()).forEach { if (it.channel != null) it.start() else it.set(null) }
        applyAudio()
    }

    override fun onStop() {
        super.onStop()
        list.forEach { it.stop() }
    }

    private fun count() = if (four) 4 else 2

    private fun applyLayout() {
        row2.visibility = if (four) View.VISIBLE else View.GONE
        if (!four) {
            list[2].stop(); list[3].stop()
            if (sel > 1) sel = 0
        }
        // im 2er-Modus Fenster in 16:9 mittig
        grid.layoutParams = (grid.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(-1, -1)).apply {
            height = if (four) -1 else (resources.displayMetrics.widthPixels / 2 * 9 / 16 + 12)
            gravity = Gravity.CENTER
        }
        grid.requestLayout()
    }

    private fun highlight() {
        list.forEachIndexed { i, t -> t.frame.foreground = getDrawable(if (i == sel) R.drawable.tile_selected else R.drawable.tile_normal) }
        list.getOrNull(sel)?.flashLabel() // beim Wechsel kurz zeigen, welcher Sender das ist
        applyAudio()
    }

    private fun applyAudio() {
        list.forEachIndexed { i, t -> t.player?.volume = if (i == sel) 1f else 0f }
    }

    private fun move(dx: Int, dy: Int) {
        val c = count()
        var col = sel % 2 + dx
        var row = sel / 2 + dy
        if (col !in 0..1) return
        if (row !in 0 until c / 2) return
        sel = row * 2 + col
        highlight()
    }

    /** Tasten immer zuerst hier auswerten, egal welches Element den Fokus hat */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        val mine = k in arrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)
        if (mine) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onKeyDown(k, event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { move(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (list[sel].channel == null) pickGroup() else tileMenu(); return true
            }
            KeyEvent.KEYCODE_MENU -> { toggleLayout(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zapTile(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zapTile(-1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleLayout() {
        four = !four
        applyLayout()
        if (four) list.drop(2).forEach { if (it.channel != null) it.start() else it.set(null) }
        highlight()
        toast(if (four) "4 Fenster" else "2 Fenster")
    }

    private fun zapTile(step: Int) {
        val c = list[sel].channel ?: return
        val src = list[sel].src ?: Repo.current() ?: return
        val l = src.byGroup[c.group] ?: src.channels
        val i = l.indexOf(c)
        list[sel].set(l[((i + step) % l.size + l.size) % l.size])
    }

    private fun tileMenu() {
        val c = list[sel].channel ?: return
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(arrayOf("Sender wechseln", "Im Vollbild ansehen", "Fenster leeren", if (four) "Auf 2 Fenster umschalten" else "Auf 4 Fenster umschalten")) { _, w ->
                when (w) {
                    0 -> pickGroup()
                    1 -> {
                        setResult(RESULT_OK, Intent().putExtra("url", c.url).putExtra("pid", list[sel].src?.playlist?.id ?: Repo.playlistId))
                        finish()
                    }
                    2 -> list[sel].set(null)
                    3 -> toggleLayout()
                }
            }.show()
    }

    private val scope = kotlinx.coroutines.MainScope()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** Schritt 1: Playlist wählen (nur wenn mehrere vorhanden) */
    private fun pickGroup() {
        val store = Store(this)
        val pls = store.playlists()
        if (pls.size <= 1) { Repo.current()?.let { checkLimit(it) } ?: toast("Keine Kanäle geladen"); return }
        val labels = pls.map { p ->
            val used = list.take(count()).count { t -> t.channel != null && (t.src?.playlist?.id ?: Repo.playlistId) == p.id }
            (if (p.id == Repo.playlistId) "● " else "") + p.name + (if (used > 0) "   ($used im Bild)" else "")
        }
        AlertDialog.Builder(this)
            .setTitle("Aus welcher Playlist?")
            .setItems(labels.toTypedArray()) { _, w -> loadPlaylist(pls[w]) }
            .show()
    }

    private fun loadPlaylist(p: Playlist) {
        if (p.id == Repo.playlistId) { Repo.current()?.let { checkLimit(it) }; return }
        val dlg = AlertDialog.Builder(this).setTitle("„${p.name}“ wird geladen").setMessage("Starte …").setCancelable(true).create()
        dlg.show()
        val job = scope.launch {
            try {
                val l = Repo.forMultiView(this@MultiViewActivity, p, Store(this@MultiViewActivity).streamFormat) { step, pct ->
                    runOnUiThread { dlg.setMessage(if (pct >= 0) "$step\n$pct %" else step) }
                }
                dlg.dismiss()
                checkLimit(l)
            } catch (e: Exception) {
                dlg.dismiss()
                if (e !is kotlinx.coroutines.CancellationException)
                    AlertDialog.Builder(this@MultiViewActivity).setTitle("Laden fehlgeschlagen").setMessage(e.message).setPositiveButton("OK", null).show()
            }
        }
        dlg.setOnCancelListener { job.cancel() }
    }

    /** Schritt 2: Verbindungs-Limit dieser Playlist prüfen */
    private fun checkLimit(src: Repo.Loaded) {
        val max = src.maxConnections
        val active = list.take(count()).count { it.channel != null && it.index != sel && (it.src?.playlist?.id ?: Repo.playlistId) == src.playlist.id }
        if (max in 1..active) {
            AlertDialog.Builder(this)
                .setTitle("Verbindungs-Limit")
                .setMessage("Diese Playlist erlaubt nur $max gleichzeitige Verbindung${if (max == 1) "" else "en"}, " +
                    "und davon laufen schon $active. Ein weiterer Sender wird wahrscheinlich nicht laden.\n\n" +
                    "Tipp: Wähle einen Sender aus einer anderen Playlist.")
                .setPositiveButton("Trotzdem versuchen") { _, _ -> pickGroupOf(src) }
                .setNegativeButton("Andere Playlist") { _, _ -> pickGroup() }
                .show()
            return
        }
        pickGroupOf(src)
    }

    /** Schritt 3: Gruppe wählen */
    private fun pickGroupOf(src: Repo.Loaded) {
        if (src.channels.isEmpty()) { toast("Keine Kanäle"); return }
        val store = Store(this)
        val byKey = src.channels.groupBy { favKey(it) }
        val hidden = store.hiddenGroups(src.playlist.id)
        val groups = ArrayList<Pair<String, List<Channel>>>()
        store.favLists().forEach { fl ->
            val l = fl.keys.mapNotNull { byKey[it]?.firstOrNull() }.sortedBy { it.number }
            if (l.isNotEmpty()) groups.add("★ ${fl.name}" to l)
        }
        groups.add(PlayerActivity.G_ALL to src.channels.filter { it.group !in hidden })
        src.groups.filter { it !in hidden }.forEach { g -> groups.add(g to (src.byGroup[g] ?: emptyList())) }
        AlertDialog.Builder(this)
            .setTitle("Gruppe wählen")
            .setItems(groups.map { "${it.first}  (${it.second.size})" }.toTypedArray()) { _, w -> pickChannel(src, groups[w].second) }
            .show()
    }

    /** Schritt 4: Sender wählen */
    private fun pickChannel(src: Repo.Loaded, l: List<Channel>) {
        AlertDialog.Builder(this)
            .setTitle("Sender wählen")
            .setItems(l.map { c -> "${c.number}  ${c.name}" + (Repo.nowNext(c).first?.let { "  –  ${it.title}" } ?: "") }.toTypedArray()) { _, w ->
                list[sel].set(l[w], src)
                highlight()
            }.show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
