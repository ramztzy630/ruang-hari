# Ruang Hari: integrasi InnerTubeX (Metrolist)

Di APK Android, lagu online sekarang diputar langsung oleh mesin native, tidak lewat iframe YouTube:

```
cari (search, InnerTube WEB_REMIX) -> pilih lagu (videoId)
  -> music.playVideo({videoId})
  -> StreamResolver (InnerTubeX: cipher, PoToken via WebView, fallback client)
  -> URL stream + header -> Media3 ExoPlayer (ResolvingDataSource + ChunkedDataSource)
```

Player YouTube (iframe) tetap ada sebagai cadangan jika stream gagal, dan sebagai satu-satunya jalur di versi web.

## Isi

| File (`native/android/...`) | Fungsi |
| --- | --- |
| `com/ruanghari/app/MetrolistMusicPlugin.java` | Plugin Capacitor: `search`, `playVideo`, `playUrl`, `pause`, `resume`, `stop`, `seek`, `status` |
| `.../StreamResolver.kt` | InnerTubeX: videoId -> URL stream (adaptasi `InnerTubeXPlayer.kt` Metrolist) |
| `.../MusicEngine.kt` | ExoPlayer, pasang header stream, retry otomatis jika 401/403/404/410 |
| `.../ChunkedDataSource.kt` | Menyambung Range per chunk untuk stream `requireBoundedRange` / `useRangeChunks` |
| `.../potoken/*.kt` + `assets/po_token.html` | Pembuat PoToken lewat WebView (dari Metrolist) |
| `scripts/install-music-native.mjs` | Menyalin semua file di atas ke `android/` dan mem-patch Gradle |

Script install mem-patch: plugin Kotlin `2.4.20`, repo JitPack, dependency `innertubex:v0.11.2`, Ktor `3.6.0`,
coroutines, Timber, Media3 `1.5.1`, desugaring, `minSdkVersion 26`, dan izin `ACCESS_NETWORK_STATE` + `WAKE_LOCK`.
Aman dijalankan berulang kali.

## Build

Folder `android/` digenerate ulang, jadi urutannya penting. Di GitHub Actions, pastikan langkah build memanggil
(setelah `cap add/sync android`, sebelum Gradle) berurutan:

```bash
node scripts/android-resources.mjs
node scripts/install-music-native.mjs
cd android && ./gradlew assembleDebug
```

Butuh **JDK 21** dan akses internet ke `jitpack.io` saat build.

## Status jujur

- Belum dikompilasi dan belum diuji di perangkat. Kode ditulis dari membaca source Metrolist;
  library `innertubex` sendiri (di JitPack) tidak ikut terbaca, jadi nama/signature API-nya mengikuti
  cara Metrolist memakainya. Jika Gradle/Kotlin protes soal tipe atau nama, kirim log error-nya.
- Belum ada pemutaran latar belakang penuh: tidak ada foreground service / MediaSession, jadi Android bisa
  menghentikan audio setelah aplikasi lama di background. Itu tahap berikutnya (`MediaSessionService`).
- Bergantung pada YouTube. Resolver seperti ini bisa rusak kapan saja jika YouTube mengubah sesuatu.
  Mengambil stream di luar player resmi melanggar ketentuan layanan YouTube; pakai untuk keperluan pribadi dan
  pertimbangkan risikonya sebelum mendistribusikan aplikasi.
- Jika `compileSdk 35` bawaan Capacitor 7 menolak dependency tertentu, naikkan `compileSdkVersion` di
  `android/variables.gradle`.
