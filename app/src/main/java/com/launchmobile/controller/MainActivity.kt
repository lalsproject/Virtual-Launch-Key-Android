package com.launchmobile.controller

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val mp = ViewGroup.LayoutParams.MATCH_PARENT
    private val wc = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var piano: PianoView
    private lateinit var pages: List<View>
    private lateinit var tabs: List<Button>
    private lateinit var monitorView: TextView
    private lateinit var info: TextView
    private lateinit var btnEdit: Button
    private lateinit var btnChord: Button
    private lateinit var btnArp: Button
    private lateinit var btnSus: Button
    private val redraw = ArrayList<View>()
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Cfg.load(this)
        Store.init(this)
        MidiRouter.start(this)
        setContentView(buildUi())
        MidiRouter.monitor = { s -> monitorView.text = s }
        if (Cfg.keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyOutputs()
        refreshInfo()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onPause() {
        super.onPause()
        piano.releaseAll()
        for (v in redraw) if (v is PanelView) v.releaseAll()
        Perf.allOff()
    }

    override fun onDestroy() {
        if (isFinishing) {
            BlePeripheral.stop()
            MidiRouter.bleOn = false
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ util UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    private fun btn(t: String, click: (View) -> Unit): Button = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(Color.WHITE)
        background = round(0xFF2E3440.toInt())
        setOnClickListener { v -> click(v) }
    }

    private fun tint(b: Button, on: Boolean, onColor: Int = 0xFF3D7BFF.toInt()) {
        b.background = round(if (on) onColor else 0xFF2E3440.toInt())
    }

    private fun redrawAll() {
        for (v in redraw) v.invalidate()
    }

    // ------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14161B.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        val names = listOf("KEYS", "PADS", "MIX")
        tabs = names.mapIndexed { i, n -> btn(n) { showPage(i) } }
        for (t in tabs) bar.addView(t, lp(wc, dp(36)).apply { rightMargin = dp(4) })

        btnEdit = btn("EDIT") {
            Cfg.editMode = !Cfg.editMode
            tint(btnEdit, Cfg.editMode, 0xFFB8860B.toInt())
            redrawAll()
            if (Cfg.editMode) Toast.makeText(this, "Mode EDIT: sentuh kontrol untuk mengubah mapping", Toast.LENGTH_SHORT).show()
        }
        bar.addView(btnEdit, lp(wc, dp(36)).apply { leftMargin = dp(8); rightMargin = dp(4) })
        bar.addView(btn("MIDI") { showOutputDialog() }, lp(wc, dp(36)).apply { rightMargin = dp(4) })
        bar.addView(btn("MENU") { showMenu() }, lp(wc, dp(36)).apply { rightMargin = dp(4) })
        bar.addView(btn("PANIC") { Out.panic(); piano.releaseAll() }, lp(wc, dp(36)))

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        monitorView = TextView(this).apply {
            setTextColor(0xFF6BE675.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.CENTER
            text = "--"
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(scroll, lp(0, wc, 1f))
        top.addView(monitorView, lp(dp(96), mp))
        root.addView(top, lp(mp, wc))

        val content = FrameLayout(this)
        pages = listOf(buildKeysPage(), buildPadsPage(), buildMixPage())
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(mp, mp))
        root.addView(content, lp(mp, 0, 1f))
        showPage(0)
        return root
    }

    private fun showPage(i: Int) {
        if (page == 0 && i != 0) piano.releaseAll()
        page = i
        for ((k, p) in pages.withIndex()) p.visibility = if (k == i) View.VISIBLE else View.GONE
        for ((k, t) in tabs.withIndex()) tint(t, k == i)
        for (v in redraw) if (v is PanelView) v.releaseAll()
    }

    private fun buildKeysPage(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val wheels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(2), dp(4))
        }
        val pitch = WheelView(this, Store.get("pitch"), true) { editCtl(it) }
        val mod = WheelView(this, Store.get("mod"), false) { editCtl(it) }
        redraw.add(pitch)
        redraw.add(mod)
        wheels.addView(pitch, lp(dp(46), mp).apply { rightMargin = dp(4) })
        wheels.addView(mod, lp(dp(46), mp))
        row.addView(wheels, lp(wc, mp))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(4), dp(4), dp(2))
        }
        fun add(b: Button) = strip.addView(b, lp(0, mp, 1f).apply { rightMargin = dp(3) })

        add(btn("OCT −") { shiftOct(-1) })
        add(btn("OCT +") { shiftOct(1) })
        add(btn("TRN −") { shiftTrans(-1) })
        add(btn("TRN +") { shiftTrans(1) })

        btnSus = btn("SUSTAIN") { }
        bindCtlButton(btnSus, Store.get("sustain"))
        add(btnSus)

        btnChord = btn("CHORD") {
            Cfg.chord = (Cfg.chord + 1) % Perf.CHORD_NAMES.size
            Perf.allOff(); piano.releaseAll()
            Cfg.save(); refreshInfo()
        }
        btnChord.setOnLongClickListener {
            Cfg.chord = 0
            Perf.allOff(); piano.releaseAll()
            Cfg.save(); refreshInfo()
            true
        }
        add(btnChord)

        btnArp = btn("ARP") {
            Perf.setArp(!Cfg.arpOn)
            piano.releaseAll()
            Cfg.save(); refreshInfo()
        }
        btnArp.setOnLongClickListener { showArpDialog(); true }
        add(btnArp)

        info = TextView(this).apply {
            setTextColor(0xFFAAB0BD.toInt())
            textSize = 11f
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        piano = PianoView(this)
        col.addView(strip, lp(mp, dp(40)))
        col.addView(info, lp(mp, wc))
        col.addView(piano, lp(mp, 0, 1f).apply { rightMargin = dp(4); bottomMargin = dp(4) })
        row.addView(col, lp(0, mp, 1f))
        return row
    }

    private fun buildPadsPage(): View {
        val cells = ArrayList<Cell>()
        val pw = 0.74f / 8
        for (i in 0..7) cells.add(Cell(Store.get("pad$i"), 0.01f + i * pw, 0.03f, pw, 0.31f))
        for (i in 0..7) cells.add(Cell(Store.get("pad${8 + i}"), 0.01f + i * pw, 0.35f, pw, 0.31f))
        val transport = listOf("play", "stop", "rec", "loop", "capture", "quant", "click", "undo")
        for ((i, id) in transport.withIndex()) cells.add(Cell(Store.get(id), 0.01f + i * pw, 0.70f, pw, 0.27f))
        val side = listOf("trkL", "trkR", "up", "down", "scene1", "scene2", "shift")
        for ((i, id) in side.withIndex()) {
            cells.add(Cell(Store.get(id), 0.77f + (i % 2) * 0.115f, 0.03f + (i / 2) * 0.235f, 0.115f, 0.23f))
        }
        val v = PanelView(this, cells) { editCtl(it) }
        redraw.add(v)
        return v
    }

    private fun buildMixPage(): View {
        val cells = ArrayList<Cell>()
        val cw = 1f / 9
        for (i in 0..7) cells.add(Cell(Store.get("knob$i"), i * cw, 0.01f, cw, 0.36f))
        for (i in 0..8) cells.add(Cell(Store.get("fader$i"), i * cw, 0.38f, cw, 0.47f))
        for (i in 0..8) cells.add(Cell(Store.get("fbtn$i"), i * cw, 0.86f, cw, 0.13f))
        val v = PanelView(this, cells) { editCtl(it) }
        redraw.add(v)
        return v
    }

    /** Tombol biasa (bukan canvas) yang memakai mapping sebuah Ctl, mis. sustain. */
    private fun bindCtlButton(b: Button, c: Ctl) {
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (Cfg.editMode) {
                        editCtl(c)
                    } else if (c.toggle) {
                        c.on = !c.on
                        if (c.on) Out.press(c) else Out.release(c)
                        tint(b, c.on, 0xFF2E9E5B.toInt())
                    } else {
                        c.on = true
                        Out.press(c)
                        tint(b, true, 0xFF2E9E5B.toInt())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!Cfg.editMode && !c.toggle && c.on) {
                        c.on = false
                        Out.release(c)
                        tint(b, false)
                    }
                }
            }
            true
        }
    }

    // ------------------------------------------------------------ aksi keyboard

    private fun shiftOct(d: Int) {
        piano.releaseAll()
        Cfg.octShift = (Cfg.octShift + d).coerceIn(-4, 4)
        Cfg.save(); refreshInfo()
    }

    private fun shiftTrans(d: Int) {
        piano.releaseAll()
        Perf.allOff()
        Cfg.transpose = (Cfg.transpose + d).coerceIn(-12, 12)
        Cfg.save(); refreshInfo()
    }

    private fun sign(v: Int) = if (v > 0) "+$v" else v.toString()

    private fun refreshInfo() {
        val base = piano.baseNote()
        val top = base + 12 * Cfg.octaves
        info.text = "Kisaran C${base / 12 - 2}–C${top / 12 - 2} (note $base–$top)   Oct ${sign(Cfg.octShift)}   " +
            "Transpose ${sign(Cfg.transpose)}   Ch ${Cfg.keysCh}   Chord ${Perf.CHORD_NAMES[Cfg.chord]}   " +
            (if (Cfg.arpOn) "Arp ${Perf.ARP_TYPES[Cfg.arpType]} ${Perf.ARP_RATES[Cfg.arpRate]} ${Cfg.bpm}bpm" else "Arp off")
        btnChord.text = if (Cfg.chord == 0) "CHORD" else "CHORD ${Perf.CHORD_NAMES[Cfg.chord]}"
        tint(btnChord, Cfg.chord != 0, 0xFF8E44AD.toInt())
        tint(btnArp, Cfg.arpOn, 0xFFE67E22.toInt())
        piano.invalidate()
    }

    // ------------------------------------------------------------ dialog helper

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(4))
    }

    private fun scrolled(v: View): ScrollView = ScrollView(this).apply { addView(v) }

    private fun cap(parent: LinearLayout, t: String) {
        parent.addView(TextView(this).apply {
            text = t
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun numEdit(v: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(v.toString())
        setSingleLine()
    }

    private fun spin(items: List<String>, sel: Int): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, items.size - 1))
    }

    private fun EditText.int(def: Int): Int = text.toString().trim().toIntOrNull() ?: def

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ editor mapping

    private fun editCtl(c: Ctl) {
        val b = box()
        cap(b, "Label")
        val eLabel = EditText(this).apply { setText(c.label); setSingleLine() }
        b.addView(eLabel)

        cap(b, "Tipe pesan")
        val sType = spin(MsgType.values().map { it.label }, c.type.ordinal)
        b.addView(sType)

        cap(b, "Channel MIDI (1–16, 0 = ikut channel keyboard)")
        val eCh = numEdit(c.channel)
        b.addView(eCh)

        cap(b, "Nomor CC / Note / Program (realtime: 248–255)")
        val eNum = numEdit(c.number)
        b.addView(eNum)

        val isBtn = c.kind == Kind.BUTTON || c.kind == Kind.PAD
        cap(b, if (isBtn) "Nilai OFF (dikirim saat dilepas)" else "Nilai minimum")
        val eMin = numEdit(c.minV)
        b.addView(eMin)
        cap(b, if (isBtn) "Nilai ON (dikirim saat ditekan)" else "Nilai maksimum")
        val eMax = numEdit(c.maxV)
        b.addView(eMax)

        val cbToggle = CheckBox(this).apply { text = "Mode toggle (tekan sekali = ON, lagi = OFF)"; isChecked = c.toggle }
        if (isBtn) b.addView(cbToggle)
        val cbAll = CheckBox(this).apply { text = "Terapkan channel ini ke semua kontrol sejenis" }
        b.addView(cbAll)

        AlertDialog.Builder(this)
            .setTitle("Mapping: ${c.label}  (${c.info()})")
            .setView(scrolled(b))
            .setPositiveButton("Simpan") { _, _ ->
                val t = MsgType.values()[sType.selectedItemPosition]
                c.label = eLabel.text.toString().ifBlank { c.label }
                c.type = t
                c.channel = eCh.int(c.channel).coerceIn(0, 16)
                c.number = if (t == MsgType.REALTIME) eNum.int(c.number).coerceIn(248, 255) else eNum.int(c.number).coerceIn(0, 127)
                var lo = eMin.int(c.minV).coerceIn(0, 127)
                var hi = eMax.int(c.maxV).coerceIn(0, 127)
                if (hi < lo) { val x = lo; lo = hi; hi = x }
                c.minV = lo
                c.maxV = hi
                c.toggle = if (isBtn) cbToggle.isChecked else false
                c.value = c.minV
                c.on = false
                if (cbAll.isChecked) for (o in Store.list) if (o.kind == c.kind) o.channel = c.channel
                Store.save()
                redrawAll()
                refreshInfo()
            }
            .setNeutralButton("Default") { _, _ ->
                val d = Defaults.all().firstOrNull { it.id == c.id }
                if (d != null) {
                    c.label = d.label; c.type = d.type; c.channel = d.channel; c.number = d.number
                    c.minV = d.minV; c.maxV = d.maxV; c.toggle = d.toggle; c.value = c.minV; c.on = false
                    Store.save()
                    redrawAll()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ------------------------------------------------------------ menu

    private fun showMenu() {
        val items = arrayOf(
            "Keyboard & velocity…",
            "Arpeggiator…",
            "Output MIDI…",
            "Salin mapping (JSON) ke clipboard",
            "Tempel mapping dari clipboard",
            "Reset semua mapping ke default Launchkey MK4",
            "Tips koneksi"
        )
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, which ->
            when (which) {
                0 -> showKeyboardDialog()
                1 -> showArpDialog()
                2 -> showOutputDialog()
                3 -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("launch-mobile-mapping", Store.toJson()))
                    toast("Mapping disalin ke clipboard")
                }
                4 -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val s = cm.primaryClip?.getItemAt(0)?.text?.toString()
                    if (s != null && Store.applyJson(s)) {
                        Store.save(); redrawAll(); toast("Mapping ditempel")
                    } else toast("Isi clipboard bukan mapping yang valid")
                }
                5 -> AlertDialog.Builder(this)
                    .setTitle("Reset mapping?")
                    .setMessage("Semua perubahan mapping akan kembali ke default Launchkey MK4.")
                    .setPositiveButton("Reset") { _, _ ->
                        Store.reset()
                        recreateUiAfterReset()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
                6 -> showTips()
            }
        }.show()
    }

    private fun recreateUiAfterReset() {
        // Objek Ctl dibuat ulang saat reset, jadi susun ulang seluruh UI.
        piano.releaseAll()
        Cfg.editMode = false
        redraw.clear()
        setContentView(buildUi())
        MidiRouter.monitor = { s -> monitorView.text = s }
        refreshInfo()
        toast("Mapping dikembalikan ke default")
    }

    private fun showTips() {
        val msg = "USB ke PC/Mac (disarankan, latensi terendah)\n" +
            "1. Sambungkan HP ke komputer dengan kabel USB.\n" +
            "2. Tarik notifikasi \"Mengisi daya lewat USB\" → ketuk → pilih MIDI. " +
            "Jika tidak ada: Opsi Pengembang → Konfigurasi USB default → MIDI.\n" +
            "3. Buka MIDI → centang \"Android USB Peripheral Port\" (atau nama serupa).\n" +
            "4. Di DAW pilih perangkat MIDI baru yang muncul sebagai input.\n\n" +
            "Bluetooth MIDI\n" +
            "MIDI → centang \"Bluetooth MIDI\". HP tampil sebagai perangkat BLE MIDI; " +
            "pasangkan dari PC/Mac/iPad/HP lain (mis. aplikasi MIDIberry, loopMIDI + MIDIberry, atau Audio MIDI Setup di Mac).\n\n" +
            "Aplikasi di HP yang sama (mis. synth/arranger)\n" +
            "MIDI → centang \"Perangkat virtual\", lalu di aplikasi tujuan pilih input \"Launch Mobile Controller\"."
        AlertDialog.Builder(this).setTitle("Tips koneksi").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ------------------------------------------------------------ dialog pengaturan

    private fun showKeyboardDialog() {
        val b = box()
        cap(b, "Channel MIDI keyboard (1–16)")
        val eCh = numEdit(Cfg.keysCh)
        b.addView(eCh)
        cap(b, "Velocity")
        val sVel = spin(listOf("Tetap (fixed)", "Dari posisi sentuhan (bawah = keras)"), Cfg.velMode)
        b.addView(sVel)
        cap(b, "Velocity tetap (1–127)")
        val eFix = numEdit(Cfg.fixedVel)
        b.addView(eFix)
        cap(b, "Jumlah oktaf yang tampil")
        val sOct = spin(listOf("1", "2", "3", "4", "5"), Cfg.octaves - 1)
        b.addView(sOct)
        val cbHaptic = CheckBox(this).apply { text = "Getar saat menekan"; isChecked = Cfg.haptic }
        b.addView(cbHaptic)
        val cbKeep = CheckBox(this).apply { text = "Layar selalu menyala"; isChecked = Cfg.keepOn }
        b.addView(cbKeep)

        AlertDialog.Builder(this).setTitle("Keyboard & velocity").setView(scrolled(b))
            .setPositiveButton("Simpan") { _, _ ->
                piano.releaseAll()
                Perf.allOff()
                Cfg.keysCh = eCh.int(Cfg.keysCh).coerceIn(1, 16)
                Cfg.velMode = sVel.selectedItemPosition
                Cfg.fixedVel = eFix.int(Cfg.fixedVel).coerceIn(1, 127)
                Cfg.octaves = sOct.selectedItemPosition + 1
                Cfg.haptic = cbHaptic.isChecked
                Cfg.keepOn = cbKeep.isChecked
                if (Cfg.keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Cfg.save()
                refreshInfo()
                redrawAll()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showArpDialog() {
        val b = box()
        cap(b, "Pola")
        val sType = spin(Perf.ARP_TYPES.toList(), Cfg.arpType)
        b.addView(sType)
        cap(b, "Kecepatan (rate)")
        val sRate = spin(Perf.ARP_RATES.toList(), Cfg.arpRate)
        b.addView(sRate)
        cap(b, "Jumlah oktaf (1–4)")
        val sOct = spin(listOf("1", "2", "3", "4"), Cfg.arpOct - 1)
        b.addView(sOct)
        cap(b, "Gate (% panjang note, 5–100)")
        val eGate = numEdit(Cfg.arpGate)
        b.addView(eGate)
        cap(b, "Tempo (BPM, 20–300)")
        val eBpm = numEdit(Cfg.bpm)
        b.addView(eBpm)
        val cbLatch = CheckBox(this).apply { text = "Latch (tahan pola setelah jari diangkat)"; isChecked = Cfg.arpLatch }
        b.addView(cbLatch)
        val cbOn = CheckBox(this).apply { text = "Arpeggiator aktif"; isChecked = Cfg.arpOn }
        b.addView(cbOn)

        AlertDialog.Builder(this).setTitle("Arpeggiator (tahan tombol ARP untuk membuka ini)").setView(scrolled(b))
            .setPositiveButton("Simpan") { _, _ ->
                piano.releaseAll()
                Perf.allOff()
                Cfg.arpType = sType.selectedItemPosition
                Cfg.arpRate = sRate.selectedItemPosition
                Cfg.arpOct = sOct.selectedItemPosition + 1
                Cfg.arpGate = eGate.int(Cfg.arpGate).coerceIn(5, 100)
                Cfg.bpm = eBpm.int(Cfg.bpm).coerceIn(20, 300)
                Cfg.arpLatch = cbLatch.isChecked
                Cfg.arpOn = cbOn.isChecked
                Cfg.save()
                refreshInfo()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ------------------------------------------------------------ output MIDI

    private fun showOutputDialog() {
        val b = box()
        val cbVirtual = CheckBox(this).apply {
            text = "Perangkat virtual (untuk aplikasi lain di HP ini)"
            isChecked = Cfg.virtualOn
        }
        b.addView(cbVirtual)
        val cbBle = CheckBox(this).apply {
            text = "Bluetooth MIDI (HP tampil sebagai perangkat BLE MIDI)"
            isChecked = Cfg.bleOn
        }
        b.addView(cbBle)

        cap(b, "Perangkat MIDI sistem (USB peripheral / Bluetooth / aplikasi lain):")
        val boxes = ArrayList<Pair<String, CheckBox>>()
        val devs = MidiRouter.devices()
        if (!MidiRouter.available) {
            b.addView(TextView(this).apply { text = "HP ini tidak melaporkan dukungan Android MIDI." })
        } else if (devs.isEmpty()) {
            b.addView(TextView(this).apply {
                text = "Belum ada perangkat. Untuk USB ke PC: aktifkan mode MIDI pada konfigurasi USB (lihat MENU → Tips koneksi)."
            })
        }
        for (d in devs) {
            val n = MidiRouter.nameOf(d)
            val cb = CheckBox(this).apply {
                text = "$n  [${MidiRouter.typeOf(d)}]"
                isChecked = n in Cfg.targets
            }
            boxes.add(Pair(n, cb))
            b.addView(cb)
        }
        val status = StringBuilder()
        status.append("Tersambung ke ${MidiRouter.connectedCount()} port sistem")
        if (BlePeripheral.running) status.append(" • BLE aktif, ${BlePeripheral.subscriberCount()} klien")
        BlePeripheral.lastError?.let { status.append(" • $it") }
        cap(b, status.toString())

        AlertDialog.Builder(this).setTitle("Output MIDI").setView(scrolled(b))
            .setPositiveButton("Terapkan") { _, _ ->
                Cfg.virtualOn = cbVirtual.isChecked
                Cfg.bleOn = cbBle.isChecked
                val sel = HashSet<String>()
                for ((n, cb) in boxes) if (cb.isChecked) sel.add(n)
                Cfg.targets = sel
                Cfg.save()
                applyOutputs()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun needBlePerm(): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)

    private fun applyOutputs() {
        MidiRouter.virtualOn = Cfg.virtualOn
        MidiRouter.applyTargets(Cfg.targets)
        if (Cfg.bleOn) {
            if (needBlePerm()) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), 7
                )
                return
            }
            val err = BlePeripheral.start(this)
            if (err != null) {
                toast("Bluetooth MIDI: $err")
                Cfg.bleOn = false
                Cfg.save()
                MidiRouter.bleOn = false
            } else {
                MidiRouter.bleOn = true
            }
        } else {
            BlePeripheral.stop()
            MidiRouter.bleOn = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                applyOutputs()
            } else {
                Cfg.bleOn = false
                Cfg.save()
                toast("Izin Bluetooth ditolak")
            }
        }
    }
}
