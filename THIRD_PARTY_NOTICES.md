# Metrolist adaptation

Ruang Hari music search adapts the search request/filter and responsive-list renderer mapping from MetrolistGroup/Metrolist, commit e6fea3b36c68841bad21daa6252e1d69ecd3afbe.

Sources: innertube/src/main/kotlin/com/metrolist/innertube/YouTube.kt and pages/SearchPage.kt.
Upstream: https://github.com/MetrolistGroup/Metrolist
Copyright: Metrolist contributors; upstream credits ViMusic contributors for the original YouTube parsing implementation.
Modified November 9, 2026: Java implementation for Capacitor, song-only JSON response and Indonesian errors.

This combined distribution is released under GNU GPL version 3; see LICENSE. There is no warranty. Corresponding source is supplied in this archive, including native bridge and build scripts. Preserve this notice when redistributing.

Online playback uses the official visible YouTube embedded player, not the Metrolist native playback engine. No background/offline YouTube playback, cipher extraction, account login or downloads are implemented. Music availability and playback depend on YouTube and the rights holder allowing embeds. Local music remains available through the existing player.
