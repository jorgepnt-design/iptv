package com.jorgepnt.pintotv

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Aufnahme: schreibt mit, was der Player ohnehin empfängt (keine zweite Verbindung zum Anbieter).
 * Alle Speicherzugriffe laufen in einem eigenen Hintergrund-Thread – weder Bedienung noch Wiedergabe
 * warten jemals auf den Speicher (wichtig bei langsamen USB-Sticks/Festplatten).
 */
object Recorder {
    @Volatile var active = false; private set
    @Volatile var file: File? = null; private set
    @Volatile var channelUrl: String? = null; private set
    @Volatile var channelName: String = ""; private set
    @Volatile var startedAt = 0L; private set
    private val written = AtomicLong(0)
    private val dropped = AtomicLong(0)
    val bytes: Long get() = written.get()
    val droppedBytes: Long get() = dropped.get()
    /** zuletzt gemessener freier Speicher (wird im Hintergrund aktualisiert) */
    @Volatile var lastFree: Long = Long.MAX_VALUE; private set

    private val main = Handler(Looper.getMainLooper())
    private val STOP = ByteArray(0)
    private const val MAX_QUEUE_BYTES = 96L * 1024 * 1024
    private val queued = AtomicLong(0)
    @Volatile private var queue: LinkedBlockingQueue<ByteArray>? = null
    @Volatile private var doneCallback: ((File?) -> Unit)? = null

    class Place(val dir: File, val label: String)

    /** alle möglichen Speicherorte: interner Speicher + USB-Sticks/Festplatten/SD-Karten */
    fun places(ctx: Context): List<Place> {
        val out = ArrayList<Place>()
        val dirs = ctx.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
        dirs.forEachIndexed { i, d ->
            val removable = try { Environment.isExternalStorageRemovable(d) } catch (e: Throwable) { i > 0 }
            val label = if (!removable && i == 0) "Interner Speicher" else "USB / SD-Karte ${if (dirs.size > 2) i else ""}".trim()
            out.add(Place(File(d, "Aufnahmen"), label))
        }
        if (out.isEmpty()) out.add(Place(File(ctx.filesDir, "Aufnahmen"), "Interner Speicher"))
        return out
    }

    /** Achtung: greift auf den Speicher zu – nicht im Hauptthread in kurzen Abständen aufrufen */
    fun dir(ctx: Context): File {
        val chosen = ctx.getSharedPreferences("pintotv", Context.MODE_PRIVATE).getString("recDir", null)?.let { File(it) }
        val d = chosen?.takeIf { (it.exists() || it.mkdirs()) && it.canWrite() } ?: places(ctx).first().dir
        d.mkdirs()
        return d
    }

    fun setDir(ctx: Context, d: File) {
        ctx.getSharedPreferences("pintotv", Context.MODE_PRIVATE).edit().putString("recDir", d.absolutePath).apply()
    }

    fun free(d: File): Long = try { d.mkdirs(); StatFs(d.path).availableBytes } catch (e: Throwable) { 0 }

    fun freeBytes(ctx: Context): Long = try { StatFs(dir(ctx).path).availableBytes } catch (e: Throwable) { Long.MAX_VALUE }

    /** Startet die Aufnahme. Datei öffnen & schreiben passiert im Hintergrund; onError kommt im Hauptthread. */
    fun start(ctx: Context, c: Channel, onError: (String) -> Unit) {
        stop(null)
        val app = ctx.applicationContext
        val safe = c.name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(40).ifBlank { "Aufnahme" }
        val name = "${safe}_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date()) + ".ts"
        val q = LinkedBlockingQueue<ByteArray>()
        queue = q; queued.set(0); written.set(0); dropped.set(0)
        channelUrl = c.url; channelName = c.name
        startedAt = System.currentTimeMillis()
        lastFree = Long.MAX_VALUE
        active = true
        Thread({ writerLoop(app, name, q, onError) }, "PintoTV-Aufnahme").apply { isDaemon = true; start() }
    }

