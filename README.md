# Launch Mobile Controller

Mengubah HP Android (target: Samsung J5 Pro, Android 7.0+) menjadi **keyboard MIDI controller** dengan tata letak
dan pesan MIDI bawaan **Novation Launchkey MK4**. Semua kontrol bisa di-mapping ulang ke CC / note / program lain.

## Fitur
- **KEYS**: piano multi-touch 1–5 oktaf, glissando, velocity (tetap atau dari posisi sentuhan), pitch wheel (14-bit, kembali ke tengah),
  mod wheel (CC1), sustain (CC64), Octave ±, Transpose ±12, Fixed Chord (Maj/Min/Dom7/Maj7/Min7/Sus4/Dim/Power),
  Arpeggiator (Up/Down/Up-Down/Random/As played, rate 1/4–1/32 + triplet, oktaf 1–4, gate, BPM, latch).
- **PADS**: 16 drum pad (2×8) + Play/Stop/Rec/Loop, Capture, Quantise, Click, Undo, Track ◀▶, Scene, ▲▼, Shift.
- **MIX**: 8 knob, 9 fader, 9 tombol di bawah fader.
- **EDIT**: aktifkan, lalu sentuh kontrol mana pun untuk mengubah label, tipe pesan (CC/Note/PC/Pitch Bend/Realtime),
  channel (0 = ikut channel keyboard), nomor, nilai min/max (atau OFF/ON), mode toggle. Ada opsi "terapkan channel ke semua kontrol sejenis".
- Mapping bisa disalin / ditempel sebagai JSON (MENU) dan di-reset ke default MK4.
- **PANIC**: All Notes Off + All Sound Off di 16 channel.

## Default mapping (Launchkey MK4)
| Kontrol | Pesan |
|---|---|
| Keyboard | Note, channel 1 (bisa diubah di MENU) |
| Pad 1–16 | Note 36–51 (C1–D#2), channel 10 (atas: 40–43, 48–51, bawah: 36–39, 44–47) |
| Knob 1–8 | CC 21–28 |
| Fader 1–9 | CC 5–13 |
| Tombol fader 1–9 | CC 37–45 |
| Play / Stop | MIDI Realtime Start (0xFA) / Stop (0xFC) |
| Record / Loop | CC 117 / 118, channel 16 |
| Track ▶ / ◀ | CC 102 / 103, channel 16 |
| Scene ▶ / Scene 2 | CC 104 / 105, channel 16 |
| ▲ / ▼ | CC 106 / 107, channel 16 |
| Capture / Quantise / Click / Undo | CC 74 / 75 / 76 / 77, channel 16 |
| Mod wheel / Sustain / Pitch bend | CC1 / CC64 / pitch bend 14-bit |

Knob, fader, tombol fader, mod, sustain, dan pitch memakai channel 0 = mengikuti channel keyboard.
Nomor pad, knob, fader dan tombol DAW diambil dari Programmer's Reference Novation dan script Ableton untuk Launchkey;
yang belum jelas di dokumentasi (channel default knob/fader di mode standalone dan nomor CC tombol fader)
adalah perkiraan terbaik. Jika berbeda dengan unit asli Anda, ubah lewat mode EDIT.

## Cara menyambungkan
1. **USB ke PC/Mac** (latensi terendah): sambungkan kabel USB, ubah konfigurasi USB HP ke **MIDI**
   (notifikasi USB, atau Opsi Pengembang → Konfigurasi USB default). Di aplikasi: tombol **MIDI** → centang
   *Android USB Peripheral Port*. Di DAW pilih port MIDI baru tersebut sebagai input.
   Beberapa tipe Samsung tidak menyediakan opsi MIDI; jika begitu pakai Bluetooth.
2. **Bluetooth MIDI**: tombol **MIDI** → centang *Bluetooth MIDI*. HP tampil sebagai perangkat BLE MIDI.
   Tidak semua HP mendukung mode BLE peripheral; aplikasi akan memberi tahu jika tidak.
3. **Aplikasi lain di HP yang sama**: centang *Perangkat virtual*, lalu pilih input "Launch Mobile Controller" di aplikasi tujuan.

## Build lewat GitHub Actions + kirim ke Telegram
1. Push folder ini ke repo GitHub (branch `main`).
2. Repo → Settings → Secrets and variables → Actions → New repository secret:
   - `TELEGRAM_TOKEN` = token bot dari @BotFather
   - `TELEGRAM_TO` = chat ID tujuan
3. Setiap push ke `main` (atau Actions → Build APK → Run workflow) akan membuild APK debug, mengunggahnya sebagai artifact,
   dan mengirimnya ke Telegram. Jika secret kosong, langkah Telegram dilewati.

Build lokal: `gradle assembleDebug` (Gradle 8.7, JDK 17, Android SDK 34).
APK ada di `app/build/outputs/apk/debug/app-debug.apk`. Pasang dengan mengizinkan "instal dari sumber tidak dikenal".
