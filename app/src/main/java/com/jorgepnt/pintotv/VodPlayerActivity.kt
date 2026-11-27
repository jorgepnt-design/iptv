package com.jorgepnt.pintotv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Player für Filme und Serienfolgen – mit Spulen und Fortsetzen */
class VodPlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private lateinit var bar: android.widget.LinearLayout
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var timeText: TextView
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private var url = ""
    private var title = ""
    private var startPos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        url = intent.getStringExtra("url") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: ""
        startPos = intent.getLongExtra("pos", 0)
        val pid = intent.getStringExtra("pid")
        val ua = pid?.let { store.userAgent(it) } ?: Http.UA

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            useController = false // keine Knöpfe in der Bildmitte – eigene Leiste unten
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.banner_bg)
            setPadding((40 * d).toInt(), (36 * d).toInt(), (40 * d).toInt(), (24 * d).toInt())
        }
        info = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; text = title; maxLines = 1 }
        progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressDrawable = getDrawable(R.drawable.progress)
        }
        timeText = TextView(this).apply { setTextColor(Color.parseColor("#D9FFFFFF")); textSize = 15f }
        bar.addView(info)
        bar.addView(progress, android.widget.LinearLayout.LayoutParams(-1, (6 * d).toInt()).apply { topMargin = (10 * d).toInt() })
        bar.addView(timeText, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        player = Players.create(this) { ua }
        view.player = player
        // Untertitel standardmäßig aus (oder wie zuletzt gewählt)
        val subs = store.getString("vodSubs", "off")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, subs == "off")
            .apply { if (subs != "off" && subs != "on") setPreferredTextLanguage(subs) }
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    store.setResumePos(url, 0, 0)
                    val next = intent.getStringExtra("nextUrl")
                    if (next != null) {
                        Toast.makeText(this@VodPlayerActivity, "Nächste Folge …", Toast.LENGTH_SHORT).show()
                        url = next; title = intent.getStringExtra("nextTitle") ?: title
                        intent.removeExtra("nextUrl")
                        info.text = title
                        startPos = 0
                        start()
                    } else finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@VodPlayerActivity, "Abspielen nicht möglich (${error.errorCodeName})", Toast.LENGTH_LONG).show()
            }
        })
        start()
    }

    private fun start() {
        player.setMediaItem(Players.item(url), startPos)
        player.prepare()
        player.playWhenReady = true
        showBar()
    }

    // ---------- eigene Leiste unten ----------

    private fun fmtTime(ms: Long): String {
        if (ms < 0) return "--:--"
        val s = ms / 1000
        return if (s >= 3600) String.format(java.util.Locale.GERMANY, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.GERMANY, "%02d:%02d", s / 60, s % 60)
    }

    private val barUpdater = object : Runnable {
        override fun run() {
            val d = player.duration; val p = player.currentPosition
            progress.progress = if (d > 0) (p * 1000 / d).toInt() else 0
            val state = if (!player.playWhenReady) "⏸ Pause   ·   " else ""
            timeText.text = state + fmtTime(p) + " / " + (if (d > 0) fmtTime(d) else "--:--") +
                "        ◀ ▶ spulen · ▲ ▼ 5 Min. · OK Pause · OK lang: Untertitel/Ton"
            if (bar.visibility == View.VISIBLE) ui.postDelayed(this, 500)
        }
    }
    private val hideBar = Runnable { if (player.playWhenReady) bar.visibility = View.GONE }

    private fun showBar() {
        bar.visibility = View.VISIBLE
        ui.removeCallbacks(barUpdater); ui.post(barUpdater)
        ui.removeCallbacks(hideBar); ui.postDelayed(hideBar, 4000)
    }

    private val saver = object : Runnable {
        override fun run() { save(); ui.postDelayed(this, 10000) }
    }

    private fun save() {
        val d = player.duration
        val p = player.currentPosition
        if (d <= 0 || p < 5000) return
        if (p > d * 0.95) store.setResumePos(url, 0, 0) else store.setResumePos(url, p, d)
    }

    override fun onStart() { super.onStart(); ui.postDelayed(saver, 10000); player.playWhenReady = true }

    override fun onStop() {
        super.onStop()
        save()
        ui.removeCallbacks(saver)
        player.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        player.release()
    }

    /** Untertitel ein/aus und Tonspur wählen */
    private fun tracksDialog() {
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        val params = player.trackSelectionParameters
        val subsOff = C.TRACK_TYPE_TEXT in params.disabledTrackTypes
        labels.add((if (subsOff) "● " else "    ") + "Untertitel aus")
        acts.add {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
            store.putString("vodSubs", "off")
            Toast.makeText(this, "Untertitel aus", Toast.LENGTH_SHORT).show()
        }
        fun name(f: androidx.media3.common.Format, i: Int): String {
            val lang = f.language?.let { l -> java.util.Locale(l).getDisplayLanguage(java.util.Locale.GERMAN).ifBlank { l } }
            return listOfNotNull(f.label, lang).distinct().joinToString(" · ").ifBlank { "Spur ${i + 1}" }
        }
        var n = 0
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i); val idx = n++
                labels.add((if (!subsOff && g.isTrackSelected(i)) "● " else "    ") + "Untertitel: " + name(f, idx))
                acts.add {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    store.putString("vodSubs", f.language ?: "on")
                }
            }
        }
        if (n == 0) labels.add("    (dieser Film hat keine wählbaren Untertitel)").also { acts.add {} }
        var a = 0
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i); val idx = a++
                labels.add((if (g.isTrackSelected(i)) "● " else "    ") + "Ton: " + name(f, idx))
                acts.add {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                }
            }
        }
        android.app.AlertDialog.Builder(this).setTitle("Untertitel & Ton")
            .setItems(labels.toTypedArray()) { _, w -> acts.getOrNull(w)?.invoke() }.show()
    }

    private fun seek(delta: Long) {
        val d = player.duration
        var t = player.currentPosition + delta
        if (t < 0) t = 0
        if (d > 0 && t > d - 2000) t = d - 2000
        player.seekTo(t)
        showBar()
    }

    private fun isOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
    private var okDown = 0L
    private var okLongDone = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // OK kurz = Pause, OK lang = Untertitel & Ton
        if (isOk(e.keyCode)) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { okDown = e.eventTime; okLongDone = false }
                else if (!okLongDone && e.eventTime - okDown > 600) { okLongDone = true; tracksDialog() }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP) {
                if (!okLongDone) { player.playWhenReady = !player.playWhenReady; showBar() }
                return true
            }
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        // Lange gedrückt halten spult schneller
        val fast = if (e.repeatCount > 5) 6 else 1
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-10_000L * fast); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L * fast); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { seek(5 * 60_000L); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { seek(-5 * 60_000L); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (e.repeatCount == 0) player.playWhenReady = !player.playWhenReady
                showBar(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.playWhenReady = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.playWhenReady = false; showBar(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> { tracksDialog(); return true }
            KeyEvent.KEYCODE_INFO -> { showBar(); return true }
        }
        return super.dispatchKeyEvent(e)
    }
}
