package com.jorgepnt.pintotv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Timeshift: lädt den laufenden Sender über EINE Verbindung in eine Pufferdatei.
 * Der Player spielt dann aus dieser Datei – pausieren, zurück- und vorspulen bis zum Live-Punkt.
 */
object Timeshift {
    @Volatile var active = false; private set
    @Volatile var file: File? = null; private set
    @Volatile var startedAt = 0L; private set
    @Volatile var error: String? = null; private set
    private val written = AtomicLong(0)
    val bytes: Long get() = written.get()
    /** (Millisekunden seit Start, Byte-Position) – jede halbe Sekunde ein Eintrag */
    private val index = ArrayList<LongArray>()
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var generation = 0

    fun bufferedMs(): Long = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    fun start(ctx: Context, url: String, ua: String) {
        stop()
        val f = File(ctx.cacheDir, "timeshift.ts")
        f.delete()
        file = f
        written.set(0)
        synchronized(index) { index.clear() }
        error = null
        startedAt = System.currentTimeMillis()
        active = true
        val gen = ++generation
        Thread({ loop(f, url, ua, gen) }, "PintoTV-Timeshift").apply { isDaemon = true; start() }
    }

    private fun loop(f: File, url: String, ua: String, gen: Int) {
        FileOutputStream(f, true).use { out ->
            val buf = ByteArray(64 * 1024)
            var lastIndex = 0L
            var fails = 0
            while (active && gen == generation) {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 15000
                    c.setRequestProperty("User-Agent", ua)
                    c.instanceFollowRedirects = true
                    conn = c
                    val code = c.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    c.inputStream.use { inp ->
                        while (active && gen == generation) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            out.write(buf, 0, n)
                            written.addAndGet(n.toLong())
                            Recorder.feed(buf, 0, n) // laufende Aufnahme bekommt die Daten ebenfalls
                            val now = System.currentTimeMillis()
                            if (now - lastIndex >= 500) {
                                lastIndex = now
                                synchronized(index) { index.add(longArrayOf(now - startedAt, written.get())) }
                            }
                            fails = 0
                        }
                    }
                } catch (e: Throwable) {
                    if (!active || gen != generation) break
                    error = e.message
                    if (++fails > 20) { active = false; break }
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { }
                } finally {
                    try { conn?.disconnect() } catch (_: Throwable) { }
                }
            }
        }
    }

    fun stop() {
        active = false
        generation++
        try { conn?.disconnect() } catch (_: Throwable) { }
        conn = null
        startedAt = 0
        file?.delete()
    }

    /** Byte-Position zu einer Zeit (ms seit Start) */
    fun offsetAt(ms: Long): Long = synchronized(index) {
        if (index.isEmpty() || ms <= 0) return 0
        var lo = 0; var hi = index.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (index[mid][0] <= ms) lo = mid else hi = mid - 1 }
        // auf ein TS-Paket (188 Byte) ausrichten
        (index[lo][1] / 188) * 188
    }

    /** Zeit (ms seit Start) zu einer Byte-Position */
    fun timeAt(offset: Long): Long = synchronized(index) {
        if (index.isEmpty()) return 0
        var lo = 0; var hi = index.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (index[mid][1] <= offset) lo = mid else hi = mid - 1 }
        index[lo][0]
    }
}

/** liest die wachsende Pufferdatei; wartet auf neue Daten statt aufzuhören */
class TimeshiftDataSource : BaseDataSource(true) {
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val base = dataSpec.uri.getQueryParameter("o")?.toLongOrNull() ?: 0
        val f = Timeshift.file ?: throw IOException("Kein Timeshift-Puffer")
        transferInitializing(dataSpec)
        // kurz warten, bis die Datei existiert
        var waited = 0
        while (!f.exists() && waited < 8000) { Thread.sleep(50); waited += 50 }
        raf = RandomAccessFile(f, "r").apply { seek(base + dataSpec.position) }
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val r = raf ?: return C.RESULT_END_OF_INPUT
        var waited = 0
        while (true) {
            val avail = Timeshift.bytes - r.filePointer
            if (avail > 0) {
                val n = r.read(buffer, offset, minOf(length.toLong(), avail).toInt())
                if (n > 0) { bytesTransferred(n); return n }
            }
            if (!Timeshift.active) return C.RESULT_END_OF_INPUT
            if (waited > 20000) throw IOException("Timeshift: keine neuen Daten")
            Thread.sleep(40); waited += 40
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { raf?.close() } catch (_: Throwable) { }
        raf = null
        if (uri != null) { uri = null; transferEnded() }
    }
}