    private fun writerLoop(ctx: Context, name: String, q: LinkedBlockingQueue<ByteArray>, onError: (String) -> Unit) {
        var out: OutputStream? = null
        var f: File? = null
        try {
            var dir = dir(ctx)
            if (!dir.canWrite()) dir = places(ctx).first().dir.apply { mkdirs() }
            f = File(dir, name)
            out = BufferedOutputStream(FileOutputStream(f, true), 1024 * 1024)
            file = f
            lastFree = free(dir)
            var lastCheck = System.currentTimeMillis()
            while (true) {
                val chunk = q.poll(1, TimeUnit.SECONDS)
                if (chunk === STOP) break
                if (chunk != null) {
                    queued.addAndGet(-chunk.size.toLong())
                    out.write(chunk)
                    written.addAndGet(chunk.size.toLong())
                }
                val now = System.currentTimeMillis()
                if (now - lastCheck > 15_000) { lastCheck = now; lastFree = free(dir) }
            }
        } catch (e: Throwable) {
            active = false
            main.post { onError(e.message ?: e.javaClass.simpleName) }
        } finally {
            try { out?.flush(); out?.close() } catch (_: Throwable) { }
            if (f != null && f.length() < 50_000) f.delete() // keine Daten angekommen
            val cb = doneCallback
            doneCallback = null
            if (cb != null) { val ff = f?.takeIf { it.exists() }; main.post { cb(ff) } }
        }
    }

    /** beendet die Aufnahme sofort (blockiert nicht); onDone kommt im Hauptthread, sobald die Datei fertig ist */
    fun stop(onDone: ((File?) -> Unit)?) {
        val q = queue
        if (q == null) { active = false; onDone?.invoke(null); return }
        active = false
        doneCallback = onDone
        queue = null
        q.offer(STOP)
    }

    /** Daten direkt einspeisen (vom Timeshift-Puffer) */
    fun feed(b: ByteArray, off: Int, len: Int) { if (active) enqueue(b, off, len) }

    private fun enqueue(b: ByteArray, off: Int, len: Int) {
        val q = queue ?: return
        if (!active || len <= 0) return
        // ist der Speicher zu langsam, lieber Daten verwerfen als Bild/Bedienung anzuhalten
        if (queued.get() + len > MAX_QUEUE_BYTES) { dropped.addAndGet(len.toLong()); return }
        queued.addAndGet(len.toLong())
        q.offer(b.copyOfRange(off, off + len))
    }

    /** neuer „Abzweig" für jede Datenquelle des Players – schreibt nur echte Videodaten (MPEG-TS) */
    fun sink(): DataSink = object : DataSink {
        private var state = 0 // 0 = noch nicht entschieden, 1 = Video → schreiben, 2 = kein Video → verwerfen
        override fun open(dataSpec: DataSpec) { state = if (active) 0 else 2 }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            if (state == 0) {
                // am Inhalt erkennen: MPEG-TS beginnt mit dem Sync-Byte 0x47 – Stream-Listen (#EXTM3U) sind Text
                state = if (buffer[offset] == 0x47.toByte()) 1 else 2
            }
            if (state == 1) enqueue(buffer, offset, length)
        }
        override fun close() { state = 2 }
    }

    /**
     * Repariert eine Aufnahme: behält nur gültige 188-Byte-TS-Pakete (entfernt z. B. eingestreute Stream-Listen).
     * Gibt die reparierte Datei zurück oder die Originaldatei, wenn sie schon sauber war.
     */
    fun repairIfNeeded(f: File): File {
        val head = ByteArray(188 * 3)
        val n = f.inputStream().use { it.read(head) }
        val clean = n >= 377 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte() && head[376] == 0x47.toByte()
        if (clean) return f
        val out = File(f.parentFile, f.nameWithoutExtension + "_repariert.ts")
        if (out.exists() && out.length() > 0) return out
        java.io.BufferedInputStream(f.inputStream(), 1 shl 20).use { inp ->
            BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { o ->
                val buf = ByteArray(1 shl 20)
                var carry = ByteArray(0)
                var inRun = false
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    val data = carry + buf.copyOf(r)
                    var i = 0
                    var lastEnd = if (inRun) 0 else -1
                    while (i + 188 <= data.size) {
                        // gültig: beginnt mit 0x47 und das nächste Paket auch – oder schließt direkt an ein gültiges an
                        if (data[i] == 0x47.toByte() &&
                            (i + 188 >= data.size || data[i + 188] == 0x47.toByte() || lastEnd == i)) {
                            o.write(data, i, 188); i += 188; lastEnd = i
                        } else i++
                    }
                    inRun = lastEnd == i
                    carry = data.copyOfRange(i, data.size)
                }
            }
        }
        if (out.length() < 50_000) { out.delete(); return f }
        return out
    }

    fun elapsed(): String {
        val s = (System.currentTimeMillis() - startedAt) / 1000
        return if (s >= 3600) String.format(Locale.GERMANY, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.GERMANY, "%02d:%02d", s / 60, s % 60)
    }

    /** Aufnahmen von allen Speicherorten */
    fun list(ctx: Context): List<File> =
        places(ctx).flatMap { p -> p.dir.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.extension == "ts" && !(active && it == file) && !it.nameWithoutExtension.endsWith("_repariert") }
            .sortedByDescending { it.lastModified() }
}
