# Ruang Hari: Native Media3 audio integration

The Android Capacitor plugin now supports `playUrl({url})`, `pause()`, `resume()`, `stop()`, `seek({positionMs})`, and `status()`. It uses Android Media3 ExoPlayer.

**Not yet implemented:** Metrolist InnerTubeX song-ID-to-stream resolver, background MediaSessionService, and in-app online-song playback. The existing online song click still uses the YouTube iframe. This is a partial engineering change, not a verified Metrolist integration.

Build: GitHub Actions > Build Android APK > Run workflow. Native Gradle build and real device playback have not been verified.
