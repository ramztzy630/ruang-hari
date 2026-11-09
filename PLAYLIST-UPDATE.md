# Playlist ala Spotify (tanpa backend)

Semua data playlist disimpan di `localStorage` perangkat (kunci `rh.playlists`), sama seperti tugas, jurnal, dan profil.

## Fitur
- Tab Musik: panel **Playlist saya** (kartu dengan mosaik sampul), tombol **Playlist baru**.
- Tombol **+** di setiap hasil pencarian dan di pemutar (kartu maupun layar penuh) membuka "Tambahkan ke playlist": centang untuk menambah/menghapus, atau buat playlist baru langsung dengan lagu itu.
- Buka playlist: **Putar**, **Acak**, ganti nama, hapus playlist, ketuk lagu untuk memutar dari lagu itu, hapus lagu dari playlist.
- Playlist diputar sebagai antrean sendiri: tombol next/previous di aplikasi dan di notifikasi mengikuti urutan playlist, terpisah dari hasil pencarian.
- Ekspor/impor cadangan (Pengaturan) kini ikut membawa playlist; impor menggabungkan tanpa menduplikasi.

## Batas dan perilaku
- Maks. 60 playlist, 500 lagu per playlist, tanpa lagu ganda di satu playlist.
- Yang disimpan hanya metadata (id video, judul, artis, album, URL sampul); audio tidak disimpan.
- Antrean native dibatasi 40 lagu dimulai dari lagu yang sedang diputar; playlist yang lebih panjang tetap bisa lanjut lewat tombol next di aplikasi.
- Data hilang jika aplikasi di-uninstall atau datanya dihapus. Pakai Ekspor di Pengaturan sebagai cadangan.
- Debug APK dari GitHub Actions ditandatangani kunci debug yang berbeda tiap build, sehingga Android bisa menolak update di atas versi lama dan harus uninstall dulu (data ikut hilang). Ekspor cadangan sebelum memasang build baru, atau gunakan keystore tetap.

## Berkas yang berubah
`dist/app.js`, `dist/index.html`, `dist/style.css`, `scripts/check-artwork.cjs` (menyesuaikan markup baris hasil pencarian), `scripts/check-playlists.cjs` (baru). Tidak ada perubahan Kotlin/Java.

Validasi: sintaks JS; seluruh `check-*.cjs` lulus; pemuatan penuh `app.js` di DOM tiruan. Belum diuji visual di perangkat.
