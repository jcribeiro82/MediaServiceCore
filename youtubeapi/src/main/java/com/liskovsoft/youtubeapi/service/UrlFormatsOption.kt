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
     * Client name that gave direct URLs in a previous run (tried first), set by the app
     */
    @JvmStatic
    @Volatile
    var savedWinner: String? = null

    /**
     * Called with the client name when a (new) client gives direct URLs, so the app can remember it
     */
    @JvmStatic
    @Volatile
    var onWinner: ((String) -> Unit)? = null

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
     * True if the last player data answer was kept/prefetched data (not a fresh request)
     */
    @JvmStatic
    @Volatile
    var lastReused: Boolean = false

    /**
     * Forgets the kept player data of a video, so the next request is fresh
     */
    @JvmStatic
    fun invalidate(videoId: String?) {
        com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper.invalidate(videoId)
    }

    /**
     * Fetches the player data of a likely next video in the background (blocking: call off the main thread)
     */
    @JvmStatic
    fun prefetch(videoId: String) {
        com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper.prefetch(videoId)
    }
}
