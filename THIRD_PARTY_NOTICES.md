# Metrolist adaptation

Ruang Hari music search adapts the search request/filter and responsive-list renderer mapping from MetrolistGroup/Metrolist, commit e6fea3b36c68841bad21daa6252e1d69ecd3afbe.

Sources: innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt and pages/SearchPage.kt.
Upstream: https://github.com/MetrolistGroup/Metrolist
Copyright: Metrolist contributors; upstream credits ViMusic contributors for the original YouTube parsing implementation.
Modified November 9, 2026: Java implementation for Capacitor, song-only JSON response and Indonesian errors.

This combined distribution is released under GNU GPL version 3; see LICENSE. There is no warranty. Corresponding source is supplied in this archive, including native bridge and build scripts. Preserve this notice when redistributing.

Native playback (added November 9, 2026): files under native/android/com/ruanghari/app/ (StreamResolver.kt, MusicEngine.kt, ChunkedDataSource.kt, ResolvedStream.kt, the plugin methods in MetrolistMusicPlugin.java), native/android/com/ruanghari/app/potoken/ and native/android/assets/po_token.html adapt or copy code from Metrolist (snapshot Metrolist-main, 2026-11-02): app/.../utils/InnerTubeXPlayer.kt, app/.../utils/potoken/*, app/.../playback/StreamUrlCache.kt and the data source pattern in MusicService.kt, plus createClient() from innertube/InnerTube.kt. Changes: package renamed, Metrolist-internal dependencies (YouTube object, BuildConfig, DataStore, Timber in resolver) removed, ChunkedDataSource added.

The build fetches the InnerTubeX library (com.github.MetrolistGroup.innertubex:innertubex, v0.11.2) from JitPack; see its repository for its own license and notices. Other dependencies: Ktor, OkHttp, kotlinx.coroutines, Timber, AndroidX Media3 (Apache-2.0).

On Android, online playback uses the InnerTubeX stream resolver and the Media3 engine described above. The visible YouTube embedded player remains as a fallback and is the only online playback path in the web version. No background service, account login or downloads are implemented. Music availability depends on YouTube and the rights holder. Local music remains available through the existing player.
