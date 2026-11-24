package com.jorgepnt.pintotv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/** TV-Guide-Raster wie bei TiviMate: Sender links, Zeitleiste oben, Sendungen als Blöcke */
class EpgGridView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs) {

    class Block(val start: Long, val stop: Long, val program: Program?)

    var channels: List<Channel> = emptyList()
        set(v) { field = v; selRow = selRow.coerceIn(0, (v.size - 1).coerceAtLeast(0)); invalidate() }
    var onSelect: ((Channel, Block) -> Unit)? = null
    var onPlay: ((Channel) -> Unit)? = null
    var playingUrl: String? = null

    private val dp = resources.displayMetrics.density
    private val headerH = 36 * dp
    private val leftW = 230 * dp
    private val rowH = 54 * dp
    private val gap = 2 * dp
    private val visibleMs = 150 * 60_000L // 2,5 Stunden sichtbar
    private val halfHour = 30 * 60_000L

    private var winStart = floor30(System.currentTimeMillis()) - halfHour
    private var selTime = System.currentTimeMillis()
    private var selRow = 0
    private var firstRow = 0

    private val bg = Paint().apply { color = Color.parseColor("#0B0E15") }
    private val cellPaint = Paint().apply { color = Color.parseColor("#1A1F2B") }
    private val cellPast = Paint().apply { color = Color.parseColor("#141821") }
    private val cellNow = Paint().apply { color = Color.parseColor("#232B3B") }
    private val selPaint = Paint().apply { color = Color.parseColor("#3D7BFF") }
    private val chanPaint = Paint().apply { color = Color.parseColor("#121620") }
    private val chanSel = Paint().apply { color = Color.parseColor("#2A3550") }
    private val nowLine = Paint().apply { color = Color.parseColor("#FF4D4D"); strokeWidth = 2 * dp }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16 * dp * 0.95f }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3FFFFFF"); textSize = 13 * dp }
    private val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#99FFFFFF"); textSize = 14 * dp }
    private val r = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun floor30(t: Long): Long {
        val off = java.util.TimeZone.getDefault().getOffset(t)
        return ((t + off) / halfHour) * halfHour - off
    }

    fun selectUrl(url: String?) {
        val i = channels.indexOfFirst { it.url == url }
        if (i >= 0) selRow = i
        post { ensureRow(); notifySel(); invalidate() }
    }

    private fun visibleRows() = ((height - headerH) / rowH).toInt().coerceAtLeast(1)
    private fun pxPerMs() = (width - leftW) / visibleMs.toFloat()
    private fun x(t: Long) = leftW + (t - winStart) * pxPerMs()

    fun block(row: Int, t: Long): Block {
        val l = Repo.programs(channels[row])
        val i = Repo.firstEndingAfter(l, t)
        val p = l.getOrNull(i)
        if (p != null && p.start <= t) return Block(p.start, p.stop, p)
        val prevStop = l.getOrNull(i - 1)?.stop ?: Long.MIN_VALUE
        val nextStart = p?.start ?: Long.MAX_VALUE
        val f = floor30(t)
        val s = maxOf(prevStop, f)
        var e = minOf(nextStart, f + halfHour)
        if (e <= s) e = s + halfHour
        return Block(s, e, null)
    }

    private fun ensureRow() {
        val v = visibleRows()
        if (selRow < firstRow) firstRow = selRow
        if (selRow >= firstRow + v) firstRow = selRow - v + 1
        firstRow = firstRow.coerceIn(0, (channels.size - v).coerceAtLeast(0))
    }

    private fun ensureTime() {
        if (selTime < winStart) winStart = floor30(selTime)
        if (selTime > winStart + visibleMs - halfHour) winStart = floor30(selTime) - halfHour
    }

    private fun notifySel() {
        if (channels.isEmpty()) return
        onSelect?.invoke(channels[selRow], block(selRow, selTime))
    }

    private fun moved() { ensureRow(); ensureTime(); notifySel(); invalidate() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        val now = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (selRow < channels.size - 1) { selRow++; moved() }; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (selRow > 0) { selRow--; moved() }; return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                selRow = (selRow + visibleRows()).coerceAtMost(channels.size - 1); moved(); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                selRow = (selRow - visibleRows()).coerceAtLeast(0); moved(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val b = block(selRow, selTime)
                if (b.stop > now + 4 * 24 * 3600_000L) return true
                selTime = maxOf(b.stop, winStart); moved(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val b = block(selRow, selTime)
                val prev = block(selRow, b.start - 1)
                if (prev.start < now - 12 * 3600_000L) return true
                selTime = maxOf(prev.start, b.start - 2 * halfHour); moved(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                onPlay?.invoke(channels[selRow]); return true
            }
            KeyEvent.KEYCODE_0 -> { selTime = now; winStart = floor30(now) - halfHour; moved(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var scrollY = 0f
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (channels.isEmpty() || e.y < headerH) return true
            val row = firstRow + ((e.y - headerH) / rowH).toInt()
            if (row >= channels.size) return true
            if (row == selRow && e.x < leftW) { onPlay?.invoke(channels[row]); return true }
            selRow = row
            if (e.x > leftW) selTime = winStart + ((e.x - leftW) / pxPerMs()).toLong()
            notifySel(); invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (channels.isNotEmpty() && e.y > headerH) {
                val row = firstRow + ((e.y - headerH) / rowH).toInt()
                if (row < channels.size) onPlay?.invoke(channels[row])
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (channels.isEmpty()) return true
            scrollY += dy
            val rows = (scrollY / rowH).toInt()
            if (rows != 0) {
                scrollY -= rows * rowH
                firstRow = (firstRow + rows).coerceIn(0, (channels.size - visibleRows()).coerceAtLeast(0))
            }
            if (Math.abs(dx) > Math.abs(dy)) {
                val now = System.currentTimeMillis()
                winStart = (winStart + (dx / pxPerMs()).toLong())
                    .coerceIn(now - 12 * 3600_000L, now + 4 * 24 * 3600_000L)
            }
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        ensureRow()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val now = System.currentTimeMillis()
        val winEnd = winStart + visibleMs

        // Kopfzeile mit Uhrzeiten
        c.drawText(Fmt.day(winStart), 16 * dp, headerH - 12 * dp, small)
        var t = winStart
        while (t < winEnd) {
            val xx = x(t)
            c.drawLine(xx, headerH - 10 * dp, xx, headerH, small)
            c.drawText(Fmt.time(t), xx + 6 * dp, headerH - 12 * dp, small)
            t += halfHour
        }

        if (channels.isEmpty()) {
            c.drawText("Keine Kanäle", leftW, headerH + rowH, text); return
        }

        val rows = visibleRows() + 1
        for (i in 0 until rows) {
            val row = firstRow + i
            if (row >= channels.size) break
            val ch = channels[row]
            val top = headerH + i * rowH
            val bottom = top + rowH - gap
            val ty = top + rowH / 2 + text.textSize / 3

            // Senderzelle
            r.set(0f, top, leftW - gap, bottom)
            c.drawRect(r, if (row == selRow) chanSel else chanPaint)
            c.drawText(ch.number.toString(), 12 * dp, ty, numPaint)
            val nameW = leftW - 64 * dp
            val name = TextUtils.ellipsize(if (ch.url == playingUrl) "▶ ${ch.name}" else ch.name, text, nameW, TextUtils.TruncateAt.END)
            c.drawText(name, 0, name.length, 56 * dp, ty, text)

            // Sendungen
            c.save()
            c.clipRect(leftW, top, width.toFloat(), bottom)
            val l = Repo.programs(ch)
            var k = Repo.firstEndingAfter(l, winStart)
            var cursor = winStart
            val sel = if (row == selRow) block(row, selTime) else null
            fun drawBlock(s: Long, e: Long, title: String, p: Program?) {
                val x0 = maxOf(x(s), leftW)
                val x1 = x(e) - gap
                if (x1 <= x0) return
                r.set(x0, top, x1, bottom)
                val paint = when {
                    sel != null && sel.start == s -> selPaint
                    e <= now -> cellPast
                    s <= now -> cellNow
                    else -> cellPaint
                }
                c.drawRect(r, paint)
                val avail = x1 - x0 - 16 * dp
                if (avail > 10 * dp) {
                    val tt = TextUtils.ellipsize(title, text, avail, TextUtils.TruncateAt.END)
                    c.drawText(tt, 0, tt.length, x0 + 8 * dp, top + rowH / 2 - 2 * dp, text)
                    if (p != null) {
                        val sub = TextUtils.ellipsize("${Fmt.time(p.start)} – ${Fmt.time(p.stop)}", small, avail, TextUtils.TruncateAt.END)
                        c.drawText(sub, 0, sub.length, x0 + 8 * dp, top + rowH / 2 + small.textSize + 1 * dp, small)
                    }
                }
            }
            while (cursor < winEnd) {
                val p = l.getOrNull(k)
                if (p != null && p.start <= cursor) {
                    drawBlock(p.start, p.stop, p.title, p); cursor = p.stop; k++
                } else {
                    val f = floor30(cursor)
                    val end = minOf(p?.start ?: Long.MAX_VALUE, f + halfHour)
                    drawBlock(cursor, end, if (l.isEmpty()) "Keine Informationen" else "", null)
                    cursor = end
                }
            }
            c.restore()
        }

        // Jetzt-Linie
        if (now in winStart..winEnd) {
            val xx = x(now)
            c.drawLine(xx, headerH - 6 * dp, xx, height.toFloat(), nowLine)
        }
    }
}
