package com.jorgepnt.pintotv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class TextItem(val label: String, val count: String = "", val header: String? = null)

/** Liste für Menü, Gruppen und Playlists */
class TextAdapter(
    private val onFocus: (Int) -> Unit = {},
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit = {},
    /** ganzen Text zeigen (mehrzeilig) statt mit „…“ abzuschneiden */
    private val wrap: Boolean = false
) : RecyclerView.Adapter<TextAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.label)
        val count: TextView = v.findViewById(R.id.count)
        val header: TextView = v.findViewById(R.id.header)
    }

    var items: List<TextItem> = emptyList()
        set(v) {
            val same = v.size == field.size && v.isNotEmpty()
            field = v
            if (same) notifyItemRangeChanged(0, v.size) else notifyDataSetChanged() // gleiche Länge: an Ort und Stelle, Fokus bleibt
        }

    var activePos = -1
        set(v) { val old = field; field = v; if (old >= 0) notifyItemChanged(old); if (v >= 0) notifyItemChanged(v) }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_text, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val it = items[pos]
        if (wrap) { h.label.maxLines = 4; h.label.ellipsize = null; h.label.textSize = 15f }
        h.label.text = it.label
        h.count.text = it.count
        // Zahl als kleines Kästchen, Pfeile/leer ohne Kästchen
        h.count.visibility = if (it.count.isEmpty()) View.GONE else View.VISIBLE
        h.count.background = if (it.count.any { c -> c.isDigit() }) h.count.context.getDrawable(R.drawable.count_bg) else null
        h.header.visibility = if (it.header != null) View.VISIBLE else View.GONE
        h.header.text = it.header ?: ""
        h.itemView.isActivated = pos == activePos
        h.itemView.setOnFocusChangeListener { v, f -> Focus.pop(v.findViewById(R.id.row), f); if (f) onFocus(h.bindingAdapterPosition) }
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
        val initials: TextView = v.findViewById(R.id.initials)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    var items: List<Channel> = emptyList()
        set(v) {
            val same = v.size == field.size && v.isNotEmpty()
            field = v
            if (same) notifyItemRangeChanged(0, v.size) else notifyDataSetChanged()
        }
    var playingUrl: String? = null
    var favorites: Set<String> = emptySet()
    /** Sender, der gerade verschoben wird (Favoritenliste ordnen) */
    var moving: String? = null

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.number.text = c.number.toString()
        h.name.text = when {
            moving != null && favKey(c) == moving -> "⇅  ${c.name}"
            favKey(c) in favorites -> "★ ${c.name}"
            else -> c.name
        }
        // Logo im einheitlichen Kästchen; ohne Logo die Anfangsbuchstaben
        h.initials.text = c.name.split(' ', '-', '_').filter { w -> w.isNotBlank() && w.first().isLetterOrDigit() }.take(2).joinToString("") { w -> w.take(1).uppercase() }
        h.initials.visibility = View.VISIBLE
        h.logo.setImageDrawable(null)
        h.itemView.scaleX = if (h.itemView.isFocused) 1.025f else 1f; h.itemView.scaleY = h.itemView.scaleX
        if (!c.logo.isNullOrBlank()) h.logo.load(c.logo) {
            crossfade(false); size(160, 100)
            listener(onSuccess = { _, _ -> h.initials.visibility = View.INVISIBLE })
        }
        val now = System.currentTimeMillis()
        val (cur, _) = Repo.nowNext(c, now)
        if (cur != null) {
            h.now.text = "${Fmt.time(cur.start)}  ${cur.title}"
            h.now.visibility = View.VISIBLE
            h.progress.visibility = View.VISIBLE
            h.progress.progress = Fmt.progress(cur, now)
        } else {
            // ohne Programm: nichts anzeigen, die Liste bleibt ruhig
            h.now.visibility = View.GONE
            h.progress.visibility = View.GONE
        }
        h.itemView.isActivated = c.url == playingUrl
        h.itemView.setOnFocusChangeListener { v, f -> Focus.pop(v, f); if (f) onFocus(c) }
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
    }

    fun indexOf(url: String?) = items.indexOfFirst { it.url == url }
}

/** markierte Zeile hebt sich leicht ab */
object Focus {
    fun pop(v: View?, focused: Boolean) {
        v ?: return
        v.animate().cancel()
        v.animate().scaleX(if (focused) 1.025f else 1f).scaleY(if (focused) 1.025f else 1f).setDuration(120).start()
        v.elevation = if (focused) 6f else 0f
    }
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
