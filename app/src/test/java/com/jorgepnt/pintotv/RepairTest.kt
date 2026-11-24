package com.jorgepnt.pintotv

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RepairTest {
    private fun pkt(n: Int) = ByteArray(188) { if (it == 0) 0x47 else (n % 100).toByte() }

    @Test fun removesPlaylistText() {
        val dir = createTempDir()
        val f = File(dir, "a.ts")
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6.0,\nseg1.ts\n".toByteArray()
        f.outputStream().use { o ->
            o.write(playlist)
            repeat(400) { o.write(pkt(it)) }
            o.write(playlist)
            repeat(400) { o.write(pkt(it)) }
        }
        val r = Recorder.repairIfNeeded(f)
        assertNotEquals(f, r)
        assertEquals(800L * 188, r.length())
        val b = r.readBytes()
        for (i in 0 until 800) assertEquals(0x47.toByte(), b[i * 188])
    }

    @Test fun cleanFileUntouched() {
        val dir = createTempDir()
        val f = File(dir, "b.ts")
        f.outputStream().use { o -> repeat(400) { o.write(pkt(it)) } }
        assertEquals(f, Recorder.repairIfNeeded(f))
    }
}
