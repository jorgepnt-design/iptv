package com.jorgepnt.pintotv

import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Hoch/Runter in Listen selbst steuern, damit der Fokus nie in eine andere Spalte springt */
object ListNav {
    /** delta in Zeilen (-1 / +1); bei Rastern wird eine ganze Zeile gesprungen */
    fun move(rv: RecyclerView, focused: View?, delta: Int): Boolean {
        val item = focused?.let { rv.findContainingItemView(it) } ?: return false
        val pos = rv.getChildAdapterPosition(item)
        if (pos == RecyclerView.NO_POSITION) return true
        val n = rv.adapter?.itemCount ?: 0
        val step = (rv.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val target = pos + delta * step
        if (target < 0 || target >= n) return true // Rand: stehen bleiben
        val vh = rv.findViewHolderForAdapterPosition(target)
        if (vh != null && vh.itemView.isShown) {
            vh.itemView.requestFocus()
            // etwas vorausscrollen, damit die nächsten Einträge schon bereitliegen
            rv.smoothScrollBy(0, delta * (vh.itemView.height / 2).coerceAtLeast(1))
        } else {
            rv.scrollToPosition(target)
            var tries = 0
            lateinit var r: Runnable
            r = Runnable {
                val h = rv.findViewHolderForAdapterPosition(target)
                if (h != null) h.itemView.requestFocus() else if (++tries < 10) rv.postDelayed(r, 30)
            }
            rv.post(r)
        }
        return true
    }
}
