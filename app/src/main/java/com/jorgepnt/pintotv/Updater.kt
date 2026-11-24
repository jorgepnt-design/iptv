package com.jorgepnt.pintotv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Updates direkt in der App: prüft GitHub, lädt die neue APK und startet die Installation */
object Updater {
    private const val VERSION_URL = "https://github.com/jorgepnt-design/iptv/raw/main/version.json"

    class Info(val code: Int, val name: String, val url: String, val notes: String)

    private fun currentCode(a: Activity): Long = try {
        val pi = a.packageManager.getPackageInfo(a.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Exception) { 0 }

    /** quiet = beim Start: nur melden, wenn es etwas Neues gibt */
    fun check(a: Activity, scope: CoroutineScope, quiet: Boolean) {
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    val o = JSONObject(Http.text("$VERSION_URL?t=${System.currentTimeMillis()}"))
                    Info(o.getInt("versionCode"), o.getString("versionName"), o.getString("url"), o.optString("notes"))
                } catch (e: Exception) { null }
            }
            if (a.isFinishing) return@launch
            if (info == null) { if (!quiet) toast(a, "Update-Server nicht erreichbar"); return@launch }
            if (info.code <= currentCode(a)) { if (!quiet) toast(a, "PintoTV ist aktuell (${appVersion(a)})"); return@launch }
            AlertDialog.Builder(a)
                .setTitle("Update verfügbar: PintoTV v${info.name}")
                .setMessage("Installiert: ${appVersion(a)}\n\n${info.notes}".trim())
                .setPositiveButton("Jetzt installieren") { _, _ -> download(a, scope, info) }
                .setNegativeButton("Später", null)
                .show()
        }
    }

    private fun download(a: Activity, scope: CoroutineScope, info: Info) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("Erlaubnis nötig")
                .setMessage("Damit PintoTV sich selbst aktualisieren kann, im nächsten Fenster „Apps aus dieser Quelle zulassen“ einschalten. Danach zurück und nochmal „Nach Updates suchen“.")
                .setPositiveButton("Einstellungen öffnen") { _, _ ->
                    try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))) }
                    catch (e: Exception) { toast(a, "Bitte in den Einstellungen bei PintoTV „Unbekannte Apps installieren“ erlauben") }
                }
                .setNegativeButton("Abbrechen", null).show()
            return
        }
        val dlg = AlertDialog.Builder(a).setTitle("Update wird geladen").setMessage("Starte …").setCancelable(false).create()
        dlg.show()
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    val dir = File(a.cacheDir, "updates").apply { mkdirs() }
                    val f = File(dir, "pintotv-update.apk")
                    Http.open(info.url) { done, total ->
                        a.runOnUiThread {
                            dlg.setMessage(if (total > 0) "${mb(done)} von ${mb(total)}  ·  ${done * 100 / total} %" else mb(done))
                        }
                    }.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    f
                } catch (e: Exception) { null }
            }
            dlg.dismiss()
            if (file == null || file.length() < 100_000) { toast(a, "Download fehlgeschlagen"); return@launch }
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.updates", file)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try { a.startActivity(i) } catch (e: Exception) { toast(a, "Installation konnte nicht gestartet werden: ${e.message}") }
        }
    }

    private fun toast(a: Activity, t: String) = Toast.makeText(a, t, Toast.LENGTH_LONG).show()
}
