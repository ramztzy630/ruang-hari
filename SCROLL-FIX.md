# Scroll scope repair

The previous fit-player body class locked the entire Music page and hid search, library and header. Removed that global lock. Normal app pages now scroll. A dedicated native HTML dialog hosts the bounded full-screen player, closed using its X/back; leaving the dialog does not stop native music. Search and personal collection remain reachable on the page. The main card retains a single lyric subtitle that opens a separately scrollable full-lyrics dialog. Mini-player is hidden only while the full-screen player is open, and returns when it closes.

Validation: JS syntax; scroll-scope open/close regression; lyric timing/manual-scroll regressions; mini-player geometry. Browser/Android visual verification not performed. Upload the entire extracted project, rebuild APK with included Actions and update the installed app.
