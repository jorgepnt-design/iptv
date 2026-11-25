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
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
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
        findViewById<TextView>(R.id.plVersion).text = "PintoTV ${appVersion(this)}   ·   Gerätecode: ${Remote.deviceCode(this)}"
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
        findViewById<Button>(R.id.transferPl).setOnClickListener { transferMenu() }
        findViewById<Button>(R.id.deletePl).setOnClickListener { store.playlists().getOrNull(marked)?.let { confirmDelete(it) } }
        refresh()
        if (store.playlists().isEmpty()) findViewById<Button>(R.id.addXtream).requestFocus()
        startPhoneSetup()
    }

    private fun startPhoneSetup() {
        val pane = findViewById<View>(R.id.phonePane)
        val ip = PhoneSetupServer.localIp()
        val srv = PhoneSetupServer(
            onPlaylist = { p ->
                runOnUiThread {
                    Toast.makeText(this, "Playlist „${p.name}“ vom Handy empfangen", Toast.LENGTH_LONG).show()
                    save(null, p)
                }
            },
            exportBackup = { store.exportJson() },
            onRestore = { json, from -> runOnUiThread { confirmRestore(json, from) } }
        )
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

    // ---------- Fern-Einrichtung ----------

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var remoteBusy = false
    private val remotePoll = object : Runnable {
        override fun run() { checkRemote(quiet = true); ui.postDelayed(this, 15000) }
    }

    override fun onResume() { super.onResume(); ui.postDelayed(remotePoll, 1500) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(remotePoll) }

    private fun checkRemote(quiet: Boolean) {
        if (remoteBusy) return
        remoteBusy = true
        scope.launch {
            val items = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Remote.fetch(this@PlaylistActivity).also { l -> l.lastOrNull()?.let { Remote.clear(this@PlaylistActivity, it.id) } }
                }
            } catch (e: Exception) {
                if (!quiet) Toast.makeText(this@PlaylistActivity, "Abruf fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                null
            }
            remoteBusy = false
            if (items == null) return@launch
            if (items.isEmpty()) { if (!quiet) Toast.makeText(this@PlaylistActivity, "Für ${Remote.deviceCode(this@PlaylistActivity)} liegt nichts bereit", Toast.LENGTH_LONG).show(); return@launch }
            Remote.offer(this@PlaylistActivity, items,
                onPlaylists = { pls ->
                    store.savePlaylists(store.playlists() + pls)
                    store.activeId = pls.last().id
                    Toast.makeText(this@PlaylistActivity, "Playlist hinzugefügt – wird geladen", Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK); finish()
                },
                onBackup = { json -> applyBackup(json) })
        }
    }

    private fun showDeviceCode() {
        val code = Remote.deviceCode(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(40, 20, 40, 10)
            addView(TextView(this@PlaylistActivity).apply {
                text = code; textSize = 34f; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
            })
            addView(ImageView(this@PlaylistActivity).apply { setImageBitmap(qr(Remote.setupUrl(this@PlaylistActivity), 360)) },
                LinearLayout.LayoutParams(360, 360).apply { topMargin = 16 })
            addView(TextView(this@PlaylistActivity).apply {
                text = "Diesen Code an den schicken, der dein Gerät einrichtet.\n" +
                    "Er gibt ihn auf ${Remote.BASE.removePrefix("https://")} ein (oder scannt den QR-Code) " +
                    "oder schickt aus seiner PintoTV-App: Playlists → ⇄ Übertragen → „Meine Einrichtung an Gerätecode senden“.\n\n" +
                    "Solange dieser Bildschirm offen ist, kommt die Einrichtung automatisch an."
                textSize = 15f; setTextColor(Color.parseColor("#B8C0CE"))
                setPadding(0, 18, 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("🌐 Fern-Einrichtung")
            .setView(box)
            .setPositiveButton("Jetzt abrufen") { _, _ -> checkRemote(quiet = false) }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun sendToCode() {
        val input = EditText(this).apply {
            hint = "PT-XXXX-XXXX"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this)
            .setTitle("Meine Einrichtung an Gerätecode senden")
            .setMessage("Alle Playlists, Favoriten und Einstellungen dieses Fernsehers werden an das andere Gerät geschickt und ersetzen dort die Einrichtung.\n\nGerätecode des anderen Geräts:")
            .setView(input)
            .setPositiveButton("Senden") { _, _ ->
                val code = Remote.normalize(input.text.toString())
                if (code == null) { Toast.makeText(this, "Ungültiger Gerätecode", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                if (code == Remote.deviceCode(this)) { Toast.makeText(this, "Das ist der Code dieses Geräts", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                chooseScope(code)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ---------- Auswahl, was der andere bekommt ----------

    private class Pick(var playlists: Set<String>, var countries: Set<String>?, var favLists: Set<String>) {
        /** genau ausgeblendete Gruppen je Bereich (live / movies / series); fehlt = wie bei mir */
        val hidden = HashMap<String, Set<String>>()
    }

    private fun chooseScope(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Was soll $code bekommen?")
            .setItems(arrayOf(
                "Alles wie bei mir",
                "Genau auswählen: Playlists, Gruppen (TV, Filme, Serien), Favoriten …"
            )) { _, w ->
                val all = Pick(store.playlists().map { it.id }.toSet(), null, store.favLists().map { it.name }.toSet())
                if (w == 0) send(code, all) else pickPlaylists(code, all)
            }.show()
    }

    private fun pickPlaylists(code: String, pick: Pick) {
        val pls = store.playlists()
        if (pls.size <= 1) { pickCountries(code, pick); return }
        val checked = BooleanArray(pls.size) { true }
        Dialogs.multiChoice(this, "Welche Playlists?", pls.map { it.name }.toTypedArray(), checked) {
            pick.playlists = pls.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
            if (pick.playlists.isEmpty()) { Toast.makeText(this, "Mindestens eine Playlist wählen", Toast.LENGTH_LONG).show(); return@multiChoice }
            pickCountries(code, pick)
        }
    }

    private fun pickCountries(code: String, pick: Pick) {
        val pid = Repo.playlistId
        if (pid == null || pid !in pick.playlists || Repo.groups.isEmpty()) { pickFavs(code, pick); return }
        pickSection(code, "📺 TV", Repo.groups, store.hiddenGroups(pid)) { h ->
            h?.let { pick.hidden["live"] = it }
            val p = store.playlists().first { it.id == pid }
            loadVodNames(p, false) { movies ->
                pickSection(code, "🎬 Filme", movies, store.hiddenGroups("$pid:movies")) { hm ->
                    hm?.let { pick.hidden["movies"] = it }
                    loadVodNames(p, true) { series ->
                        pickSection(code, "📺 Serien", series, store.hiddenGroups("$pid:series")) { hs ->
                            hs?.let { pick.hidden["series"] = it }
                            pickFavs(code, pick)
                        }
                    }
                }
            }
        }
    }

    private fun loadVodNames(p: Playlist, series: Boolean, then: (List<String>) -> Unit) {
        val t = Toast.makeText(this, if (series) "Serien-Kategorien werden geladen …" else "Film-Kategorien werden geladen …", Toast.LENGTH_LONG).also { it.show() }
        scope.launch {
            val names = try { Vod.catalog(this@PlaylistActivity, p, series, null).categories.map { it.name } } catch (e: Exception) { emptyList() }
            t.cancel()
            then(names)
        }
    }

    /** Ein Bereich (TV/Filme/Serien): wie bei mir, alles, nach Ländern (+ einzeln anpassen) oder komplett einzeln */
    private fun pickSection(code: String, title: String, names: List<String>, mine: Set<String>, done: (Set<String>?) -> Unit) {
        if (names.isEmpty()) { done(null); return }
        val byCountry = LinkedHashMap<String, MutableList<String>>()
        names.forEach { g -> byCountry.getOrPut(Countries.code(g)) { mutableListOf() }.add(g) }
        val codes = byCountry.keys.sortedWith(compareBy({ it == "?" }, { it == "XXX" }, { -(byCountry[it]?.size ?: 0) }))
        fun fineTune(visible: List<String>) {
            val checked = BooleanArray(visible.size) { true }
            Dialogs.multiChoice(this, "$title – einzelne Gruppen (Haken = $code bekommt sie)", visible.toTypedArray(), checked) {
                val keep = visible.filterIndexed { i, _ -> checked[i] }.toSet()
                done(names.filter { it !in keep }.toSet())
            }
        }
        AlertDialog.Builder(this)
            .setTitle("$title – was soll $code sehen? (${names.size} Gruppen)")
            .setItems(arrayOf(
                "Wie bei mir (${names.size - mine.count { it in names }} sichtbar)",
                "Nach Ländern auswählen …",
                "Einzelne Gruppen auswählen …",
                "Alles",
                if (byCountry.containsKey("XXX")) "Alles außer 🔞 Erwachsene" else null
            ).filterNotNull().toTypedArray()) { _, w ->
                when (w) {
                    0 -> done(mine.filter { it in names }.toSet())
                    1 -> {
                        val checked = BooleanArray(codes.size) { i -> byCountry[codes[i]]!!.any { it !in mine } && codes[i] != "XXX" }
                        val labels = codes.map { c -> "${Countries.label(c)}  ·  ${byCountry[c]!!.size} Gruppen" }.toTypedArray()
                        Dialogs.multiChoice(this, "$title – welche Länder?", labels, checked) {
                            val visible = codes.filterIndexed { i, _ -> checked[i] }.flatMap { byCountry[it]!! }
                            if (visible.isEmpty()) { done(names.toSet()); return@multiChoice }
                            AlertDialog.Builder(this)
                                .setTitle("${visible.size} Gruppen ausgewählt")
                                .setMessage("Möchtest du in diesen Ländern noch einzelne Gruppen abwählen?")
                                .setPositiveButton("Einzeln anpassen") { _, _ -> fineTune(visible) }
                                .setNegativeButton("So übernehmen") { _, _ -> done(names.filter { it !in visible }.toSet()) }
                                .show()
                        }
                    }
                    2 -> fineTune(codes.flatMap { byCountry[it]!! })
                    3 -> done(emptySet())
                    4 -> done(byCountry["XXX"]!!.toSet())
                }
            }.show()
    }

    private fun pickFavs(code: String, pick: Pick) {
        val fl = store.favLists().filter { it.keys.isNotEmpty() }
        if (fl.isEmpty()) { send(code, pick); return }
        val checked = BooleanArray(fl.size) { true }
        Dialogs.multiChoice(this, "Welche Favoritenlisten?", fl.map { "★ ${it.name}  (${it.keys.size} Sender)" }.toTypedArray(), checked) {
            pick.favLists = fl.filterIndexed { i, _ -> checked[i] }.map { it.name }.toSet()
            send(code, pick)
        }
    }

    /** Sicherung für den anderen zusammenstellen: nur das Gewählte, ohne persönlichen Verlauf */
    private suspend fun buildFor(pick: Pick): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val root = JSONObject(store.exportJson())
        val prefs = root.getJSONObject("prefs")
        val removedPids = store.playlists().map { it.id }.filter { it !in pick.playlists }
        prefs.keys().asSequence().toList().forEach { k ->
            val personal = k.startsWith("recent_") || k.startsWith("pos_") || k.startsWith("dur_") || k == "recDir" || k == "lastBackup"
            val ofRemoved = removedPids.any { k.endsWith(it) || k.contains("_$it") || k.startsWith("$it:") || k.contains("$it:") }
            if (personal || ofRemoved) prefs.remove(k)
        }
        // Playlists
        val plArr = JSONArray(store.playlists().filter { it.id in pick.playlists }.map { it.toJson() })
        prefs.put("playlists", JSONObject().put("t", "s").put("v", plArr.toString()))
        if (store.activeId !in pick.playlists) pick.playlists.firstOrNull()?.let { prefs.put("active", JSONObject().put("t", "s").put("v", it)) }
        // Favoritenlisten
        val favs = JSONArray()
        store.favLists().filter { it.name in pick.favLists }.forEach { f ->
            favs.put(JSONObject().put("n", f.name).put("k", JSONArray(f.keys.toList())).put("m", f.manual))
        }
        if (favs.length() == 0) favs.put(JSONObject().put("n", "Favoriten").put("k", JSONArray()))
        prefs.put("favlists", JSONObject().put("t", "s").put("v", favs.toString()))
        // genaue Auswahl je Bereich
        val pid = Repo.playlistId
        if (pid != null) {
            fun put(key: String, h: Set<String>) = prefs.put("hidden_$key", JSONObject().put("t", "set").put("v", JSONArray(h.toList())))
            pick.hidden["live"]?.let { put(pid, it) }
            pick.hidden["movies"]?.let { put("$pid:movies", it) }
            pick.hidden["series"]?.let { put("$pid:series", it) }
        }
        root.toString()
    }

    private fun send(code: String, pick: Pick) {
        val wait = Toast.makeText(this, "Wird vorbereitet und gesendet …", Toast.LENGTH_LONG).also { it.show() }
        scope.launch {
            val json = try { buildFor(pick) } catch (e: Exception) {
                wait.cancel(); Toast.makeText(this@PlaylistActivity, "Fehler beim Vorbereiten: ${e.message}", Toast.LENGTH_LONG).show(); return@launch
            }
            run {
                    val err = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Remote.sendBackup(code, json) }; null } catch (e: Exception) { e.message ?: "Fehler" }
                    if (err == null) AlertDialog.Builder(this@PlaylistActivity).setTitle("✓ Gesendet an $code")
                        .setMessage("Sobald auf dem anderen Gerät PintoTV geöffnet wird, erscheint dort die Frage, ob die Einrichtung übernommen werden soll.")
                        .setPositiveButton("OK", null).show()
                    else Toast.makeText(this@PlaylistActivity, "Senden fehlgeschlagen: $err", Toast.LENGTH_LONG).show()
            }
            wait.cancel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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

    // ---------- Übertragen / Sichern ----------

    private val scope = kotlinx.coroutines.MainScope()

    private fun transferMenu() {
        AlertDialog.Builder(this)
            .setTitle("Einstellungen übertragen")
            .setItems(arrayOf(
                "📺 An anderen Fernseher senden",
                "📥 Von anderem Fernseher empfangen",
                "📱 Mit dem Handy sichern / laden",
                "🗂 Automatische Sicherungen (wöchentlich)",
                "🌐 Fern-Einrichtung: Gerätecode anzeigen",
                "📤 Meine Einrichtung an Gerätecode senden"
            )) { _, w ->
                when (w) {
                    4 -> showDeviceCode()
                    5 -> sendToCode()
                    3 -> showAutoBackups()
                    0 -> scanAndSend()
                    1 -> AlertDialog.Builder(this).setTitle("Empfangen")
                        .setMessage("Dieser Fernseher ist jetzt empfangsbereit, solange dieser Playlist-Bildschirm offen ist.\n\n" +
                            "Auf dem anderen Fernseher: PintoTV → Playlists → „⇄ Übertragen“ → „An anderen Fernseher senden“.\n\n" +
                            "Beide Geräte müssen im selben WLAN sein.")
                        .setPositiveButton("OK", null).show()
                    2 -> AlertDialog.Builder(this).setTitle("Mit dem Handy")
                        .setMessage("Scanne den QR-Code rechts mit dem Handy. Unten auf der Seite findest du:\n\n" +
                            "• „Sicherung aufs Handy laden“ – speichert alles als Datei\n" +
                            "• „Sicherung an Fernseher senden“ – spielt eine Datei auf diesen Fernseher ein")
                        .setPositiveButton("OK", null).show()
                }
            }.show()
    }

    private fun showAutoBackups() {
        scope.launch {
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AutoBackup.list(this@PlaylistActivity) }
            val labels = files.map { f -> "Sicherung vom ${AutoBackup.label(f)}" } + "💾 Jetzt sichern"
            AlertDialog.Builder(this@PlaylistActivity)
                .setTitle("Automatische Sicherungen")
                .setItems(labels.toTypedArray()) { _, w ->
                    if (w == files.size) {
                        scope.launch {
                            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AutoBackup.runIfDue(this@PlaylistActivity, force = true) }
                            Toast.makeText(this@PlaylistActivity, if (f != null) "Gesichert" else "Nichts zu sichern", Toast.LENGTH_SHORT).show()
                        }
                        return@setItems
                    }
                    val f = files[w]
                    scope.launch {
                        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { f.readText() }
                        confirmRestore(json, "Sicherung vom ${AutoBackup.label(f)}")
                    }
                }
                .setNegativeButton("Schließen", null)
                .show()
        }
    }

    private fun scanAndSend() {
        val myIp = PhoneSetupServer.localIp()
        if (myIp == null) { Toast.makeText(this, "Kein WLAN gefunden", Toast.LENGTH_LONG).show(); return }
        val dlg = AlertDialog.Builder(this).setTitle("Suche Fernseher mit PintoTV …")
            .setMessage("Auf dem anderen Fernseher muss PintoTV geöffnet sein, am besten auf dem Playlist-Bildschirm.")
            .setNegativeButton("Abbrechen", null).create()
        dlg.show()
        val job = scope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { scanLan(myIp) }
            dlg.dismiss()
            if (found.isEmpty()) {
                AlertDialog.Builder(this@PlaylistActivity).setTitle("Kein Gerät gefunden")
                    .setMessage("Öffne PintoTV auf dem anderen Fernseher und geh dort auf „Playlists“. Dann nochmal versuchen.\n\nBeide Geräte müssen im selben WLAN sein.")
                    .setPositiveButton("Nochmal suchen") { _, _ -> scanAndSend() }
                    .setNegativeButton("Abbrechen", null).show()
                return@launch
            }
            AlertDialog.Builder(this@PlaylistActivity)
                .setTitle("An welches Gerät senden?")
                .setItems(found.map { "${it.second}  (${it.first.substringAfter("//").substringBefore(":")})" }.toTypedArray()) { _, w ->
                    sendTo(found[w].first)
                }.show()
        }
        dlg.setOnCancelListener { job.cancel() }
        dlg.setOnDismissListener { }
    }

    /** sucht im WLAN nach anderen PintoTV-Geräten (gleiches /24-Netz) */
    private suspend fun scanLan(myIp: String): List<Pair<String, String>> = kotlinx.coroutines.coroutineScope {
        val prefix = myIp.substringBeforeLast('.')
        val sem = kotlinx.coroutines.sync.Semaphore(48)
        (1..254).map { i -> "$prefix.$i" }.filter { it != myIp }.flatMap { ip -> listOf(8080, 8081, 8088, 8090).map { ip to it } }
            .map { (ip, port) ->
                async {
                    sem.acquire()
                    try {
                        val c = java.net.URL("http://$ip:$port/ping").openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 400; c.readTimeout = 800
                        val t = c.inputStream.bufferedReader().use { it.readText() }
                        if (t.startsWith("PintoTV:")) "http://$ip:$port" to t.substringAfter(':') else null
                    } catch (e: Exception) { null } finally { sem.release() }
                }
            }.awaitAll().filterNotNull().distinctBy { it.first.substringBeforeLast(':') }
    }

    private fun sendTo(base: String) {
        scope.launch {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val c = java.net.URL("$base/restore").openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 10000
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(store.exportJson().toByteArray(Charsets.UTF_8)) }
                    c.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) { "Fehler: ${e.message}" }
            }
            AlertDialog.Builder(this@PlaylistActivity).setTitle("Gesendet")
                .setMessage(if (res.startsWith("OK")) "Alles wurde gesendet. Bitte jetzt am anderen Fernseher mit „Übernehmen“ bestätigen." else res)
                .setPositiveButton("OK", null).show()
        }
    }

    private fun confirmRestore(json: String, from: String) {
        AlertDialog.Builder(this)
            .setTitle("Sicherung empfangen")
            .setMessage("${if (from.startsWith("Sicherung")) from else "Von " + (if (from.isNotBlank()) from else "einem Gerät")}:\n${store.describe(json)}\n\n" +
                "Übernehmen? Die Einstellungen auf diesem Fernseher werden dabei ersetzt.")
            .setPositiveButton("Übernehmen") { _, _ -> applyBackup(json) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun applyBackup(json: String) {
        try {
            store.importJson(json)
            filesDir.listFiles()?.filter { it.name.startsWith("ch_") || it.name.startsWith("epg_") }?.forEach { it.delete() }
            Vod.clearCache()
            Toast.makeText(this, "Übernommen – Playlist wird geladen", Toast.LENGTH_LONG).show()
            deletedActive = true
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Sicherung fehlerhaft: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
