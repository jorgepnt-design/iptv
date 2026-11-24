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
