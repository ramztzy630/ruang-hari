# Background playback and movable mini-player

This update builds on the supplied working Ruang-Hari-Album-Lyrics-1.zip.

Mini-player: defaults above fixed bottom tabs, stays below navigation in stacking order, and can be dragged using its dotted handle. Keyboard arrow keys move it; Home or double-click on the handle resets placement. Drag bounds are clamped after resize/keyboard/orientation changes. Main content reserves space for the bar.

Android: RuangMusicService (MediaSessionService) owns MusicEngine/ExoPlayer and MediaSession, registers its session, and supplies metadata for automatic media notifications. The Capacitor plugin binds to the service and only unbinds when the UI is destroyed; it no longer releases the player. Playback can continue on Home/lock/task dismissal while media playback remains ongoing. Plugin status returns track metadata so a newly created UI can restore the active card. Explicit Stop clears the media playlist. Android force-stop/process termination is not playback resumption.

The installer adds media3-session:1.5.1, foreground-service permissions, a non-exported mediaPlayback service with stopWithTask=false, and copies service source. The existing stream resolver is unchanged.

Upload the entire archive contents to repository root (including native, scripts, dist and .github), commit, build APK in Actions, and install it as an update. Do not uninstall if preserving local app data.

Validation: JS syntax, proxy and lyric regression checks, mini-player viewport/keyboard geometry checks, repeatable installer test with manifest permissions/service/dependency assertions. Android compile and physical-device background playback have not been executed here. On a device, verify playback after pressing Home, locking the screen, dismissing the Activity, notification play/pause, then reopening without duplicate playback. Validate mini-player drag and all bottom tabs in portrait and landscape.
