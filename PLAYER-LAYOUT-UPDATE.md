# Player layout refinement

Based on the supplied Ruang-Hari-Background-Player-Fix.zip.

Changes: active lyrics use bold white text on a transparent background; other lines use muted gray. Cover is 156–220 CSS px, with 150 px on short screens. Title/artist/album/seek/control spacing is compact. Previous/next controls have 48 px touch targets; play/pause is 58 px. The lyric viewport is 240–380 px with natural wrapped lines and no active card box.

Auto-scroll now calculates the target from the row and container rectangles, centers within the lyric scroller, clamps to bounds and animates only when needed. A manual wheel/touch/keyboard scroll pauses following for four seconds; touch dragging remains paused until release. Following resumes even without a new lyric timestamp. Reduced-motion preference uses instant scrolling.

Native music, background service, mini-player dragging and lyrics provider files are unchanged. Upload all extracted contents to repository root, commit, build in GitHub Actions and install as an update.

Validation passed: JavaScript syntax; LRC parser/stale request tests; auto-scroll center/bounds/manual pause/same-line resume/reduced-motion tests; mini-player navigation geometry; Capacitor Proxy regression. Browser visual rendering was attempted but unavailable because the environment blocked Chromium process sockets. Actual Android visual/device verification is still needed.
