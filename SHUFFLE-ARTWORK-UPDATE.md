# Centered subtitles, shuffle autoplay and HD search artwork

Based on supplied Ruang-Hari-Scroll-Fix.zip.

Fullscreen subtitle: the free region below controls is a flex-growing centered tap target. One active lyric remains visible with animated text changes; tapping opens full lyrics. Normal app pages retain scrolling.

Search: each result is one keyboard-accessible button with rounded artwork and a small play overlay. Native mapping selects the largest thumbnail by pixel area. Known Google image size directives upgrade to 600px; YouTube video thumbnail filenames upgrade to maxresdefault on the frontend. Original URLs remain as fallback; if images cannot load, the placeholder remains. Actual available image quality depends on the provider.

Native autoplay: a bounded snapshot of the selected result queue is sent with playVideo. MusicEngine handles Media3 STATE_ENDED and selects a random other item with ShufflePolicy (one-item queues repeat; empty queues end). It reuses StreamResolver and the existing MediaSessionService; JavaScript is not needed to trigger autoplay in the background. UI status changes update the active track metadata, cover, title and lyrics when the app is visible or reopened. Metadata-only changes do not trigger a second play call. Stop clears the queue. Native extraction failures remain visible rather than silently looping through the queue.

Checks passed: Java compile/run of ShufflePolicy empty/single/multiple queue scenarios; JavaScript artwork upgrades and escaped search markup; autoplay metadata/lyric synchronization; existing proxy, scrolling and lyrics regressions; JS syntax. Full APK compilation and real-device/background playback are not verified here.

Upload all extracted contents to repository root, commit, build via Actions and update installed APK. Native sources changed, so a fresh APK build is required.
