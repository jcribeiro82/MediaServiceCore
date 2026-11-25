package com.liskovsoft.youtubeapi.service.internal

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.PoTokenGate
import com.liskovsoft.youtubeapi.app.ReloadPlaybackGate
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.innertube.InnertubeService
import com.liskovsoft.youtubeapi.service.UrlFormatsOption
import com.liskovsoft.youtubeapi.service.YouTubeSignInService
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService

internal object FormatInfoWrapper {
    private enum class ProviderType { INNERTUBE, LEGACY }
    private var mCachedFormatInfo: MediaItemFormatInfo? = null
    private val mInnertubeProvider: (AppClient, String, String?) -> MediaItemFormatInfo? =
        { client, videoId, clickTrackingParams -> getFormatInfoInnertube(client, videoId, clickTrackingParams) }
    private val mLegacyProvider: (AppClient, String, String?) -> MediaItemFormatInfo? =
        { client, videoId, clickTrackingParams -> getFormatInfoLegacy(client, videoId, clickTrackingParams) }
    private val lock = Any()
    private val CLIENT_LIST = arrayOf(
        Pair(AppClient.TV, ProviderType.LEGACY), // Supports auth. Fixes "please sign in" bug! (the best for Premium users)
        Pair(AppClient.TV_SAMSUNG, ProviderType.LEGACY), // Supports auth. Still contains DASH formats
        Pair(AppClient.WEB, ProviderType.INNERTUBE), // Fix video clip blocked in current location
        Pair(AppClient.VISIONOS, ProviderType.LEGACY), // no url formats
        Pair(AppClient.TV_DOWNGRADED, ProviderType.LEGACY), // works with old UAs like old Cobalt and old Xbox (non-tcl players)
        Pair(AppClient.WEB_EMBED, ProviderType.LEGACY), // Restricted (18+) videos (not working)
        //AppClient.ANDROID_REEL, // doesn't require pot and cipher (hangs on all engines)
        Pair(AppClient.WEB_SAFARI, ProviderType.LEGACY),
        Pair(AppClient.IOS, ProviderType.LEGACY),
        Pair(AppClient.GEO, ProviderType.LEGACY), // Fix video clip blocked in current location
        Pair(AppClient.MWEB, ProviderType.LEGACY), // single audio language
        //AppClient.TV_LEGACY,
        //AppClient.TV_EMBED, // single audio language
        Pair(AppClient.ANDROID_VR, ProviderType.LEGACY), // doesn't require pot and cipher (often hangs?)
        //AppClient.TV_SIMPLY, // hangs?
        //AppClient.ANDROID_SDK_LESS, // doesn't require pot (hangs on Cronet!)
    )
    // SmartTubeKids: see UrlFormatsOption (off by default). Clients that may answer with per-format URLs, in order;
    // the first one that does is kept for the session.
    private val URL_CLIENT = Pair(AppClient.ANDROID_VR, ProviderType.LEGACY)
    private val URL_CLIENTS = arrayOf(
        URL_CLIENT,
        Pair(AppClient.IOS, ProviderType.LEGACY),
        Pair(AppClient.ANDROID, ProviderType.LEGACY),
        Pair(AppClient.MWEB, ProviderType.LEGACY),
        Pair(AppClient.TV_EMBED, ProviderType.LEGACY),
        Pair(AppClient.WEB_EMBED, ProviderType.LEGACY),
        Pair(AppClient.WEB, ProviderType.INNERTUBE),
    )
    private var mUrlWinner: Pair<AppClient, ProviderType>? = null
    private var mUrlClientVideoId: String? = null // the current video came from URL_CLIENT
    private var mUrlClientSkipVideoId: String? = null // URL_CLIENT failed to play this video: normal chain
    private var mUrlClientFailures = 0 // in a row
    private var mUrlClientPausedUntilMs = 0L // too many failures: normal chain for a while
    // SmartTubeKids: recent and prefetched player data from URL_CLIENT (videoId -> time, info)
    private val mRecent = object : LinkedHashMap<String, Pair<Long, MediaItemFormatInfo>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, MediaItemFormatInfo>>?) = size > 6
    }
    private val mUrlClientExecutor = java.util.concurrent.Executors.newCachedThreadPool()
    private var mActualClient: Pair<AppClient, ProviderType>? = null
    private var mNextClient: Pair<AppClient, ProviderType>? = null
    private var mIsUnplayable: Boolean = false

    @JvmStatic
    fun getFormatInfo(videoId: String?, clickTrackingParams: String?): MediaItemFormatInfo? {
        if (videoId == null)
            return null

        try {
            synchronized(lock) {
                //videoId = "K04WmBtVsOs"; // the testing video: Memories of Memories

                val cachedFormatInfo = getCachedFormatInfo(videoId)

                if (cachedFormatInfo != null) {
                    return cachedFormatInfo
                }

                checkSigned()

                val urlInfo = if (UrlFormatsOption.isEnabled && videoId != mUrlClientSkipVideoId)
                    recentInfo(videoId) ?: urlFormatsInfo(videoId, clickTrackingParams, false) else null
                mUrlClientVideoId = if (urlInfo != null) videoId else null
                val formatInfo = urlInfo ?: firstPlayable(videoId, clickTrackingParams)

                mIsUnplayable = formatInfo?.isUnplayable ?: true

                setCachedFormatInfo(formatInfo, clickTrackingParams)

                return formatInfo
            }
        } finally {
            ReloadPlaybackGate.reset(videoId)
        }
    }

    @JvmStatic
    fun switchNextClient(force: Boolean) {
        // SmartTubeKids: a playback error with the URL client: retry this video with the normal client chain
        if (mUrlClientVideoId != null) {
            mRecent.remove(mUrlClientVideoId!!)
            mUrlClientSkipVideoId = mUrlClientVideoId
            mUrlClientVideoId = null
            mCachedFormatInfo = null
            UrlFormatsOption.listener?.invoke(URL_CLIENT.first.name + " playback error", false, 0)
            return
        }

        // Try to reset pot cache for the last video
        if (!force && !mIsUnplayable && (mActualClient?.let { PoTokenGate.resetCache(it.first) } ?: false)) {
            return
        }

        // And last, try to switch the client
        switchNextClientInt()
    }

    @JvmStatic
    fun resetClient() {
        resetClientInt()
        PoTokenGate.resetCache()
    }

    @JvmStatic
    fun invalidateCache() {
        mCachedFormatInfo = null
    }

    @JvmStatic
    fun switchNextSubtitle() {
        getVideoInfoService().switchNextSubtitle()
    }

    @JvmStatic
    fun allowRestrictedVideos(allow: Boolean) {
        getVideoInfoService().allowRestrictedVideos(allow)
    }

    @JvmStatic
    fun syncWithAuthInfoIfNeeded(formatInfo: MediaItemFormatInfo?) {
        if (formatInfo == null) {
            return
        }

        if (shouldBeSynced(formatInfo) && !formatInfo.isSynced()) {
            val videoInfo = getVideoInfoService().getAuthVideoInfo(formatInfo.getVideoId(), formatInfo.getClickTrackingParams())
            formatInfo.sync(YouTubeMediaItemFormatInfo.from(videoInfo))
        }

        if (shouldBeSynced(formatInfo)) { // still not synced
            throw IllegalStateException("Update history error: the format should be synced first")
        }
    }

    private fun shouldBeSynced(formatInfo: MediaItemFormatInfo): Boolean {
        return !formatInfo.isAuth() && !formatInfo.isUnplayable() && getSignInService().isSigned
    }

    /**
     * SmartTubeKids: ANDROID_VR usually answers with plain URLs (no PoToken, no JS challenge). Null if it fails,
     * answers too slowly ({@link UrlFormatsOption#timeoutMs}) or keeps failing (then paused for a while).
     */
    private fun urlFormatsInfo(videoId: String, clickTrackingParams: String?, prefetch: Boolean): MediaItemFormatInfo? {
        if (System.currentTimeMillis() < mUrlClientPausedUntilMs) {
            return null
        }
        val order = listOfNotNull(mUrlWinner) + URL_CLIENTS.filter { it != mUrlWinner }
        val roundStart = System.currentTimeMillis()
        for (client in order) {
            if (prefetch && System.currentTimeMillis() - roundStart > 6_000) {
                return null // a prefetch must not keep a tap waiting: the probe goes on with the next request
            }
            val start = System.currentTimeMillis()
            var timeout = false
            val result = try {
                AppService.instance().resetClientPlaybackNonce()
                val future = mUrlClientExecutor.submit<MediaItemFormatInfo?> {
                    getProvider(client)(client.first, videoId, clickTrackingParams)
                }
                future.get(UrlFormatsOption.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                timeout = true
                null
            } catch (e: Exception) {
                null
            }
            val hasUrls = result != null && !result.isUnplayable() && (result.containsDashFormats() || result.containsUrlFormats())
            UrlFormatsOption.listener?.invoke(client.first.name + (if (prefetch) " (prefetch)" else "") + ": " +
                (if (timeout) "timeout" else describe(result)), hasUrls, System.currentTimeMillis() - start)
            if (hasUrls) {
                mUrlWinner = client
                mUrlClientFailures = 0
                mRecent[videoId] = Pair(System.currentTimeMillis(), result!!)
                return result
            }
            if (client == mUrlWinner) {
                mUrlWinner = null // stopped working: probe the others
            }
            if (mUrlWinner != null) {
                break // a known good client failed for this video only: don't probe everything again
            }
        }
        if (mUrlWinner == null || ++mUrlClientFailures >= 3) {
            mUrlClientFailures = 0
            mUrlClientPausedUntilMs = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
            UrlFormatsOption.listener?.invoke("no client with direct URLs: normal clients for 2 h", false, 0)
        }
        return null
    }

    /**
     * SmartTubeKids: why a client gave no URLs (playability reason, SABR-only, no formats)
     */
    private fun describe(info: MediaItemFormatInfo?): String {
        if (info == null) {
            return "no answer"
        }
        if (info.isUnplayable()) {
            return "unplayable: " + (info.getPlayabilityReason() ?: "?")
        }
        val adaptive = info.getAdaptiveFormats()
        val first = adaptive?.firstOrNull()
        val type = when (first?.getFormatType()) {
            com.liskovsoft.mediaserviceinterfaces.data.MediaFormat.FORMAT_TYPE_DASH -> "dash"
            com.liskovsoft.mediaserviceinterfaces.data.MediaFormat.FORMAT_TYPE_SABR -> "sabr"
            null -> "none"
            else -> "regular"
        }
        return "playable, adaptive=${adaptive?.size ?: 0} ($type), urlsOnFirst=${first?.getUrl() != null}, " +
            "progressive=${info.getUrlFormats()?.size ?: 0}, sabrUrl=${info.getServerAbrStreamingUrl() != null}"
    }

    /**
     * SmartTubeKids: player data fetched (or prefetched) in the last minutes for this video
     */
    private fun recentInfo(videoId: String): MediaItemFormatInfo? {
        val entry = mRecent[videoId] ?: return null
        if (System.currentTimeMillis() - entry.first > UrlFormatsOption.recentTtlMs) {
            mRecent.remove(videoId)
            return null
        }
        UrlFormatsOption.listener?.invoke(URL_CLIENT.first.name + " recent/prefetched", true, 0)
        return entry.second
    }

    /**
     * SmartTubeKids: fetches the player data of a likely next video in the background (URL client only)
     */
    @JvmStatic
    fun prefetch(videoId: String) {
        if (!UrlFormatsOption.isEnabled) {
            return
        }
        synchronized(lock) {
            if (videoId == mUrlClientSkipVideoId || getCachedFormatInfo(videoId) != null || recentInfoQuiet(videoId)) {
                return
            }
            urlFormatsInfo(videoId, null, true)
        }
    }

    private fun recentInfoQuiet(videoId: String): Boolean {
        val entry = mRecent[videoId] ?: return false
        return System.currentTimeMillis() - entry.first <= UrlFormatsOption.recentTtlMs
    }

    private fun firstPlayable(videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        return firstInfoWith(videoId, clickTrackingParams) { !it.isUnplayable() }
            ?: firstInfoWith(videoId, clickTrackingParams) { it.urlFormats != null } // fallback to regular formats (480p)
            ?: firstInfoWith(videoId, clickTrackingParams) { true } // fallback to error result
    }

    private fun firstInfoWith(videoId: String, clickTrackingParams: String?, infoTester: (MediaItemFormatInfo) -> Boolean): MediaItemFormatInfo? {
        val beginClient = getClient()
        var nextClient = beginClient

        do {
            // NOTE: History fix. CPN should be unique per each /player fetch
            AppService.instance().resetClientPlaybackNonce()

            val start = System.currentTimeMillis()
            val result = getProvider(nextClient)(nextClient.first, videoId, clickTrackingParams)
            if (com.liskovsoft.youtubeapi.service.PlayerDataTrace.isEnabled) {
                com.liskovsoft.youtubeapi.service.PlayerDataTrace.log("client ${nextClient.first.name}: ${describe(result)}, " +
                    "${System.currentTimeMillis() - start} ms total")
            }

            if (result != null && infoTester(result)) {
                persistRecentTypeIfNeeded(nextClient, result)
                return result
            }

            nextClient = Helpers.getNextValue(CLIENT_LIST, nextClient)
        } while (nextClient != beginClient)

        return null
    }

    private fun getProvider(client: Pair<AppClient, ProviderType>): (AppClient, String, String?) -> MediaItemFormatInfo? {
        return if (client.second == ProviderType.INNERTUBE)
            mInnertubeProvider
        else
            mLegacyProvider
    }

    private fun getFormatInfoInnertube(client: AppClient, videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        return InnertubeService.createFormatInfo(client, videoId)
    }

    private fun getFormatInfoLegacy(client: AppClient, videoId: String, clickTrackingParams: String?): MediaItemFormatInfo? {
        val videoInfo = getVideoInfoService().getVideoInfo(client, videoId, clickTrackingParams)

        val start = System.currentTimeMillis()
        val result = YouTubeMediaItemFormatInfo.from(videoInfo)
        if (com.liskovsoft.youtubeapi.service.PlayerDataTrace.isEnabled) {
            com.liskovsoft.youtubeapi.service.PlayerDataTrace.log("${client.name} formats processed in ${System.currentTimeMillis() - start} ms")
        }
        return result
    }

    private fun getCachedFormatInfo(videoId: String?): MediaItemFormatInfo? {
        return mCachedFormatInfo?.takeIf { videoId != null && it.getVideoId() == videoId && it.isCacheActual() }
    }

    private fun setCachedFormatInfo(formatInfo: MediaItemFormatInfo?, clickTrackingParams: String?) {
        if (formatInfo == null || formatInfo.isUnplayable()) {
            mCachedFormatInfo = null
            return
        }

        mCachedFormatInfo = formatInfo
        formatInfo.setClickTrackingParams(clickTrackingParams)
    }

    private fun checkSigned() {
        getSignInService().checkAuth()
    }

    private fun getSignInService(): YouTubeSignInService {
        return YouTubeSignInService.instance()
    }

    private fun getVideoInfoService(): VideoInfoService {
        return VideoInfoService.instance()
    }

    private fun getData(): MediaServiceData {
        return MediaServiceData.instance()
    }

    private fun getClient(): Pair<AppClient, ProviderType> {
        return mNextClient ?: CLIENT_LIST[0]
    }

    @JvmStatic
    fun getActualClient(): AppClient {
        return (mNextClient ?: CLIENT_LIST[0]).first
    }

    private fun switchNextClientInt() {
        mNextClient = Helpers.getNextValue(CLIENT_LIST, mActualClient)
    }

    private fun resetClientInt() {
        mNextClient = null
        mActualClient = CLIENT_LIST[0]
        persistClient()
    }

    private fun persistClient() {
        if (!GlobalPreferences.isInitialized()) {
            return;
        }

        val appClient = mActualClient?.first

        val ordinal = appClient?.ordinal ?: -1

        if (getData().clientType != ordinal)
            getData().clientType = ordinal
    }

    private fun persistRecentTypeIfNeeded(client: Pair<AppClient, ProviderType>, videoInfo: MediaItemFormatInfo?) {
        if (videoInfo == null || videoInfo.isUnplayable() || client == mActualClient) {
            return
        }

        mActualClient = client
        persistClient()
    }
}