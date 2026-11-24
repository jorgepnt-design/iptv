package com.jorgepnt.pintotv

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ServerTest {
    @Test fun phoneForm() {
        var got: Playlist? = null
        val s = PhoneSetupServer { got = it }
        assertTrue(s.start())
        try {
            val get = URL("http://127.0.0.1:${s.port}/").openConnection() as HttpURLConnection
            val page = get.inputStream.bufferedReader().readText()
            assertEquals(200, get.responseCode)
            assertTrue(page.contains("An den Fernseher senden"))

            val post = URL("http://127.0.0.1:${s.port}/add").openConnection() as HttpURLConnection
            post.requestMethod = "POST"; post.doOutput = true
            post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            post.outputStream.write("type=xtream&name=Mein+Anbieter&server=http%3A%2F%2Fsrv.de%3A8080&user=jorge&pass=p%C3%A4ss&url=&epg=".toByteArray())
            val res = post.inputStream.bufferedReader().readText()
            assertTrue(res.contains("Gespeichert"))
            Thread.sleep(200)
            val p = got!!
            assertEquals("Mein Anbieter", p.name)
            assertEquals("xtream", p.type)
            assertEquals("http://srv.de:8080", p.server)
            assertEquals("jorge", p.user)
            assertEquals("päss", p.pass)
        } finally { s.stop() }
    }
}
