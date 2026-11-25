package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID

/**
 * Fern-Einrichtung: Jedes Gerät hat einen eigenen Gerätecode (PT-XXXX-XXXX).
 * Auf pintotv-setup.vercel.app (oder aus einer anderen PintoTV-App) kann man für diesen Code
 * eine Playlist oder eine komplette Sicherung hinterlegen; das Gerät holt sie sich ab.
 */
object Remote {
    const val BASE = "https://pintotv-setup.vercel.app"
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    class Item(val id: String, val kind: String, val payload: JSONObject)

    /** eigener Speicher, damit eine eingespielte Sicherung den Code nicht überschreibt */
    fun deviceCode(ctx: Context): String {
        val sp = ctx.getSharedPreferences("pintotv_device", Context.MODE_PRIVATE)
        sp.getString("code", null)?.let { return it }
        val r = SecureRandom()
        val raw = (1..8).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        val code = "PT-${raw.substring(0, 4)}-${raw.substring(4)}"
        sp.edit().putString("code", code).commit()
        return code
    }

    fun setupUrl(ctx: Context) = "$BASE/?code=${deviceCode(ctx)}"

    /** Eingabe wie „pt7k3f92xa“ oder „7K3F 92XA“ in PT-7K3F-92XA umwandeln */
    fun normalize(input: String): String? {
        var s = input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
        if (s.startsWith("PT") && s.length == 10) s = s.substring(2)
        if (s.length != 8 || s.any { it !in ALPHABET }) return null
        return "PT-${s.substring(0, 4)}-${s.substring(4)}"
    }

    private fun call(method: String, path: String, body: String? = null): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "PintoTV")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
            throw java.io.IOException(msg.ifBlank { "Server-Fehler $code" })
        }
        return text
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    /** wartende Einrichtungen für dieses Gerät (nur im Hintergrund aufrufen) */
    fun fetch(ctx: Context): List<Item> {
        val a = JSONArray(call("GET", "/api/setup?code=${q(deviceCode(ctx))}"))
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { Item(it.getString("id"), it.getString("kind"), it.getJSONObject("payload")) } }
    }

    fun clear(ctx: Context, upto: String) {
        call("DELETE", "/api/setup?code=${q(deviceCode(ctx))}&upto=${q(upto)}")
    }

    /** eigene komplette Einrichtung an ein anderes Gerät schicken */
    fun sendBackup(code: String, exportJson: String) {
        call("POST", "/api/setup", JSONObject().put("code", code).put("kind", "backup").put("payload", JSONObject(exportJson)).toString())
    }

    fun toPlaylist(p: JSONObject): Playlist {
        val type = if (p.optString("type") == "m3u") "m3u" else "xtream"
        return Playlist(
            UUID.randomUUID().toString(),
            p.optString("name").trim().ifBlank { if (type == "m3u") "M3U" else "Xtream" },
            type, p.optString("url").trim(), p.optString("server").trim(),
            p.optString("user").trim(), p.optString("pass").trim(), p.optString("epg").trim()
        )
    }

    /**
     * Gefundene Einrichtungen anzeigen und nach Rückfrage übernehmen.
     * Playlists werden einzeln hinzugefügt; eine Sicherung ersetzt alles.
     */
    fun offer(
        a: Activity, items: List<Item>,
        onPlaylists: (List<Playlist>) -> Unit,
        onBackup: (String) -> Unit
    ) {
        if (items.isEmpty() || a.isFinishing) return
        val store = Store(a)
        val backup = items.lastOrNull { it.kind == "backup" }
        if (backup != null) {
            val json = backup.payload.toString()
            AlertDialog.Builder(a)
                .setTitle("🌐 Fern-Einrichtung empfangen")
                .setMessage("Für dieses Gerät wurde eine komplette Einrichtung geschickt:\n${store.describe(json)}" +
                    (backup.payload.optString("device").takeIf { it.isNotBlank() }?.let { "\n(von $it)" } ?: "") +
                    "\n\nÜbernehmen? Die Einstellungen auf diesem Fernseher werden dabei ersetzt.")
                .setPositiveButton("Übernehmen") { _, _ -> onBackup(json) }
                .setNegativeButton("Verwerfen", null)
                .show()
            return
        }
        val pls = items.filter { it.kind == "playlist" }.map { toPlaylist(it.payload) }
        if (pls.isEmpty()) return
        AlertDialog.Builder(a)
            .setTitle("🌐 Fern-Einrichtung empfangen")
            .setMessage("Neue Playlist${if (pls.size > 1) "s" else ""} für dieses Gerät:\n\n" +
                pls.joinToString("\n") { "• ${it.name} (${if (it.type == "xtream") "Xtream" else "M3U"})" } + "\n\nHinzufügen?")
            .setPositiveButton("Hinzufügen") { _, _ -> onPlaylists(pls) }
            .setNegativeButton("Verwerfen", null)
            .show()
    }
}
