package com.launchmobile.controller

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

enum class Kind { KNOB, FADER, PAD, BUTTON }

enum class MsgType(val label: String) {
    CC("Control Change (CC)"),
    NOTE("Note"),
    PC("Program Change"),
    PITCH("Pitch Bend"),
    REALTIME("Realtime (250 Start, 251 Continue, 252 Stop)")
}

/** Satu kontrol di layar (pad, knob, fader, tombol) beserta mapping MIDI-nya. */
class Ctl(
    val id: String,
    val kind: Kind,
    var label: String,
    var type: MsgType,
    var channel: Int,      // 1..16, atau 0 = ikut channel keyboard
    var number: Int,       // nomor CC / note / program / status realtime
    var minV: Int = 0,
    var maxV: Int = 127,
    var toggle: Boolean = false
) {
    var value: Int = minV   // state runtime (knob / fader)
    var on: Boolean = false // state runtime (ditekan / toggle aktif)

    fun info(): String {
        val ch = if (channel == 0) "kb" else channel.toString()
        return when (type) {
            MsgType.CC -> "CC$number ch$ch"
            MsgType.NOTE -> "N$number ch$ch"
            MsgType.PC -> "PC$number ch$ch"
            MsgType.PITCH -> "PB ch$ch"
            MsgType.REALTIME -> when (number) {
                250 -> "START"
                251 -> "CONT"
                252 -> "STOP"
                else -> "RT$number"
            }
        }
    }
}

/**
 * Default mapping mengikuti Launchkey MK4:
 *  - Drum pad : note C1..D#2 (36..51) di channel 10 (urutan fisik 2 baris x 8)
 *  - Knob     : CC 21..28
 *  - Fader    : CC 5..13 (9 fader, yang ke-9 = master)
 *  - Tombol DAW/transport: CC di channel 16 (Track 102/103, Scene 104/105, Up/Down 106/107,
 *    Play 115, Stop 116, Record 117, Loop 118, Capture 74, Quantise 75, Click 76, Undo 77)
 *  - Play/Stop mengirim MIDI Realtime Start/Stop (perilaku mode Standalone MK4)
 *  - Mod wheel CC1, sustain CC64, pitch bend 14-bit
 * Knob/fader/mod/pitch/sustain channel 0 = mengikuti channel keyboard.
 */
object Defaults {
    fun all(): List<Ctl> {
        val l = ArrayList<Ctl>()
        val top = intArrayOf(40, 41, 42, 43, 48, 49, 50, 51)
        val bot = intArrayOf(36, 37, 38, 39, 44, 45, 46, 47)
        for (i in 0..7) l.add(Ctl("pad$i", Kind.PAD, "${i + 1}", MsgType.NOTE, 10, top[i]))
        for (i in 0..7) l.add(Ctl("pad${8 + i}", Kind.PAD, "${9 + i}", MsgType.NOTE, 10, bot[i]))
        for (i in 0..7) l.add(Ctl("knob$i", Kind.KNOB, "K${i + 1}", MsgType.CC, 0, 21 + i))
        for (i in 0..8) {
            l.add(Ctl("fader$i", Kind.FADER, if (i == 8) "MST" else "F${i + 1}", MsgType.CC, 0, 5 + i))
        }
        for (i in 0..8) l.add(Ctl("fbtn$i", Kind.BUTTON, "B${i + 1}", MsgType.CC, 0, 37 + i))

        l.add(Ctl("play", Kind.BUTTON, "PLAY", MsgType.REALTIME, 16, 250))
        l.add(Ctl("stop", Kind.BUTTON, "STOP", MsgType.REALTIME, 16, 252))
        l.add(Ctl("rec", Kind.BUTTON, "REC", MsgType.CC, 16, 117))
        l.add(Ctl("loop", Kind.BUTTON, "LOOP", MsgType.CC, 16, 118))
        l.add(Ctl("capture", Kind.BUTTON, "CAPTURE", MsgType.CC, 16, 74))
        l.add(Ctl("quant", Kind.BUTTON, "QUANT", MsgType.CC, 16, 75))
        l.add(Ctl("click", Kind.BUTTON, "CLICK", MsgType.CC, 16, 76))
        l.add(Ctl("undo", Kind.BUTTON, "UNDO", MsgType.CC, 16, 77))
        l.add(Ctl("trkL", Kind.BUTTON, "TRK ◀", MsgType.CC, 16, 103))
        l.add(Ctl("trkR", Kind.BUTTON, "TRK ▶", MsgType.CC, 16, 102))
        l.add(Ctl("up", Kind.BUTTON, "▲", MsgType.CC, 16, 106))
        l.add(Ctl("down", Kind.BUTTON, "▼", MsgType.CC, 16, 107))
        l.add(Ctl("scene1", Kind.BUTTON, "SCENE ▶", MsgType.CC, 16, 104))
        l.add(Ctl("scene2", Kind.BUTTON, "SCENE 2", MsgType.CC, 16, 105))
        l.add(Ctl("shift", Kind.BUTTON, "SHIFT", MsgType.CC, 16, 108))

        l.add(Ctl("mod", Kind.FADER, "MOD", MsgType.CC, 0, 1))
        l.add(Ctl("pitch", Kind.FADER, "PITCH", MsgType.PITCH, 0, 0))
        l.add(Ctl("sustain", Kind.BUTTON, "SUSTAIN", MsgType.CC, 0, 64))
        return l
    }
}

