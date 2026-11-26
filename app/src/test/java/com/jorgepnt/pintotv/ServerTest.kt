package com.jorgepnt.pintotv

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ServerTest {
    @Test fun phoneForm() {
        var got: Playlist? = null
        val s = PhoneSetupServer(onPlaylist = { got = it })
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

    @Test fun backupRoundTrip() {
        var restored: String? = null
        val backup = """{"pintotv":1,"prefs":{"playlists":{"t":"s","v":"[]"},"favlists":{"t":"s","v":"[{\"n\":\"Sport – Ü\",\"k\":[\"dazn 1 hd\"]}]"}}}"""
        val s = PhoneSetupServer(onPlaylist = {}, exportBackup = { backup }, onRestore = { j, _ -> restored = j })
        assertTrue(s.start())
        try {
            val ping = URL("http://127.0.0.1:${s.port}/ping").openStream().bufferedReader().readText()
            assertTrue(ping.startsWith("PintoTV:"))
            val dl = URL("http://127.0.0.1:${s.port}/backup").openStream().bufferedReader().readText()
            assertEquals(backup, dl)
            val post = URL("http://127.0.0.1:${s.port}/restore").openConnection() as HttpURLConnection
            post.requestMethod = "POST"; post.doOutput = true
            post.outputStream.write(dl.toByteArray(Charsets.UTF_8))
            assertTrue(post.inputStream.bufferedReader().readText().startsWith("OK"))
            Thread.sleep(200)
            assertEquals(backup, restored) // Umlaute/Mehrbyte-Zeichen vollständig angekommen
        } finally { s.stop() }
    }

    @Test fun personForm() {
        var got: Map<String, String>? = null
        val s = PhoneSetupServer(onPlaylist = {}, onPerson = { got = it },
            personData = { id -> if (id == "abc") mapOf("id" to "abc", "name" to "Paulo", "server" to "http://x.de") else null })
        assertTrue(s.start())
        try {
            val get = URL("http://127.0.0.1:${s.port}/person?id=abc").openConnection() as HttpURLConnection
            val page = get.inputStream.bufferedReader().readText()
            assertTrue(page.contains("value=\"Paulo\""))
            val post = URL("http://127.0.0.1:${s.port}/person").openConnection() as HttpURLConnection
            post.requestMethod = "POST"; post.doOutput = true
            post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            post.outputStream.write("id=&name=Ana&server=http%3A%2F%2Fsrv.de&user=ana1&pass=x&code=pt7k3f92xa&note=".toByteArray())
            assertTrue(post.inputStream.bufferedReader().readText().contains("Gespeichert"))
            Thread.sleep(200)
            assertEquals("Ana", got!!["name"]); assertEquals("ana1", got!!["user"])
        } finally { s.stop() }
    }
}
