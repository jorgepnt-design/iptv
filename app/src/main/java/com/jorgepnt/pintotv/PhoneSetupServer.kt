package com.jorgepnt.pintotv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID

/**
 * Kleiner Web-Server im Heimnetz: Das Handy (z. B. iPhone) öffnet die angezeigte Adresse
 * und trägt dort bequem die Playlist-Daten ein.
 */
class PhoneSetupServer(
    private val onPlaylist: (Playlist) -> Unit,
    /** aktuelle Sicherung als JSON (für Download aufs Handy) */
    private val exportBackup: (() -> String)? = null,
    /** Sicherung empfangen (vom Handy oder einem anderen Fernseher) */
    private val onRestore: ((json: String, from: String) -> Unit)? = null,
    /** Person für die Verwaltung vom Handy (Formularfelder) */
    private val onPerson: ((Map<String, String>) -> Unit)? = null,
    /** vorhandene Person zum Bearbeiten (id → Felder) */
    private val personData: ((String) -> Map<String, String>?)? = null
) {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    var port = 0; private set

    fun start(): Boolean {
        for (p in listOf(8080, 8081, 8088, 8090)) {
            try { server = ServerSocket(p); port = p; break } catch (e: Exception) { }
        }
        val s = server ?: return false
        running = true
        Thread {
            while (running) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                } catch (e: Exception) { if (!running) break }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    private fun handle(sock: Socket) {
        sock.use { c ->
            try {
                c.soTimeout = 15000
                val ins = java.io.BufferedInputStream(c.getInputStream())
                fun line(): String? {
                    val b = java.io.ByteArrayOutputStream()
                    while (true) {
                        val x = ins.read()
                        if (x < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                        if (x == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
                        b.write(x)
                    }
                }
                val requestLine = line() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1) ?: "/"
                var len = 0
                while (true) {
                    val h = line() ?: break
                    if (h.isEmpty()) break
                    if (h.lowercase().startsWith("content-length:")) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val body = if (method == "POST" && len > 0) {
                    val buf = ByteArray(minOf(len, 8_000_000)); var read = 0
                    while (read < buf.size) { val n = ins.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""
                val out = c.getOutputStream()
                fun send(type: String, bytes: ByteArray, extra: String = "") {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n${extra}Connection: close\r\n\r\n").toByteArray())
                    out.write(bytes); out.flush()
                }
                // Erkennung durch andere PintoTV-Geräte im WLAN
                if (path.startsWith("/ping")) { send("text/plain", "PintoTV:${android.os.Build.MODEL}".toByteArray()); return }
                if (path.startsWith("/backup") && exportBackup != null) {
                    send("application/json; charset=utf-8", exportBackup.invoke().toByteArray(Charsets.UTF_8),
                        "Content-Disposition: attachment; filename=\"pintotv-sicherung.json\"\r\n")
                    return
                }
                if (method == "POST" && path.startsWith("/restore") && onRestore != null) {
                    val ok = try { org.json.JSONObject(body).has("pintotv") } catch (e: Exception) { false }
                    if (ok) onRestore.invoke(body, c.inetAddress?.hostAddress ?: "")
                    send("text/plain; charset=utf-8", (if (ok) "OK – bitte am Fernseher bestätigen" else "Keine gültige PintoTV-Sicherung").toByteArray())
                    return
                }

                if (path.startsWith("/person") && onPerson != null) {
                    val html = if (method == "POST") {
                        val f = parseForm(body)
                        if (f["name"].isNullOrBlank() || (f["server"].isNullOrBlank())) page("Fehler", "<p class='err'>Name und Server/Link sind nötig.</p><a class='btn' href='/person'>Zurück</a>")
                        else {
                            onPerson.invoke(f)
                            page("Gespeichert ✓", "<p class='ok'>„${esc(f["name"] ?: "")}“ ist in der Verwaltung gespeichert.</p><p>Schau auf den Fernseher.</p><a class='btn' href='/person'>Weitere Person eintragen</a>")
                        }
                    } else {
                        val id = path.substringAfter("id=", "").substringBefore("&")
                        val d = if (id.isNotBlank()) personData?.invoke(URLDecoder.decode(id, "UTF-8")) else null
                        page("Person eintragen", personForm(d ?: emptyMap()))
                    }
                    send("text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8)); return
                }

                val html = if (method == "POST" && path.startsWith("/add")) {
                    val f = parseForm(body)
                    val type = if (f["type"] == "m3u") "m3u" else "xtream"
                    val ok = if (type == "m3u") !f["url"].isNullOrBlank() else !f["server"].isNullOrBlank() && !f["user"].isNullOrBlank()
                    if (ok) {
                        onPlaylist(
                            Playlist(
                                UUID.randomUUID().toString(),
                                f["name"]?.trim()?.ifBlank { null } ?: if (type == "m3u") "M3U" else "Xtream",
                                type,
                                f["url"]?.trim() ?: "",
                                f["server"]?.trim() ?: "",
                                f["user"]?.trim() ?: "",
                                f["pass"]?.trim() ?: "",
                                f["epg"]?.trim() ?: ""
                            )
                        )
                        page("Gespeichert ✓", "<p class='ok'>Die Playlist wurde an den Fernseher geschickt.</p><p>Schau jetzt auf den Fernseher – die Sender werden geladen.</p><a class='btn' href='/'>Weitere Playlist hinzufügen</a>")
                    } else {
                        page("Fehler", "<p class='err'>Bitte alle Pflichtfelder ausfüllen.</p><a class='btn' href='/'>Zurück</a>")
                    }
                } else page("PintoTV einrichten", FORM)

                send("text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) { }
        }
    }

    private fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Formular für die Verwaltung (Freunde & Familie) */
    private fun personForm(d: Map<String, String>): String {
        fun v(k: String) = esc(d[k] ?: "")
        return """<p class="sub">${if (d.isEmpty()) "Neue Person für die Verwaltung" else "Person bearbeiten"} – wird auf dem Fernseher gespeichert</p>
<form method="post" action="/person">
<input type="hidden" name="id" value="${v("id")}">
<div class="f"><label>Name *</label><input name="name" value="${v("name")}" placeholder="z. B. Paulo"></div>
<div class="f"><label>Server-Adresse oder M3U-Link *</label><input name="server" value="${v("server")}" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://server.de:8080"></div>
<div class="f"><label>Benutzername</label><input name="user" value="${v("user")}" autocapitalize="off" autocorrect="off"></div>
<div class="f"><label>Passwort</label><input name="pass" value="${v("pass")}" autocapitalize="off" autocorrect="off"></div>
<div class="f"><label>Gerätecode (optional)</label><input name="code" value="${v("code")}" autocapitalize="characters" autocorrect="off" placeholder="PT-XXXX-XXXX"></div>
<div class="f"><label>Notiz (optional)</label><input name="note" value="${v("note")}" placeholder="z. B. Ablauf, Gerät"></div>
<button type="submit">Auf dem Fernseher speichern</button>
</form><p class="hint">Handy und Fernseher müssen im selben WLAN sein. Die Länder wählst du danach am Fernseher aus.</p>"""
    }

    private fun parseForm(s: String): Map<String, String> = s.split("&").mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
    }.toMap()

    private fun page(title: String, content: String) = """<!DOCTYPE html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title><style>
body{margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0B0E15;color:#fff}
.wrap{max-width:480px;margin:0 auto;padding:24px 18px 40px}
h1{font-size:26px;margin:4px 0 4px}h1 span{color:#3D7BFF}.sub{color:#9aa3b5;margin:0 0 22px}
.tabs{display:flex;gap:8px;margin-bottom:18px}.tabs label{flex:1;text-align:center;padding:12px;border-radius:10px;background:#1A1F2B;cursor:pointer;font-weight:600}
input[type=radio]{display:none}#t1:checked~.tabs label[for=t1],#t2:checked~.tabs label[for=t2]{background:#3D7BFF}
.f{margin-bottom:14px}.f label{display:block;font-size:14px;color:#9aa3b5;margin-bottom:6px}
.f input{width:100%;box-sizing:border-box;padding:14px;border-radius:10px;border:1px solid #2A3550;background:#121620;color:#fff;font-size:17px}
.x,.m{display:none}#t1:checked~.x{display:block}#t2:checked~.m{display:block}
button,.btn{display:block;width:100%;box-sizing:border-box;text-align:center;padding:16px;border:0;border-radius:12px;background:#3D7BFF;color:#fff;font-size:18px;font-weight:700;text-decoration:none;margin-top:10px}
.ok{color:#5BE08A;font-size:20px;font-weight:700}.err{color:#FF6B6B;font-weight:700}.hint{color:#6f788a;font-size:13px;margin-top:18px}
</style></head><body><div class="wrap"><h1>Pinto<span>TV</span></h1>$content</div></body></html>"""

    companion object {
        private val FORM = """<p class="sub">Playlist vom Handy aus zum Fernseher schicken</p>
<form method="post" action="/add">
<input type="radio" name="type" value="xtream" id="t1" checked><input type="radio" name="type" value="m3u" id="t2">
<div class="tabs"><label for="t1">Xtream-Codes</label><label for="t2">M3U-Link</label></div>
<div class="f"><label>Name (frei wählbar)</label><input name="name" placeholder="z. B. Mein Anbieter"></div>
<div class="x"><div class="f"><label>Server-Adresse *</label><input name="server" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://server.de:8080"></div>
<div class="f"><label>Benutzername *</label><input name="user" autocapitalize="off" autocorrect="off"></div>
<div class="f"><label>Passwort *</label><input name="pass" autocapitalize="off" autocorrect="off"></div></div>
<div class="m"><div class="f"><label>Playlist-Adresse (M3U) *</label><input name="url" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://…/playlist.m3u"></div></div>
<div class="f"><label>EPG-Adresse (optional)</label><input name="epg" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="bei Xtream automatisch"></div>
<button type="submit">An den Fernseher senden</button>
</form><h2 style="margin-top:34px;font-size:20px">Sicherung</h2>
<p class="sub">Alle Playlists, Favoriten und Einstellungen auf dem Handy sichern – oder eine Sicherung auf diesen Fernseher laden.</p>
<a class="btn" href="/backup" download="pintotv-sicherung.json">⬇ Sicherung aufs Handy laden</a>
<a class="btn" href="/person" style="background:#222630;border:1px solid #3D7BFF">👥 Person für die Verwaltung eintragen</a>
<div class="f" style="margin-top:18px"><label>Sicherung auf diesen Fernseher übertragen</label><input type="file" id="bf" accept=".json,application/json"></div>
<button type="button" onclick="up()">⬆ Sicherung an Fernseher senden</button>
<p id="st" class="hint"></p>
<script>
function up(){var f=document.getElementById('bf').files[0];var st=document.getElementById('st');
if(!f){st.textContent='Bitte zuerst die Sicherungsdatei auswählen.';return;}
f.text().then(function(t){return fetch('/restore',{method:'POST',body:t});}).then(function(r){return r.text();})
.then(function(t){st.textContent=t;}).catch(function(e){st.textContent='Fehler: '+e;});}
</script>
<p class="hint">Handy und Fernseher müssen im selben WLAN sein.</p>"""

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        } catch (e: Exception) { null }
    }
}