/** Penyimpanan mapping (JSON di SharedPreferences). */
object Store {
    private var sp: SharedPreferences? = null
    var list: List<Ctl> = emptyList()
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("mapping", Context.MODE_PRIVATE)
        list = Defaults.all()
        val s = sp?.getString("map", null)
        if (s != null) applyJson(s)
    }

    fun get(id: String): Ctl = list.first { it.id == id }

    fun applyJson(s: String): Boolean {
        return try {
            val o = JSONObject(s)
            for (c in list) {
                val j = o.optJSONObject(c.id) ?: continue
                c.label = j.optString("l", c.label)
                c.type = MsgType.values().getOrElse(j.optInt("t", c.type.ordinal)) { c.type }
                c.channel = j.optInt("c", c.channel).coerceIn(0, 16)
                c.number = j.optInt("n", c.number).coerceIn(0, 255)
                c.minV = j.optInt("lo", c.minV).coerceIn(0, 127)
                c.maxV = j.optInt("hi", c.maxV).coerceIn(0, 127)
                c.toggle = j.optBoolean("tg", c.toggle)
                c.value = c.minV
                c.on = false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        for (c in list) {
            val j = JSONObject()
            j.put("l", c.label)
            j.put("t", c.type.ordinal)
            j.put("c", c.channel)
            j.put("n", c.number)
            j.put("lo", c.minV)
            j.put("hi", c.maxV)
            j.put("tg", c.toggle)
            o.put(c.id, j)
        }
        return o.toString()
    }

    fun save() {
        sp?.edit()?.putString("map", toJson())?.apply()
    }

    fun reset() {
        sp?.edit()?.remove("map")?.apply()
        list = Defaults.all()
    }
}

/** Pengaturan umum aplikasi. */
object Cfg {
    private var sp: SharedPreferences? = null

    var keysCh = 1
    var velMode = 1          // 0 = fixed, 1 = posisi sentuhan (bawah = keras)
    var fixedVel = 100
    var octaves = 2          // jumlah oktaf yang tampil (1..5)
    var haptic = true
    var keepOn = true
    var octShift = 0
    var transpose = 0
    var chord = 0
    var arpOn = false
    var arpType = 0
    var arpRate = 3
    var arpOct = 1
    var arpGate = 60
    var bpm = 120
    var arpLatch = false
    var virtualOn = true
    var bleOn = false
    var targets: Set<String> = emptySet()
    var editMode = false     // tidak disimpan

    fun load(ctx: Context) {
        val s = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        sp = s
        keysCh = s.getInt("keysCh", 1).coerceIn(1, 16)
        velMode = s.getInt("velMode", 1)
        fixedVel = s.getInt("fixedVel", 100).coerceIn(1, 127)
        octaves = s.getInt("octaves", 2).coerceIn(1, 5)
        haptic = s.getBoolean("haptic", true)
        keepOn = s.getBoolean("keepOn", true)
        octShift = s.getInt("octShift", 0)
        transpose = s.getInt("transpose", 0)
        chord = s.getInt("chord", 0)
        arpType = s.getInt("arpType", 0)
        arpRate = s.getInt("arpRate", 3)
        arpOct = s.getInt("arpOct", 1)
        arpGate = s.getInt("arpGate", 60)
        bpm = s.getInt("bpm", 120)
        arpLatch = s.getBoolean("arpLatch", false)
        virtualOn = s.getBoolean("virtualOn", true)
        bleOn = s.getBoolean("bleOn", false)
        targets = HashSet(s.getStringSet("targets", emptySet()) ?: emptySet())
    }

    fun save() {
        val s = sp ?: return
        s.edit()
            .putInt("keysCh", keysCh).putInt("velMode", velMode).putInt("fixedVel", fixedVel)
            .putInt("octaves", octaves).putBoolean("haptic", haptic).putBoolean("keepOn", keepOn)
            .putInt("octShift", octShift).putInt("transpose", transpose).putInt("chord", chord)
            .putInt("arpType", arpType).putInt("arpRate", arpRate).putInt("arpOct", arpOct)
            .putInt("arpGate", arpGate).putInt("bpm", bpm).putBoolean("arpLatch", arpLatch)
            .putBoolean("virtualOn", virtualOn).putBoolean("bleOn", bleOn)
            .putStringSet("targets", HashSet(targets))
            .apply()
    }
}
