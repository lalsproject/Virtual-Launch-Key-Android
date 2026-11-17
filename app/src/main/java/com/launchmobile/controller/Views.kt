package com.launchmobile.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseArray
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

private fun View.dp(v: Float): Float = v * resources.displayMetrics.density

private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.style = style
    }

/** Keyboard piano multi-touch dengan glissando dan velocity dari posisi sentuhan. */
class PianoView(ctx: Context) : View(ctx) {
    private val fillW = paint(0xFFF4F4F2.toInt())
    private val fillWOn = paint(0xFF4FC3F7.toInt())
    private val fillB = paint(0xFF15171C.toInt())
    private val fillBOn = paint(0xFF0288D1.toInt())
    private val line = paint(0xFF555B66.toInt(), Paint.Style.STROKE).apply { strokeWidth = 2f }
    private val txt = paint(0xFF666B75.toInt()).apply { textAlign = Paint.Align.CENTER }

    private val white = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val blackAfter = booleanArrayOf(true, true, false, true, true, true, false)
    private val pointerNote = SparseIntArray()
    private val pressed = HashSet<Int>()

    private fun whiteCount(): Int = 7 * Cfg.octaves + 1
    fun baseNote(): Int = (48 + 12 * Cfg.octShift).coerceIn(0, (127 - 12 * Cfg.octaves).coerceAtLeast(0))
    private fun noteForWhite(i: Int): Int = baseNote() + 12 * (i / 7) + white[i % 7]

    private fun hit(x: Float, y: Float): Int {
        val n = whiteCount()
        val wk = width.toFloat() / n
        val bw = wk * 0.62f
        val bh = height * 0.62f
        val xi = x.coerceIn(0f, width - 1f)
        val i = (xi / wk).toInt().coerceIn(0, n - 1)
        if (y < bh) {
            for (j in intArrayOf(i - 1, i)) {
                if (j in 0 until n - 1 && blackAfter[j % 7]) {
                    val cx = (j + 1) * wk
                    if (Math.abs(xi - cx) <= bw / 2) return noteForWhite(j) + 1
                }
            }
        }
        return noteForWhite(i)
    }

    private fun vel(y: Float): Int {
        if (Cfg.velMode == 0) return Cfg.fixedVel
        val f = (y / height.toFloat()).coerceIn(0f, 1f)
        return (20 + f * 107).toInt().coerceIn(1, 127)
    }

