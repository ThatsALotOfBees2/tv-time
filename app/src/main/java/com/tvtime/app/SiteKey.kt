package com.tvtime.app

import java.util.Locale

/**
 * A rough "which site is this" key: "www.streamex.hn" -> "streamex", "player.streamex.to" ->
 * "streamex", "foo.co.uk" -> "foo". Mirrors of the same site on other domains get the same key.
 * Used by the redirect guard: the page may only navigate to sites you chose.
 */
object SiteKey {
    private val TWO_PART_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.nz", "com.br", "com.ar",
        "com.mx", "co.in", "co.jp", "co.za", "com.tr", "com.cn", "com.hk", "com.sg",
    )

    fun of(host: String?): String {
        if (host.isNullOrEmpty()) return ""
        val labels = host.lowercase(Locale.ROOT).trimEnd('.').split('.')
        if (labels.size < 2) return labels[0]
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_PART_SUFFIXES && labels.size >= 3) labels[labels.size - 3]
        else labels[labels.size - 2]
    }
}
