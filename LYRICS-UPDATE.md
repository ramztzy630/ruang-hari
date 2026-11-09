# Album artwork and lyrics update

Based on the uploaded, working Ruang-Hari-Proxy-Fix-09-11d-25.zip. The native stream resolver and audio engine are unchanged.

The Sound of the day card now follows the selected online track. It displays the search result artwork, title, artist and album, with native playback controls and seek. LRCLIB supplies synchronized or plain lyrics; timed lines are highlighted from native player position, and tapping a line seeks to its timestamp. Instrumental, missing lyrics and failed-request states are explicit. Requests for an old track cannot replace a new track's lyrics.

Install the entire extracted archive at the repository root. Rebuild via the included GitHub Actions workflow, then update the installed APK. The new native LyricsClient.java and plugin lyrics() method require rebuilding; copying only web files is insufficient.

Validation: npm run check, node scripts/check-music-proxy.cjs, node scripts/check-lyrics.cjs. Android compile/device verification is not available locally. Cover/album and lyric availability depend on provider metadata. Exact track/artist matching and duration checks are used on search fallback to avoid choosing arbitrary lyrics.
