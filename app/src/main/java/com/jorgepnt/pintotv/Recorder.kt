package com.jorgepnt.pintotv

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aufnahme: schreibt mit, was der Player ohnehin empfängt (keine zweite Verbindung zum Anbieter).
 * Bei HLS werden nur die Video-Segmente gespeichert, nicht die Playlisten.
 */
object Recorder {
    @Volatile var active = false; private set
    @Volatile var file: File? = null; private set
    @Volatile var channelUrl: String? = null; private set
    @Volatile var channelName: String = ""; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var bytes = 0L; private set
    private var out: FileOutputStream? = null
    private val lock = Any()

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "Aufnahmen").apply { mkdirs() }

    fun freeBytes(ctx: Context): Long = try { StatFs(dir(ctx).path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }

    fun start(ctx: Context, c: Channel): File {
        stop()
        val safe = c.name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(40).ifBlank { "Aufnahme" }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date())
        val f = File(dir(ctx), "${safe}_$stamp.ts")
        synchronized(lock) {
            out = FileOutputStream(f, true)
            file = f; channelUrl = c.url; channelName = c.name
            startedAt = System.currentTimeMillis(); bytes = 0
            active = true
        }
        return f
    }

    /** beendet die Aufnahme; gibt die Datei zurück (oder null, wenn leer) */
    fun stop(): File? = synchronized(lock) {
        if (!active) return null
        active = false
        try { out?.close() } catch (e: Exception) { }
        out = null
        val f = file
        if (f != null && f.length() < 50_000) { f.delete(); null } else f
    }

    private fun write(b: ByteArray, off: Int, len: Int) = synchronized(lock) {
        if (!active) return
        try { out?.write(b, off, len); bytes += len } catch (e: Exception) { }
    }

    /** neuer „Abzweig“ für jede Datenquelle des Players */
    fun sink(): DataSink = object : DataSink {
        private var record = false
        override fun open(dataSpec: DataSpec) {
            val path = dataSpec.uri.path?.lowercase() ?: ""
            // Playlisten (.m3u8) nicht mitschreiben, nur Videodaten
            record = active && !path.endsWith(".m3u8") && !path.endsWith(".m3u")
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { if (record) this@Recorder.write(buffer, offset, length) }
        override fun close() { record = false }
    }

    fun elapsed(): String {
        val s = (System.currentTimeMillis() - startedAt) / 1000
        return if (s >= 3600) String.format(Locale.GERMANY, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.GERMANY, "%02d:%02d", s / 60, s % 60)
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.extension == "ts" && it != file }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
