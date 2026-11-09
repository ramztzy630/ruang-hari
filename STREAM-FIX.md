# Stream loading repair — 9 November 2026

Verified by source inspection: JS polling was disabled while playVideo waited. Network calls allowed 60-second reads, no OkHttp call deadline and no Ktor HttpTimeout. Player buffering had no independent deadline. These defects can hide failure stages; they do not by themselves prove why YouTube rejected or delayed a particular device request.

Changes: live resolving/token/extracting/buffering status, 30-second independent resolver deadline with single terminal callback, canceled previous resolver on stop/new track, bounded per-request Ktor/OkHttp IO, short BotGuard deadlines, 20-second audio-buffer deadline and player error detail in UI/Logcat. No new external resolver server or API key is required.

Install: upload all extracted files at repository root, preserving existing app data. Use included workflow, build and update installed APK. Native playback needs device verification; successful Actions compilation alone does not prove runtime playback.

If playback fails, record the complete message now displayed below the song title. Android Logcat tag RuangStream contains detailed causes. No logs, cookies or tokens are transmitted to a diagnostic server.