    private fun press(id: Int, note: Int, v: Int, first: Boolean) {
        pointerNote.put(id, note)
        pressed.add(note)
        Perf.noteOn(note, v)
        if (first && Cfg.haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun lift(id: Int) {
        val n = pointerNote.get(id, -1)
        if (n < 0) return
        pointerNote.delete(id)
        pressed.remove(n)
        Perf.noteOff(n)
    }

    fun releaseAll() {
        for (k in pointerNote.size() - 1 downTo 0) Perf.noteOff(pointerNote.valueAt(k))
        pointerNote.clear()
        pressed.clear()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                press(e.getPointerId(i), hit(e.getX(i), e.getY(i)), vel(e.getY(i)), true)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val n = hit(e.getX(i), e.getY(i))
                    if (pointerNote.get(id, -1) != n) {
                        lift(id)
                        press(id, n, vel(e.getY(i)), false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> lift(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val n = whiteCount()
        val wk = width.toFloat() / n
        val bw = wk * 0.62f
        val bh = height * 0.62f
        txt.textSize = dp(10f)
        for (i in 0 until n) {
            val note = noteForWhite(i)
            val on = pressed.contains(note)
            c.drawRect(i * wk, 0f, (i + 1) * wk, height.toFloat(), if (on) fillWOn else fillW)
            c.drawRect(i * wk, 0f, (i + 1) * wk, height.toFloat(), line)
            if (note % 12 == 0) c.drawText("C${note / 12 - 2}", i * wk + wk / 2, height - dp(8f), txt)
        }
        for (j in 0 until n - 1) {
            if (!blackAfter[j % 7]) continue
            val note = noteForWhite(j) + 1
            val cx = (j + 1) * wk
            c.drawRect(cx - bw / 2, 0f, cx + bw / 2, bh, if (pressed.contains(note)) fillBOn else fillB)
        }
    }
}

/** Wheel vertikal: pitch bend (kembali ke tengah) atau mod wheel (tetap). */
class WheelView(
    ctx: Context,
    private val ctl: Ctl,
    private val spring: Boolean,
    private val onEdit: (Ctl) -> Unit
) : View(ctx) {
    private var frac = if (spring) 0.5f else 0f
    private var lastSent = -1
    private val bg = paint(0xFF262A33.toInt())
    private val fill = paint(0xFF3D7BFF.toInt())
    private val knob = paint(0xFFE8EAF0.toInt())
    private val txt = paint(0xFFAAB0BD.toInt()).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    private fun apply() {
        if (ctl.type == MsgType.PITCH) {
            var f = frac
            if (Math.abs(f - 0.5f) < 0.03f) f = 0.5f
            val v = if (f >= 0.5f) 8192 + ((f - 0.5f) / 0.5f * 8191f).toInt() else (f / 0.5f * 8192f).toInt()
            if (v != lastSent) {
                lastSent = v
                Out.bend(Out.ch(ctl), v)
            }
        } else {
            val v = (ctl.minV + frac * (ctl.maxV - ctl.minV)).toInt()
            ctl.value = v
            if (v != lastSent) {
                lastSent = v
                Out.value(ctl, v)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (Cfg.editMode) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onEdit(ctl)
            return true
        }
        val pad = dp(14f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                frac = (1f - (e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
                apply()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (spring) {
                    frac = 0.5f
                    apply()
                }
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val m = dp(3f)
        r.set(m, m, width - m, height - m)
        c.drawRoundRect(r, dp(10f), dp(10f), bg)
        val pad = dp(14f)
        val y = pad + (1f - frac) * (height - 2 * pad)
        if (!spring) {
            r.set(m, y, width - m, height - m)
            c.drawRoundRect(r, dp(10f), dp(10f), fill)
        }
        r.set(m, y - dp(9f), width - m, y + dp(9f))
        c.drawRoundRect(r, dp(6f), dp(6f), knob)
        txt.textSize = dp(10f)
        c.drawText(ctl.label, width / 2f, dp(14f), txt)
        if (Cfg.editMode) c.drawText(ctl.info(), width / 2f, height - dp(6f), txt)
    }
}

class Cell(val ctl: Ctl, val x: Float, val y: Float, val w: Float, val h: Float) {
    val r = RectF()
}

/** Panel generik: pad, tombol, knob, dan fader pada grid pecahan. */
class PanelView(
    ctx: Context,
    private val cells: List<Cell>,
    private val onEdit: (Ctl) -> Unit
) : View(ctx) {
    private class Touch(val cell: Cell, val startY: Float, val startVal: Float)

    private val touches = SparseArray<Touch>()
    private val box = RectF()

    private val txtMain = paint(0xFFFFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val txtSmall = paint(0xFF9AA3B5.toInt()).apply { textAlign = Paint.Align.CENTER }
    private val trackP = paint(0xFF2B303B.toInt(), Paint.Style.STROKE).apply { strokeCap = Paint.Cap.ROUND }
    private val valP = paint(0xFF3D7BFF.toInt(), Paint.Style.STROKE).apply { strokeCap = Paint.Cap.ROUND }
    private val handleP = paint(0xFFE8EAF0.toInt())
    private val fillP = paint(0xFF000000.toInt())
    private val editP = paint(0xFFFFC107.toInt(), Paint.Style.STROKE)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        for (c in cells) c.r.set(c.x * w, c.y * h, (c.x + c.w) * w, (c.y + c.h) * h)
    }

    private fun isToggle(c: Ctl) = c.toggle && c.type != MsgType.REALTIME && c.type != MsgType.PC

    private fun find(x: Float, y: Float): Cell? {
        for (c in cells) if (c.r.contains(x, y)) return c
        return null
    }

    private fun haptic() {
        if (Cfg.haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun padVel(cell: Cell, y: Float): Int {
        if (Cfg.velMode == 0) return Cfg.fixedVel
        val f = ((y - cell.r.top) / cell.r.height()).coerceIn(0f, 1f)
        return (30 + f * 97).toInt().coerceIn(1, 127)
    }

    private fun faderBounds(cell: Cell): Pair<Float, Float> {
        val top = cell.r.top + dp(10f)
        val bottom = cell.r.bottom - dp(34f)
        return Pair(top, bottom)
    }

    private fun setFader(cell: Cell, y: Float) {
        val c = cell.ctl
        val (top, bottom) = faderBounds(cell)
        val f = (1f - (y - top) / (bottom - top)).coerceIn(0f, 1f)
        val v = (c.minV + f * (c.maxV - c.minV)).toInt()
        if (v != c.value) {
            c.value = v
            Out.value(c, v)
        }
    }

    private fun down(id: Int, cell: Cell, y: Float) {
        val c = cell.ctl
        touches.put(id, Touch(cell, y, c.value.toFloat()))
        when (c.kind) {
            Kind.PAD -> {
                c.on = true
                Out.press(c, padVel(cell, y))
                haptic()
            }
            Kind.BUTTON -> {
                haptic()
                if (isToggle(c)) {
                    c.on = !c.on
                    if (c.on) Out.press(c) else Out.release(c)
                } else {
                    c.on = true
                    Out.press(c)
                }
            }
            Kind.FADER -> setFader(cell, y)
            Kind.KNOB -> {}
        }
    }

    private fun move(id: Int, y: Float) {
        val t = touches.get(id) ?: return
        val c = t.cell.ctl
        when (c.kind) {
            Kind.KNOB -> {
                val range = (c.maxV - c.minV).toFloat()
                val nv = t.startVal + (t.startY - y) / (t.cell.r.height() * 1.1f) * range
                val v = nv.coerceIn(c.minV.toFloat(), c.maxV.toFloat()).toInt()
                if (v != c.value) {
                    c.value = v
                    Out.value(c, v)
                }
            }
            Kind.FADER -> setFader(t.cell, y)
            else -> {}
        }
    }

    private fun up(id: Int) {
        val t = touches.get(id) ?: return
        touches.remove(id)
        val c = t.cell.ctl
        when (c.kind) {
            Kind.PAD -> {
                c.on = false
                Out.release(c)
            }
            Kind.BUTTON -> {
                if (!isToggle(c)) {
                    c.on = false
                    Out.release(c)
                }
            }
            else -> {}
        }
    }

    fun releaseAll() {
        for (k in touches.size() - 1 downTo 0) up(touches.keyAt(k))
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val cell = find(e.getX(i), e.getY(i))
                if (cell != null) {
                    if (Cfg.editMode) onEdit(cell.ctl) else down(e.getPointerId(i), cell, e.getY(i))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate()
        return true
    }

    // ---- gambar ----

    override fun onDraw(canvas: Canvas) {
        editP.strokeWidth = dp(1.5f)
        for (c in cells) {
            when (c.ctl.kind) {
                Kind.PAD, Kind.BUTTON -> drawBtn(canvas, c)
                Kind.KNOB -> drawKnob(canvas, c)
                Kind.FADER -> drawFader(canvas, c)
            }
            if (Cfg.editMode) {
                box.set(c.r)
                box.inset(dp(2f), dp(2f))
                canvas.drawRoundRect(box, dp(6f), dp(6f), editP)
            }
        }
    }

    private fun drawBtn(canvas: Canvas, c: Cell) {
        val k = c.ctl
        val on = k.on
        val base: Int
        val lit: Int
        when {
            k.kind == Kind.PAD -> { base = 0xFF34436B.toInt(); lit = 0xFF4FC3F7.toInt() }
            k.id == "play" -> { base = 0xFF1F5B3A.toInt(); lit = 0xFF43D17A.toInt() }
            k.id == "rec" -> { base = 0xFF6B2626.toInt(); lit = 0xFFFF5252.toInt() }
            else -> { base = 0xFF343A47.toInt(); lit = 0xFFE0E4EC.toInt() }
        }
        box.set(c.r)
        box.inset(dp(3f), dp(3f))
        fillP.color = if (on) lit else base
        canvas.drawRoundRect(box, dp(8f), dp(8f), fillP)
        val big = if (k.kind == Kind.PAD) dp(16f) else dp(12f)
        txtMain.textSize = big
        txtMain.color = if (on && k.kind != Kind.PAD) 0xFF14161B.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawText(k.label, box.centerX(), box.centerY() + big * 0.2f, txtMain)
        if (box.height() > dp(36f)) {
            txtSmall.textSize = dp(9f)
            canvas.drawText(k.info(), box.centerX(), box.bottom - dp(5f), txtSmall)
        }
    }

    private fun drawKnob(canvas: Canvas, c: Cell) {
        val k = c.ctl
        val r = Math.min(c.r.width() * 0.38f, c.r.height() * 0.34f)
        val cx = c.r.centerX()
        val cy = c.r.top + c.r.height() * 0.40f
        trackP.strokeWidth = dp(5f)
        valP.strokeWidth = dp(5f)
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, 135f, 270f, false, trackP)
        val range = (k.maxV - k.minV).coerceAtLeast(1)
        val frac = (k.value - k.minV).toFloat() / range
        canvas.drawArc(box, 135f, 270f * frac, false, valP)
        txtMain.textSize = dp(12f)
        txtMain.color = 0xFFFFFFFF.toInt()
        canvas.drawText(k.value.toString(), cx, cy + dp(4f), txtMain)
        txtMain.textSize = dp(11f)
        canvas.drawText(k.label, cx, c.r.bottom - dp(16f), txtMain)
        txtSmall.textSize = dp(9f)
        canvas.drawText(k.info(), cx, c.r.bottom - dp(4f), txtSmall)
    }

    private fun drawFader(canvas: Canvas, c: Cell) {
        val k = c.ctl
        val cx = c.r.centerX()
        val (top, bottom) = faderBounds(c)
        val range = (k.maxV - k.minV).coerceAtLeast(1)
        val frac = (k.value - k.minV).toFloat() / range
        val y = bottom - frac * (bottom - top)
        trackP.strokeWidth = dp(6f)
        valP.strokeWidth = dp(6f)
        canvas.drawLine(cx, top, cx, bottom, trackP)
        canvas.drawLine(cx, bottom, cx, y, valP)
        box.set(cx - dp(16f), y - dp(8f), cx + dp(16f), y + dp(8f))
        canvas.drawRoundRect(box, dp(4f), dp(4f), handleP)
        txtMain.textSize = dp(11f)
        txtMain.color = 0xFFFFFFFF.toInt()
        canvas.drawText("${k.label} ${k.value}", cx, c.r.bottom - dp(18f), txtMain)
        txtSmall.textSize = dp(9f)
        canvas.drawText(k.info(), cx, c.r.bottom - dp(6f), txtSmall)
    }
}
