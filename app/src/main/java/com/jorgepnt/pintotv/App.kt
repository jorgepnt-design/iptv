package com.jorgepnt.pintotv

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Merkt sich Abstürze, damit sie beim nächsten Start angezeigt werden können */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("${appVersion(this)} · Thread ${t.name}\n$sw")
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        startWatchdog()
    }

    /** Wächter: merkt, wenn die Bedienung (Hauptablauf) länger als 2 s hängt, und speichert die Stelle */
    private fun startWatchdog() {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            var lastSaved = 0L
            while (true) {
                val tick = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post { tick.set(true) }
                Thread.sleep(2000)
                if (!tick.get()) {
                    val now = System.currentTimeMillis()
                    if (now - lastSaved > 20_000) {
                        lastSaved = now
                        try {
                            val st = android.os.Looper.getMainLooper().thread.stackTrace
                            val lines = st.map { it.toString() }
                            val mine = lines.filter { it.contains("pintotv") }.take(8)
                            val top = lines.take(10)
                            // Hänger nur im Android-System (z. B. Bildschirm geht aus, Grafik stoppt) sind kein App-Fehler – nicht melden
                            if (mine.isEmpty()) throw IllegalStateException("system")
                            val txt = "${appVersion(this)} · Bedienung hing > 2 s\n" +
                                (if (mine.isNotEmpty()) mine.joinToString("\n") + "\n—\n" else "") + top.joinToString("\n")
                            java.io.File(filesDir, "hang.txt").writeText(txt)
                        } catch (_: Throwable) { }
                    }
                    // warten bis der Hauptablauf wieder reagiert
                    while (!tick.get()) Thread.sleep(200)
                }
                Thread.sleep(500)
            }
        }, "PintoTV-Waechter").apply { isDaemon = true; start() }
    }

    companion object {
        /** warum wurde die App zuletzt beendet? (Android 11+, erfasst auch Speichermangel, „reagiert nicht“ und Decoder-Abstürze) */
        fun lastExitProblem(ctx: android.content.Context): String? {
            if (android.os.Build.VERSION.SDK_INT < 30) return null
            return try {
                val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
                val sp = ctx.getSharedPreferences("pintotv", android.content.Context.MODE_PRIVATE)
                if (info.timestamp <= sp.getLong("exitSeen", 0)) return null
                sp.edit().putLong("exitSeen", info.timestamp).apply()
                val why = when (info.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "Absturz (Java)"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz im System/Decoder (nativ)"
                    android.app.ApplicationExitInfo.REASON_ANR -> "App reagierte nicht (ANR)"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Speicher voll (vom System beendet)"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Zu hoher Verbrauch (vom System beendet)"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "Vom System beendet (Signal ${info.status})"
                    else -> return null // normales Beenden
                }
                val time = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date(info.timestamp))
                var text = "$why\n$time · Speicher ${info.pss / 1024} MB (RSS ${info.rss / 1024} MB)\n${info.description ?: ""}"
                try {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        val lines = r.readLines()
                        // 1) Grund laut Android („Subject: Input dispatching timed out …“)
                        val subject = lines.filter { it.startsWith("Subject") || it.contains("Reason") }.take(2)
                        // 2) alle Abläufe, die in PintoTV oder im Player stecken – mit Name des Ablaufs
                        val interesting = ArrayList<String>()
                        var thread = ""
                        var shownFor = ""
                        for (l in lines) {
                            if (l.startsWith("\"")) thread = l.substringBefore(" prio").take(40)
                            val t = l.trim()
                            if (t.startsWith("at ") && (t.contains("pintotv") || t.contains("media3") || t.contains("exoplayer") ||
                                        t.contains("FileOutputStream") || t.contains("MediaCodec") || t.contains("Surface"))) {
                                if (shownFor != thread) { interesting.add("▶ $thread"); shownFor = thread }
                                if (interesting.size < 22) interesting.add("  " + t.removePrefix("at ").take(90))
                            }
                        }
                        // 3) Hauptablauf (Java-Teil)
                        val main = lines.dropWhile { !it.startsWith("\"main\"") }.filter { it.trim().startsWith("at ") }.take(6).map { "  " + it.trim().take(90) }
                        text = subject.joinToString("\n") + "\n" + text + "\n\n" +
                            (if (interesting.isNotEmpty()) interesting.joinToString("\n") else "(keine PintoTV-/Player-Abläufe aktiv)") +
                            "\n\nmain:\n" + main.joinToString("\n")
                    }
                } catch (_: Throwable) { }
                text.trim()
            } catch (e: Throwable) { null }
        }

        fun takeHang(app: android.content.Context): String? {
            val f = File(app.filesDir, "hang.txt")
            if (!f.exists()) return null
            val s = f.readText(); f.delete(); return s
        }

        fun takeCrash(app: android.content.Context): String? {
            val f = File(app.filesDir, "crash.txt")
            if (!f.exists()) return null
            val s = f.readText(); f.delete()
            // nur die wichtigen Zeilen (eigener Code zuerst)
            val lines = s.lines()
            val head = lines.take(3)
            val mine = lines.filter { it.contains("pintotv") }.take(8)
            val cause = lines.filter { it.startsWith("Caused by") }.take(2)
            return (head + cause + mine).distinct().joinToString("\n")
        }
    }
}
