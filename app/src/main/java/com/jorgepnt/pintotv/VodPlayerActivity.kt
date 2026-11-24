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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Player für Filme und Serienfolgen – mit Spulen und Fortsetzen */
class VodPlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var info: TextView
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
            useController = true
            controllerShowTimeoutMs = 3500
            setShowNextButton(false); setShowPreviousButton(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f
            setBackgroundResource(R.drawable.label_bg)
            setPadding(28, 14, 28, 14)
            text = title
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(40, 32, 40, 0) })
        setContentView(root)

        player = Players.create(this) { ua }
        view.player = player
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> info.visibility = v })
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
        view.showController()
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

    private fun seek(delta: Long) {
        val d = player.duration
        var t = player.currentPosition + delta
        if (t < 0) t = 0
        if (d > 0 && t > d - 2000) t = d - 2000
        player.seekTo(t)
        view.showController()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        // Lange gedrückt halten spult schneller
        val fast = if (e.repeatCount > 5) 6 else 1
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-10_000L * fast); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L * fast); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { seek(5 * 60_000L); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { seek(-5 * 60_000L); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (e.repeatCount == 0) player.playWhenReady = !player.playWhenReady
                view.showController(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.playWhenReady = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.playWhenReady = false; view.showController(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> { view.showController(); return true }
        }
        return super.dispatchKeyEvent(e)
    }
}
