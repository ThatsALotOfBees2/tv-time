package com.tvtime.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater. GitHub Actions publishes a release with tv-time.apk and version.json for every
 * build of the main branch. On start-up the app reads version.json; when it lists a newer
 * versionCode, the APK is downloaded and handed to Android's package installer.
 */
class Updater(private val context: Context) {

    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String)

    fun currentVersion(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Runs on a background thread. Returns a newer release, or null (also when offline). */
    fun check(): Release? {
        val url = context.getString(R.string.update_url)
        if (url.isBlank()) return null
        return try {
            val conn = open(url)
            val text = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
            val json = JSONObject(text)
            val release = Release(
                json.getLong("versionCode"),
                json.optString("versionName", json.getLong("versionCode").toString()),
                json.getString("apk"),
            )
            if (release.versionCode > currentVersion()) release else null
        } catch (e: Exception) {
            null
        }
    }

    /** Runs on a background thread. [onProgress] gets 0..100, or -1 when the size is unknown. */
    fun download(release: Release, onProgress: (Int) -> Unit): File {
        val target = File(context.cacheDir, "update.apk")
        val conn = open(release.apkUrl)
        try {
            val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            var done = 0L
            var lastPercent = -2
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download incomplete ($done of $total bytes)")
        } finally {
            conn.disconnect()
        }
        return target
    }

    /** Android 8+ asks the user once to allow TV Time to install apps. */
    fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    /** Runs on a background thread. Android then shows its "update this app?" screen. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("tv-time.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, UpdateReceiver::class.java).setPackage(context.packageName),
                flags,
            )
            session.commit(status.intentSender)
        }
    }

    // Follows GitHub's redirects to its download servers by hand (also https -> https on old Androids).
    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TV-Time-Updater")
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                u = URL(u, location)
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("HTTP $code for $u")
            }
            return c
        }
        throw IOException("Too many redirects for $url")
    }
}

/** Receives the package installer's answer and shows its confirmation screen when it asks for one. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        }
        // On success Android replaces the app and closes it; the user opens it again.
        // On failure or cancel, MainActivity notices on resume and offers to try again.
    }
}
