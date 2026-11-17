package com.launchmobile.controller

import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/** Mesin performa: transpose, fixed chord, arpeggiator. Semua note keyboard lewat sini. */
object Perf {
    val CHORD_NAMES = arrayOf("OFF", "Maj", "Min", "Dom7", "Maj7", "Min7", "Sus4", "Dim", "Pwr")
    private val CHORDS = arrayOf(
        intArrayOf(0), intArrayOf(0, 4, 7), intArrayOf(0, 3, 7), intArrayOf(0, 4, 7, 10),
        intArrayOf(0, 4, 7, 11), intArrayOf(0, 3, 7, 10), intArrayOf(0, 5, 7),
        intArrayOf(0, 3, 6), intArrayOf(0, 7, 12)
    )
    val ARP_TYPES = arrayOf("Up", "Down", "Up-Down", "Random", "As played")
    val ARP_RATES = arrayOf("1/4", "1/8", "1/8T", "1/16", "1/16T", "1/32")
    private val RATE_BEATS = floatArrayOf(1f, 0.5f, 1f / 3f, 0.25f, 1f / 6f, 0.125f)

    private val main = Handler(Looper.getMainLooper())
    private val counts = IntArray(128)
    private val keyNotes = HashMap<Int, IntArray>()
    private val held = ArrayList<Int>()
    private val physDown = HashSet<Int>()
    private var arpVel = 100
    private var step = 0
    private var curArp = -1
    private var running = false

    private val stepper = object : Runnable {
        override fun run() { doStep() }
    }
    private val gateOff = object : Runnable {
        override fun run() {
            if (curArp >= 0) {
                rawOff(curArp)
                curArp = -1
            }
        }
    }

    private fun raw(note: Int, vel: Int) {
        if (note !in 0..127) return
        counts[note]++
        Out.note(Cfg.keysCh, note, vel)
    }

    private fun rawOff(note: Int) {
        if (note !in 0..127) return
        if (counts[note] > 0) {
            counts[note]--
            if (counts[note] == 0) Out.note(Cfg.keysCh, note, 0)
        }
    }

    private fun intervals(): IntArray = CHORDS[Cfg.chord.coerceIn(0, CHORDS.size - 1)]

    fun noteOn(key: Int, vel: Int) {
        if (Cfg.arpOn) {
            arpVel = vel
            if (Cfg.arpLatch && physDown.isEmpty()) held.clear()
            physDown.add(key)
            if (!held.contains(key)) held.add(key)
            if (!running) {
                running = true
                step = 0
                main.post(stepper)
            }
            return
        }
        val iv = intervals()
        val notes = IntArray(iv.size) { key + Cfg.transpose + iv[it] }
        keyNotes[key]?.let { old -> for (n in old) rawOff(n) }
        keyNotes[key] = notes
        for (n in notes) raw(n, vel)
    }

    fun noteOff(key: Int) {
        if (Cfg.arpOn) {
            physDown.remove(key)
            if (!Cfg.arpLatch) {
                held.removeAll { it == key }
                if (held.isEmpty()) stopArp()
            }
            return
        }
        val notes = keyNotes.remove(key) ?: return
        for (n in notes) rawOff(n)
    }

    fun setArp(on: Boolean) {
        allOff()
        Cfg.arpOn = on
    }

    private fun stopArp() {
        main.removeCallbacks(stepper)
        main.removeCallbacks(gateOff)
        if (curArp >= 0) {
            rawOff(curArp)
            curArp = -1
        }
        running = false
    }

    fun allOff() {
        stopArp()
        held.clear()
        physDown.clear()
        for (n in 0..127) {
            if (counts[n] > 0) {
                Out.note(Cfg.keysCh, n, 0)
                counts[n] = 0
            }
        }
        keyNotes.clear()
    }

    private fun buildSeq(): List<Int> {
        val iv = intervals()
        val base = ArrayList<Int>()
        for (k in held) for (i in iv) base.add(k + Cfg.transpose + i)
        if (Cfg.arpType != 4) base.sort()
        val uniq = LinkedHashSet<Int>(base)
        val all = ArrayList<Int>()
        for (o in 0 until Cfg.arpOct.coerceIn(1, 4)) for (n in uniq) all.add(n + 12 * o)
        val out = all.filter { it in 0..127 }
        return when (Cfg.arpType) {
            1 -> out.reversed()
            2 -> if (out.size > 2) out + out.subList(1, out.size - 1).reversed() else out
            else -> out
        }
    }

    private fun doStep() {
        if (!running) return
        main.removeCallbacks(gateOff)
        if (curArp >= 0) {
            rawOff(curArp)
            curArp = -1
        }
        val seq = buildSeq()
        if (seq.isEmpty()) {
            running = false
            return
        }
        val n = if (Cfg.arpType == 3) {
            seq[Random.nextInt(seq.size)]
        } else {
            val i = step % seq.size
            step = i + 1
            seq[i]
        }
        curArp = n
        raw(n, arpVel)
        val stepMs = (60000f / Cfg.bpm.coerceIn(20, 300) * RATE_BEATS[Cfg.arpRate.coerceIn(0, 5)]).toLong()
            .coerceAtLeast(20L)
        val gate = (stepMs * Cfg.arpGate / 100f).toLong().coerceIn(5L, stepMs)
        main.postDelayed(gateOff, gate)
        main.postDelayed(stepper, stepMs)
    }
}
