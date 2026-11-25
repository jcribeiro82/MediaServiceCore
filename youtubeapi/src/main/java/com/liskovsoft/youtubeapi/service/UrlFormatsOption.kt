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
}
