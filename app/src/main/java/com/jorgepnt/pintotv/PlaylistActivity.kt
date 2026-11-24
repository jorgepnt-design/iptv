package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        store = Store(this)
        list = findViewById(R.id.plList)
        adapter = TextAdapter(
            onClick = { pos -> select(store.playlists()[pos]) },
            onLongClick = { pos -> options(store.playlists()[pos]) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<Button>(R.id.addXtream).setOnClickListener { editXtream(null) }
        findViewById<Button>(R.id.addM3u).setOnClickListener { editM3u(null) }
        refresh()
        if (store.playlists().isEmpty()) findViewById<Button>(R.id.addXtream).requestFocus()
    }

    private fun refresh() {
        val active = store.active()?.id
        val pls = store.playlists()
        adapter.items = pls.map { TextItem((if (it.id == active) "● " else "") + it.name, if (it.type == "xtream") "Xtream" else "M3U") }
        list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun select(p: Playlist) {
        store.activeId = p.id
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun options(p: Playlist) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Bearbeiten", "Löschen")) { _, which ->
                if (which == 0) { if (p.type == "xtream") editXtream(p) else editM3u(p) }
                else AlertDialog.Builder(this).setTitle("„${p.name}“ löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        store.savePlaylists(store.playlists().filter { it.id != p.id })
                        File(filesDir, "ch_${p.id}.json").delete()
                        File(filesDir, "epg_${p.id}.bin").delete()
                        if (store.activeId == p.id) store.activeId = store.playlists().firstOrNull()?.id
                        refresh()
                    }
                    .setNegativeButton("Abbrechen", null).show()
            }.show()
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
