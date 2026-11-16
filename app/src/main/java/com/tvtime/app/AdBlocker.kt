package com.tvtime.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network-level ad blocker.
 *
 * Every request the WebView makes (including those from video-player iframes on other
 * servers) goes through [shouldBlock]. A request is blocked when its host, or any parent
 * domain of it, is on the block list, or when its URL matches one of a few well-known
 * pop-under script patterns.
 *
 * The list = built-in assets/adblock_hosts.txt + a large community hosts list downloaded
 * in the background (refreshed weekly and cached, so it also works offline).
 */
class AdBlocker(private val context: Context) {

    @Volatile
    private var hosts: Set<String> = emptySet()

    val blockedCount = AtomicInteger(0)

    /** Sites that must never be blocked (the home site and its mirrors). */
    @Volatile
    var neverBlockKeys: Set<String> = emptySet()

    private val urlPatterns = listOf(
        "/popunder", "popunder.js", "/pop.js", "/popads", "/pu.js",
        "/ads.js?", "/adsbygoogle", "/pagead/", "/afu.php", "/ad_status.js",
        "/prebid", "/push-notifications", "/sw-push", "/onclick.js",
    )

    fun load() {
        Thread {
            val set = HashSet<String>(120_000)
            try {
                context.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
                    lines.forEach { parseLine(it)?.let(set::add) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "built-in list failed", e)
            }
            hosts = HashSet(set)

            val cache = File(context.filesDir, "remote_hosts.txt")
            if (cache.exists()) {
                readFile(cache, set)
                hosts = HashSet(set)
            }
            val weekMs = 7L * 24 * 60 * 60 * 1000
            if (!cache.exists() || System.currentTimeMillis() - cache.lastModified() > weekMs) {
                if (download(cache)) {
                    readFile(cache, set)
                    hosts = HashSet(set)
                }
            }
            Log.i(TAG, "ad block list ready: ${hosts.size} hosts")
        }.apply { isDaemon = true; name = "adblock-loader" }.start()
    }

    private fun readFile(file: File, into: MutableSet<String>) {
        try {
            file.bufferedReader().useLines { lines -> lines.forEach { parseLine(it)?.let(into::add) } }
        } catch (e: Exception) {
            Log.w(TAG, "reading cached list failed", e)
        }
    }

    private fun download(target: File): Boolean {
        for (src in REMOTE_LISTS) {
            try {
                val conn = URL(src).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode != 200) continue
                val tmp = File(target.parentFile, target.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                if (tmp.length() > 10_000 && tmp.renameTo(target)) return true
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $src", e)
            }
        }
        return false
    }

    /** Accepts "domain", "0.0.0.0 domain", "127.0.0.1 domain", "||domain^". */
    private fun parseLine(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null
        val hash = line.indexOf('#')
        if (hash > 0) line = line.substring(0, hash).trim()
        if (line.startsWith("||")) line = line.removePrefix("||").substringBefore('^').substringBefore('/')
        val parts = line.split(Regex("\\s+"))
        val host = (if (parts.size >= 2) parts[1] else parts[0]).lowercase(Locale.ROOT)
        if (host == "localhost" || host == "0.0.0.0" || host.endsWith(".local") || !host.contains('.')) return null
        return host
    }

    fun shouldBlock(url: Uri): Boolean {
        val host = url.host?.lowercase(Locale.ROOT) ?: return false
        if (siteKey(host) in neverBlockKeys) return false
        if (isBlockedHost(host)) return true
        val path = (url.encodedPath ?: "") + (url.encodedQuery?.let { "?$it" } ?: "")
        val lower = path.lowercase(Locale.ROOT)
        return urlPatterns.any { lower.contains(it) }
    }

    fun isBlockedHost(host: String): Boolean {
        val set = hosts
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.length - 1) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false
        }
    }

    fun emptyResponse(): WebResourceResponse {
        blockedCount.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    companion object {
        private const val TAG = "TvTimeAdBlock"

        private val REMOTE_LISTS = listOf(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
        )

        private val TWO_PART_SUFFIXES = setOf(
            "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.nz", "com.br", "com.ar",
            "com.mx", "co.in", "co.jp", "co.za", "com.tr", "com.cn", "com.hk", "com.sg",
        )

        /**
         * A rough "which site is this" key: "www.streamex.hn" -> "streamex",
         * "player.streamex.to" -> "streamex", "foo.co.uk" -> "foo".
         * Used so mirrors of the same site on other domains are treated as the same site.
         */
        fun siteKey(host: String?): String {
            if (host.isNullOrEmpty()) return ""
            val labels = host.lowercase(Locale.ROOT).trimEnd('.').split('.')
            if (labels.size < 2) return labels[0]
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in TWO_PART_SUFFIXES && labels.size >= 3) labels[labels.size - 3]
            else labels[labels.size - 2]
        }
    }
}
