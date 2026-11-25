package com.liskovsoft.youtubeapi.service

/**
 * SmartTubeKids fork: timing breakdown of the player data request (client requests, PoToken, player JS, n/sig
 * challenge solving, format processing). Silent unless an app sets the listener (only the kids build does).
 */
object PlayerDataTrace {
    @JvmStatic
    @Volatile
    var listener: ((String) -> Unit)? = null

    @JvmStatic
    fun log(message: String) {
        listener?.invoke(message)
    }

    @JvmStatic
    val isEnabled: Boolean
        get() = listener != null
}
