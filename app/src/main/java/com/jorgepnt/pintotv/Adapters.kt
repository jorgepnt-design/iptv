package com.jorgepnt.pintotv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class TextItem(val label: String, val count: String = "")

/** Liste für Menü, Gruppen und Playlists */
class TextAdapter(
    private val onFocus: (Int) -> Unit = {},
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<TextAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.label)
        val count: TextView = v.findViewById(R.id.count)
    }

    var items: List<TextItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    var activePos = -1
        set(v) { val old = field; field = v; if (old >= 0) notifyItemChanged(old); if (v >= 0) notifyItemChanged(v) }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_text, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        h.label.text = it.label
        h.count.text = it.count
        h.itemView.isActivated = pos == activePos
        h.itemView.setOnFocusChangeListener { _, f -> if (f) onFocus(h.bindingAdapterPosition) }
        h.itemView.setOnClickListener { onClick(h.bindingAdapterPosition) }
        h.itemView.setOnLongClickListener { onLongClick(h.bindingAdapterPosition); true }
    }
}

class ChannelAdapter(
    private val onFocus: (Channel) -> Unit,
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val number: TextView = v.findViewById(R.id.number)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    var items: List<Channel> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }
    var playingUrl: String? = null
    var favorites: Set<String> = emptySet()

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.number.text = c.number.toString()
        h.name.text = if (favKey(c) in favorites) "★ ${c.name}" else c.name
        h.logo.load(c.logo) { crossfade(false); size(160, 100) }
        val now = System.currentTimeMillis()
        val (cur, _) = Repo.nowNext(c, now)
        if (cur != null) {
            h.now.text = "${Fmt.time(cur.start)}  ${cur.title}"
            h.progress.visibility = View.VISIBLE
            h.progress.progress = Fmt.progress(cur, now)
        } else {
            h.now.text = if (Repo.epg.isEmpty()) "" else "Keine Informationen"
            h.progress.visibility = View.INVISIBLE
        }
        h.itemView.isActivated = c.url == playingUrl
        h.itemView.setOnFocusChangeListener { _, f -> if (f) onFocus(c) }
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
    }

    fun indexOf(url: String?) = items.indexOfFirst { it.url == url }
}

object Fmt {
    private val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY)
    private val d = java.text.SimpleDateFormat("EEE, d. MMM", java.util.Locale.GERMANY)
    fun time(ms: Long): String = t.format(java.util.Date(ms))
    fun day(ms: Long): String = d.format(java.util.Date(ms))
    fun range(p: Program): String {
        val mins = (p.stop - p.start) / 60000
        return "${day(p.start)} · ${time(p.start)} – ${time(p.stop)} · $mins Min."
    }
    fun progress(p: Program, now: Long): Int =
        (((now - p.start).toDouble() / (p.stop - p.start)) * 1000).toInt().coerceIn(0, 1000)
}
