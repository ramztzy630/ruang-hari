# 09-11e: autoplay lagu berikutnya tanpa putus (gaya Spotify)

## Masalah
Setelah lagu habis, pemutar berhenti di 3:55 / 3:55 dengan galat
`Koneksi stream gagal (NETWORK): Unable to resolve host "www.youtube.com"`, dan lagu berikutnya tidak diputar.

Penyebab (disimpulkan dari kode dan pesan galat; belum direproduksi di perangkat): saat lagu habis, `MusicEngine`
baru mulai me-resolve lagu berikutnya lewat jaringan, padahal pada saat itu pemutar sudah tidak "playing" dan
`MediaSessionService` keluar dari mode foreground. Android (terutama dengan layar mati / penghemat baterai) bisa memblokir
jaringan aplikasi yang tidak foreground, sehingga DNS gagal. Selain itu urutan playlist tidak dihormati: pemilihan lagu
selalu acak.

## Perbaikan (native, `MusicEngine.kt`)
- Lagu berikutnya di-resolve di latar belakang beberapa detik setelah lagu aktif mulai, lalu dimasukkan ke playlist
  ExoPlayer saat lagu aktif tinggal ±35 detik. Transisi terjadi di dalam ExoPlayer (tanpa berhenti, tanpa
  `STATE_ENDED`), jadi service tetap foreground dan jaringan tidak terblokir.
- Hasil pencarian: acak, tanpa mengulang lagu yang baru diputar. Playlist: berurutan (dan berputar kembali ke awal);
  tombol Acak membuat urutan acak lebih dulu. Mode dikirim dari JS (`mode: 'shuffle' | 'sequential'`).
- Jika lagu berikutnya gagal disiapkan, lagu aktif tidak dimatikan; lagu itu dilewati dan dicoba yang lain.
- Cadangan: bila lagu berikutnya belum sempat diantre dan lagu habis, pemutar memakai hasil prefetch atau memilih lagu baru;
  untuk galat jaringan dicoba ulang sampai 3 kali (jeda 4, 8, 12 detik).
- Versi native naik ke `09-11e` (JS memeriksanya, jadi APK lama akan menampilkan pesan "build ulang APK").

## Tidak berubah
Stream resolver, ChunkedDataSource, plugin lirik, UI playlist. File yang berubah: `MusicEngine.kt`,
`MetrolistMusicPlugin.java`, `dist/app.js`, `scripts/check-music-proxy.cjs`.

## Belum diverifikasi
Kotlin belum dikompilasi dan perilaku belum diuji di perangkat (tidak ada Android SDK di lingkungan ini).
Uji nyata: putar lagu, matikan layar, tunggu lagu habis. Di beberapa HP (Xiaomi/Oppo/Vivo/Samsung) perlu juga
mengizinkan aplikasi "Tanpa pembatasan" di pengaturan baterai.
