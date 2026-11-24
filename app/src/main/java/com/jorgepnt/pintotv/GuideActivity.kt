package com.jorgepnt.pintotv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class GuideActivity : Activity() {

    companion object {
        var channels: List<Channel> = emptyList()
        var startUrl: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        val grid = findViewById<EpgGridView>(R.id.grid)
        val gChannel = findViewById<TextView>(R.id.gChannel)
        val gTitle = findViewById<TextView>(R.id.gTitle)
        val gTime = findViewById<TextView>(R.id.gTime)
        val gDesc = findViewById<TextView>(R.id.gDesc)
        findViewById<TextView>(R.id.gClock).text = Fmt.time(System.currentTimeMillis())

        grid.channels = channels
        grid.playingUrl = startUrl
        grid.onSelect = { ch, b ->
            gChannel.text = "${ch.number}  ${ch.name}"
            val p = b.program
            gTitle.text = p?.title ?: "Keine Programminformationen"
            gTime.text = p?.let { Fmt.range(it) } ?: ""
            gDesc.text = p?.desc ?: ""
        }
        grid.onPlay = { ch ->
            setResult(RESULT_OK, Intent().putExtra("url", ch.url))
            finish()
        }
        grid.selectUrl(startUrl)
        grid.requestFocus()
    }
}
