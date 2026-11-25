package com.jorgepnt.pintotv

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class ParserTest {
    @Test fun m3u() {
        val txt = """#EXTM3U x-tvg-url="http://epg.example/guide.xml.gz"
#EXTINF:-1 tvg-id="DasErste.de" tvg-name="Das Erste" tvg-logo="http://l/1.png" group-title="Deutschland, HD",DE: Das Erste HD
http://srv/live/1.ts
#EXTINF:-1 tvg-chno="7" group-title="Sport",Sky Sport, Bundesliga
#EXTVLCOPT:http-user-agent=x
http://srv/live/2.m3u8

#EXTINF:0,Ohne Attribute
http://srv/3
"""
        val r = M3uParser.parse(txt.byteInputStream())
        assertEquals("http://epg.example/guide.xml.gz", r.epgUrl)
        assertEquals(3, r.channels.size)
        val a = r.channels[0]
        assertEquals("DE: Das Erste HD", a.name)
        assertEquals("Deutschland, HD", a.group)
        assertEquals("DasErste.de", a.epgId)
        assertEquals(1, a.number)
        assertEquals("Sky Sport, Bundesliga", r.channels[1].name)
        assertEquals(7, r.channels[1].number)
        assertEquals("http://srv/live/2.m3u8", r.channels[1].url)
        assertEquals("Ohne Gruppe", r.channels[2].group)
    }

    @Test fun time() {
        val t = XmlTv.parseTime("20261124203000 +0100")
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("2026-11-24 19:30", f.format(Date(t)))
        assertEquals("2026-11-24 20:30", f.format(Date(XmlTv.parseTime("20261124203000"))))
    }

    @Test fun norm() {
        assertEquals(XmlTv.norm("Das Erste"), XmlTv.norm("DE: Das Erste HD"))
        assertEquals(XmlTv.norm("ZDF"), XmlTv.norm("ZDF FHD"))
    }

    @Test fun xmltv() {
        val f = SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        fun ts(o: Long) = f.format(Date(now + o))
        val h = 3600_000L
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
 <channel id="ard.de"><display-name>Das Erste</display-name></channel>
 <channel id="zdf.de"><display-name lang="de">ZDF</display-name></channel>
 <programme start="${ts(-h)}" stop="${ts(h)}" channel="ard.de"><title lang="de">Tagesschau &amp; Co</title><desc>Nachrichten</desc></programme>
 <programme start="${ts(h)}" stop="${ts(2*h)}" channel="ard.de"><title>Tatort</title></programme>
 <programme start="${ts(-h)}" stop="${ts(h)}" channel="zdf.de"><title>heute</title></programme>
 <programme start="${ts(-100*h)}" stop="${ts(-99*h)}" channel="zdf.de"><title>alt</title></programme>
 <programme start="${ts(0)}" stop="${ts(h)}" channel="other"><title>x</title></programme>
</tv>"""
        val chans = listOf(
            Channel("Das Erste HD", "u1", null, "g", "ARD.de", 1),
            Channel("DE: ZDF HD", "u2", null, "g", null, 2)
        )
        val m = XmlTv.parse(xml.byteInputStream(), chans)
        assertEquals(listOf("Tagesschau & Co", "Tatort"), m["ard.de"]!!.map { it.title })
        assertEquals("Nachrichten", m["ard.de"]!![0].desc)
        assertEquals(listOf("heute"), m["n:" + XmlTv.norm("ZDF")]!!.map { it.title })
    }
}

class XtreamLinkTest {
    @Test fun fullLinkInServerField() {
        val p = Playlist("1", "x", "xtream", "", "http://abc.example.com:8080/get.php?username=jorge&password=geheim&type=m3u_plus", "", "", "")
        val n = Xtream.normalize(p)
        org.junit.Assert.assertEquals("http://abc.example.com:8080", n.server)
        org.junit.Assert.assertEquals("jorge", n.user)
        org.junit.Assert.assertEquals("geheim", n.pass)
        val plain = Playlist("1", "x", "xtream", "", "abc.example.com:8080", "u", "p", "")
        org.junit.Assert.assertEquals(plain, Xtream.normalize(plain))
    }
}

class CountryTest {
    @Test fun codes() {
        org.junit.Assert.assertEquals("DE", Countries.code("DE | Sport"))
        org.junit.Assert.assertEquals("PT", Countries.code("|PT| Desporto"))
        org.junit.Assert.assertEquals("UK", Countries.code("[UK] News"))
        org.junit.Assert.assertEquals("FR", Countries.code("FR: Cinéma"))
        org.junit.Assert.assertEquals("DE", Countries.code("Deutschland HD"))
        org.junit.Assert.assertEquals("PT", Countries.code("PT - Notícias"))
        org.junit.Assert.assertEquals("?", Countries.code("Sport"))
        org.junit.Assert.assertEquals("TR", Countries.code("TR ✪ SPOR"))
        org.junit.Assert.assertEquals("GR", Countries.code("GR ✪ Ντοκιμαντέρ | DOCUMENTARY"))
        org.junit.Assert.assertEquals("ES", Countries.code("ES ✪ CINEMA"))
        org.junit.Assert.assertEquals("?", Countries.code("DAZN SENDER"))
        org.junit.Assert.assertEquals("?", Countries.code("NEWS"))
        org.junit.Assert.assertEquals("Deutschland (DE)", Countries.label("DE"))
    }
    @Test fun favKeyStable() {
        val a = Channel("Das Erste HD", "http://a/live/u/p/1.ts", null, "g", null, 1)
        val b = a.copy(url = "http://a/live/u/p/1.m3u8")
        org.junit.Assert.assertEquals(favKey(a), favKey(b))
    }
}

class VodTest {
    @Test fun vodDetection() {
        org.junit.Assert.assertTrue(Vod.isVodUrl("http://s:80/movie/u/p/123.mkv"))
        org.junit.Assert.assertTrue(Vod.isVodUrl("http://s:80/series/u/p/55.mp4"))
        org.junit.Assert.assertTrue(Vod.isVodUrl("http://x/film.mp4"))
        org.junit.Assert.assertFalse(Vod.isVodUrl("http://s:80/live/u/p/1.ts"))
        org.junit.Assert.assertFalse(Vod.isVodUrl("http://s:80/u/p/1"))
        org.junit.Assert.assertFalse(Vod.isVodUrl("http://s/hls/stream.m3u8"))
    }

    @org.junit.Test fun adultGroups() {
        org.junit.Assert.assertEquals("XXX", Countries.code("XXX ✪ FOR ADULTS (18+)"))
        org.junit.Assert.assertEquals("XXX", Countries.code("FOR ADULTS (18+)"))
        org.junit.Assert.assertEquals("DE", Countries.code("DE | Sport"))
        org.junit.Assert.assertEquals("PT", Countries.code("PT: Desporto"))
    }
}
