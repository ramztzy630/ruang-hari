package com.ruanghari.app

/** Hasil resolusi stream audio dari InnerTubeX (satu lagu). */
class ResolvedStream(
    val url: String,
    val headers: Map<String, String>,
    val clientName: String,
    val mimeType: String,
    val bitrate: Long,
    val contentLength: Long,
    val expiresAtMs: Long,
    val requireBoundedRange: Boolean,
    val rangeChunkSizeBytes: Long,
    val useRangeChunks: Boolean,
    val title: String?,
    val author: String?,
    val durationMs: Long,
)
