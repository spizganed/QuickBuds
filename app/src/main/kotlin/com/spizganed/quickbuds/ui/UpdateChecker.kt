package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.spizganed.quickbuds.R
import org.json.JSONObject
import java.io.File

/**
 * GitHub release lookup for the in-app updater, shared by [UpdateActivity] and the check on start.
 * The updater needs the release's `.apk` asset; the `.aab` alone is invisible to it (CLAUDE.md).
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/spizganed/QuickBuds/releases/latest"

    /** Settings › App › Check on start (default on). */
    const val KEY_AUTO = "updateAutoCheck"
    private const val KEY_LAST_CHECK = "updateLastCheck"
    private const val KEY_ANNOUNCED = "updateAnnounced"
    private const val MIN_INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Release(val tag: String, val apkUrl: String?, val notes: String)

    fun installed(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /** Blocking; call off the main thread. */
    fun fetch(): Release {
        val conn = java.net.URL(API).openConnection() as java.net.HttpURLConnection
        val body = try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        val assets = json.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0))
            .map { assets!!.getJSONObject(it).optString("browser_download_url") }
            .firstOrNull { it.endsWith(".apk", ignoreCase = true) }
        return Release(json.getString("tag_name"), apk, json.optString("body").takeIf { it != "null" }.orEmpty())
    }

    /** Numeric compare of "v2.1.0" against "2.1.0-debug"; suffixes are ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").substringBefore('-').split('.')
        val b = local.trim().removePrefix("v").substringBefore('-').split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** Streams [url] into [dest], reporting 0..100 (or -1 when the size is unknown). Blocking. */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Check on start: at most every 12 h, silent on any failure, and one dialog per new version,
     * never again for the same tag. It must never nag.
     */
    fun maybeAutoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO, true)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < MIN_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        val installed = installed(activity)
        Thread {
            val release = runCatching { fetch() }.getOrNull() ?: return@Thread
            if (release.apkUrl == null || !isNewer(release.tag, installed)) return@Thread
            if (prefs.getString(KEY_ANNOUNCED, null) == release.tag) return@Thread
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                prefs.edit().putString(KEY_ANNOUNCED, release.tag).apply()
                ConfirmDialog.show(
                    activity,
                    activity.getString(R.string.update_found_title, release.tag.removePrefix("v")),
                    activity.getString(R.string.update_found_body, installed),
                    activity.getString(R.string.update_found_view),
                    cancelRes = R.string.update_later
                ) { activity.startActivity(Intent(activity, UpdateActivity::class.java)) }
            }
        }.apply { isDaemon = true }.start()
    }
}
