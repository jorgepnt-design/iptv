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

object Players {
    fun create(ctx: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        return ExoPlayer.Builder(ctx)
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
        }

        fun set(c: Channel?) {
            channel = c
            tiles[index] = c?.url
            if (c == null) {
                player?.release(); player = null; view.player = null
                label.visibility = View.GONE; hint.visibility = View.VISIBLE
                return
            }
            hint.visibility = View.GONE
            val (cur, _) = Repo.nowNext(c)
            label.text = "${c.number}  ${c.name}" + (cur?.let { "  ·  ${it.title}" } ?: "")
            label.visibility = View.VISIBLE
            val p = player ?: Players.create(this@MultiViewActivity).also { np ->
                player = np
                view.player = np
                np.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                            np.seekToDefaultPosition(); np.prepare()
                        } else {
                            label.text = "${channel?.name ?: ""}  ·  nicht verfügbar"
                            view.postDelayed({ if (player === np && channel != null) { np.prepare(); np.play() } }, 4000)
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
            text = "Multi-View  ·  Fenster wählen = Ton  ·  OK/Antippen: Sender  ·  Menü/lang drücken: 2 oder 4 Fenster  ·  Zurück: Ende"
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 20 })
        info.postDelayed({ info.visibility = View.GONE }, 6000)
        setContentView(root)

        // aktueller Sender aus dem Hauptbild ins erste freie Fenster
        intent.getStringExtra("url")?.let { u -> if (u !in tiles) { val i = tiles.indexOfFirst { it == null }; if (i >= 0) tiles[i] = u } }
        applyLayout()
        val byUrl = Repo.channels.associateBy { it.url }
        for (i in 0 until 4) list[i].channel = tiles[i]?.let { byUrl[it] }
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
        val l = Repo.byGroup[c.group] ?: Repo.channels
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
                    1 -> { setResult(RESULT_OK, Intent().putExtra("url", c.url)); finish() }
                    2 -> list[sel].set(null)
                    3 -> toggleLayout()
                }
            }.show()
    }

    private fun pickGroup() {
        if (Repo.channels.isEmpty()) { toast("Keine Kanäle geladen"); return }
        val pid = Repo.playlistId ?: return
        val favs = Store(this).favorites(pid)
        val favList = Repo.channels.filter { it.url in favs }
        val groups = ArrayList<Pair<String, List<Channel>>>()
        if (favList.isNotEmpty()) groups.add(PlayerActivity.G_FAV to favList)
        groups.add(PlayerActivity.G_ALL to Repo.channels)
        Repo.groups.forEach { g -> groups.add(g to (Repo.byGroup[g] ?: emptyList())) }
        AlertDialog.Builder(this)
            .setTitle("Gruppe wählen")
            .setItems(groups.map { "${it.first}  (${it.second.size})" }.toTypedArray()) { _, w -> pickChannel(groups[w].second) }
            .show()
    }

    private fun pickChannel(l: List<Channel>) {
        AlertDialog.Builder(this)
            .setTitle("Sender wählen")
            .setItems(l.map { c -> "${c.number}  ${c.name}" + (Repo.nowNext(c).first?.let { "  –  ${it.title}" } ?: "") }.toTypedArray()) { _, w ->
                list[sel].set(l[w])
                highlight()
            }.show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
