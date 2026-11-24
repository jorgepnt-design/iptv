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
                        val main = lines.dropWhile { !it.startsWith("\"main\"") }.take(14)
                        val mine = lines.filter { it.contains("pintotv") }.take(6)
                        text += "\n" + (if (main.isNotEmpty()) main else lines.take(14)).joinToString("\n") + "\n" + mine.joinToString("\n")
                    }
                } catch (_: Throwable) { }
                text.trim()
            } catch (e: Throwable) { null }
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
