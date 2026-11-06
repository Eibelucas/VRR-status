package de.luca.mitfahrt.data

import java.net.URI

/** Official NEW map. No undocumented vehicle-to-EFA-trip matching or location upload. */
object NewLiveMap {
    const val URL = "https://livebus.new.de/"

    fun isInternal(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host.equals("livebus.new.de", true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)

    fun isExternalLink(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}
