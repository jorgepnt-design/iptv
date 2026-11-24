package com.jorgepnt.pintotv

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wöchentliche Sicherung aller Einstellungen als Datei (die letzten 6 bleiben erhalten) */
object AutoBackup {
    private const val WEEK = 7L * 24 * 3600_000

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir, "Sicherungen").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** im Hintergrund aufrufen; sichert, wenn die letzte Sicherung älter als eine Woche ist */
    fun runIfDue(ctx: Context, force: Boolean = false): File? {
        val store = Store(ctx)
        if (store.playlists().isEmpty()) return null
        val last = list(ctx).firstOrNull()
        if (!force && last != null && System.currentTimeMillis() - last.lastModified() < WEEK) return null
        val f = File(dir(ctx), "pintotv-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date()) + ".json")
        f.writeText(store.exportJson())
        list(ctx).drop(6).forEach { it.delete() }
        return f
    }

    fun label(f: File): String {
        val d = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(f.lastModified()))
        return d
    }
}
