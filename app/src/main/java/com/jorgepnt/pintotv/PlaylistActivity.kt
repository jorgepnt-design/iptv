package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.UUID

class PlaylistActivity : Activity() {

    private lateinit var store: Store
    private lateinit var adapter: TextAdapter
    private lateinit var list: RecyclerView
    private var phoneServer: PhoneSetupServer? = null
    private var marked = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        store = Store(this)
        list = findViewById(R.id.plList)
        findViewById<TextView>(R.id.plVersion).text = "PintoTV ${appVersion(this)}"
        adapter = TextAdapter(
            onFocus = { pos -> if (pos >= 0) marked = pos },
            onClick = { pos -> store.playlists().getOrNull(pos)?.let { select(it) } },
            onLongClick = { pos -> store.playlists().getOrNull(pos)?.let { options(it) } }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        findViewById<Button>(R.id.addXtream).setOnClickListener { editXtream(null) }
        findViewById<Button>(R.id.addM3u).setOnClickListener { editM3u(null) }
        findViewById<Button>(R.id.editPl).setOnClickListener {
            store.playlists().getOrNull(marked)?.let { p -> if (p.type == "xtream") editXtream(p) else editM3u(p) }
        }
        findViewById<Button>(R.id.deletePl).setOnClickListener { store.playlists().getOrNull(marked)?.let { confirmDelete(it) } }
        refresh()
        if (store.playlists().isEmpty()) findViewById<Button>(R.id.addXtream).requestFocus()
        startPhoneSetup()
    }

    private fun startPhoneSetup() {
        val pane = findViewById<View>(R.id.phonePane)
        val ip = PhoneSetupServer.localIp()
        val srv = PhoneSetupServer { p ->
            runOnUiThread {
                Toast.makeText(this, "Playlist „${p.name}“ vom Handy empfangen", Toast.LENGTH_LONG).show()
                save(null, p)
            }
        }
        if (ip == null || !srv.start()) { pane.visibility = View.GONE; return }
        phoneServer = srv
        val url = if (srv.port == 80) "http://$ip" else "http://$ip:${srv.port}"
        findViewById<TextView>(R.id.phoneUrl).text = url.removePrefix("http://")
        findViewById<ImageView>(R.id.qr).setImageBitmap(qr(url, 400))
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneServer?.stop()
    }

