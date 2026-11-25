package com.liskovsoft.youtubeapi.service

/**
 * SmartTubeKids fork option (off by default, so regular builds are unchanged): when on, the player data is first
 * requested with a client that returns direct per-format URLs (cacheable DASH instead of SABR), then the normal
 * client chain is used if that fails.
 */
object UrlFormatsOption {
    @JvmStatic
    @Volatile
    var isEnabled: Boolean = false

    /**
     * Called after each attempt: client name, whether the formats have URLs, request time in ms
     */
    @JvmStatic
    @Volatile
    var listener: ((String, Boolean, Long) -> Unit)? = null

    /**
     * The URL client gets this long, then the normal client chain is used
     */
    @JvmStatic
    @Volatile
    var timeoutMs: Long = 5_000

    /**
     * Recent/prefetched player data is reused for this long (the media URLs stay valid for hours)
     */
    @JvmStatic
    @Volatile
    var recentTtlMs: Long = 20 * 60 * 1000L

    /**
     * Fetches the player data of a likely next video in the background (blocking: call off the main thread)
     */
    @JvmStatic
    fun prefetch(videoId: String) {
        com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper.prefetch(videoId)
    }
}