    private fun refresh() {
        val active = store.active()?.id
        val pls = store.playlists()
        adapter.items = pls.map { TextItem((if (it.id == active) "● " else "") + it.name, if (it.type == "xtream") "Xtream" else "M3U") }
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun select(p: Playlist) {
        Toast.makeText(this, "„${p.name}“ wird geladen …", Toast.LENGTH_SHORT).show()
        store.activeId = p.id
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun options(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Bearbeiten", "Player-Kennung wählen", "Löschen")) { _, which ->
                if (which == 0) { if (p.type == "xtream") editXtream(p) else editM3u(p) }
                else if (which == 1) chooseUa(p)
                else confirmDelete(p)
            }.show()
    }

    private fun confirmDelete(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle("Playlist „${p.name}“ löschen?")
            .setMessage(if (p.type == "xtream") "Xtream · ${p.server}" else "M3U · ${p.url}")
            .setPositiveButton("Löschen") { _, _ ->
                store.savePlaylists(store.playlists().filter { it.id != p.id })
                File(filesDir, "ch_${p.id}.json").delete()
                File(filesDir, "epg_${p.id}.bin").delete()
                if (store.activeId == p.id) store.activeId = store.playlists().firstOrNull()?.id
                Toast.makeText(this, "„${p.name}“ gelöscht", Toast.LENGTH_SHORT).show()
                marked = 0
                deletedActive = deletedActive || Repo.playlistId == p.id
                refresh()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private var deletedActive = false

    override fun finish() {
        if (deletedActive) setResult(RESULT_OK)
        super.finish()
    }

    private fun chooseUa(p: Playlist) {
        val names = listOf("Automatisch (empfohlen)", "Wie TiviMate", "Wie IPTV Smarters", "okhttp", "VLC", "Browser (Chrome)", "FFmpeg / Lavf", "Android-System", "PintoTV", "Eigene Kennung eingeben …")
        val current = store.userAgent(p.id)
        val idx = Http.CANDIDATES.indexOf(current)
        val sel = if (!store.userAgentFixed(p.id)) 0 else if (idx >= 0) idx + 1 else names.size - 1
        AlertDialog.Builder(this)
            .setTitle("Player-Kennung für „${p.name}“")
            .setSingleChoiceItems(names.toTypedArray(), sel) { d, w ->
                if (w == names.size - 1) { d.dismiss(); customUa(p); return@setSingleChoiceItems }
                store.setUserAgentFixed(p.id, if (w == 0) null else Http.CANDIDATES[w - 1])
                File(filesDir, "ch_${p.id}.json").delete()
                d.dismiss()
                Toast.makeText(this, "Gespeichert – Playlist wird neu geladen", Toast.LENGTH_SHORT).show()
                store.activeId = p.id
                setResult(RESULT_OK)
                finish()
            }.show()
    }

    private fun customUa(p: Playlist) {
        val f = field("z. B. TiviMate/5.1.6 (Android 11)", store.userAgent(p.id) ?: "")
        AlertDialog.Builder(this)
            .setTitle("Eigene Player-Kennung")
            .setView(form(f))
            .setPositiveButton("Speichern") { _, _ ->
                val ua = f.text.toString().trim()
                if (ua.isEmpty()) return@setPositiveButton
                store.setUserAgentFixed(p.id, ua)
                File(filesDir, "ch_${p.id}.json").delete()
                store.activeId = p.id
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun form(vararg f: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        f.forEach { addView(it) }
    }

    private fun editXtream(old: Playlist?) {
        val name = field("Name (z. B. Mein Anbieter)", old?.name ?: "")
        val server = field("Server-Adresse (http://…:port)", old?.server ?: "http://")
        val user = field("Benutzername", old?.user ?: "")
        val pass = field("Passwort", old?.pass ?: "", true)
        val epg = field("EPG-Adresse (optional, sonst automatisch)", old?.epgUrl ?: "")
        AlertDialog.Builder(this)
            .setTitle("Xtream-Codes")
            .setView(form(name, server, user, pass, epg))
            .setPositiveButton("Speichern") { _, _ ->
                if (server.text.isBlank() || user.text.isBlank()) return@setPositiveButton
                save(old, Playlist(old?.id ?: UUID.randomUUID().toString(),
                    name.text.toString().ifBlank { "Xtream" }, "xtream", "",
                    server.text.toString().trim(), user.text.toString().trim(), pass.text.toString().trim(),
                    epg.text.toString().trim()))
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun editM3u(old: Playlist?) {
        val name = field("Name", old?.name ?: "")
        val url = field("Playlist-Adresse (http://…m3u)", old?.url ?: "http://")
        val epg = field("EPG-Adresse XMLTV (optional)", old?.epgUrl ?: "")
        AlertDialog.Builder(this)
            .setTitle("M3U-Playlist")
            .setView(form(name, url, epg))
            .setPositiveButton("Speichern") { _, _ ->
                if (url.text.isBlank()) return@setPositiveButton
                save(old, Playlist(old?.id ?: UUID.randomUUID().toString(),
                    name.text.toString().ifBlank { "M3U" }, "m3u", url.text.toString().trim(),
                    "", "", "", epg.text.toString().trim()))
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun save(old: Playlist?, p: Playlist) {
        val l = store.playlists().filter { it.id != p.id } + p
        store.savePlaylists(l)
        store.activeId = p.id
        if (old != null) {
            File(filesDir, "ch_${p.id}.json").delete()
            File(filesDir, "epg_${p.id}.bin").delete()
        }
        setResult(RESULT_OK)
        finish()
    }
}
