package com.spizganed.quickbuds.ui

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.PacketLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Release lookup for the in-app updater, shared by [UpdateActivity] and the check on start.
 * The updater needs the release's `.apk` asset; the `.aab` alone is invisible to it.
 */
object UpdateChecker {

    private const val API = "https://quickbuds-report.spizganed.workers.dev/latest"
    private const val GITHUB = "https://api.github.com/repos/spizganed/QuickBuds/releases?per_page=30"

    /** Settings › App › Check for updates (default on). */
    const val KEY_AUTO = "updateAutoCheck"
    private const val KEY_LAST_CHECK = "updateLastCheck"
    private const val KEY_ANNOUNCED = "updateAnnounced"

    /** The check interval, for the in-app check and the background job. */
    const val INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val JOB_ID = 4301
    private const val CHANNEL_ID = "QuickBuds_Update"
    private const val NOTIFICATION_ID = 4301

    /** An in-app fetch is running, so a second resume does not start another one. */
    @Volatile
    private var checking = false

    data class Release(val tag: String, val apkUrl: String?, val notes: String)

    /** True when the switch is on and the last successful check is at least [INTERVAL_MS] old. */
    fun shouldCheck(auto: Boolean, lastSuccess: Long, now: Long): Boolean =
        auto && now - lastSuccess >= INTERVAL_MS

    /** True when [tag] is newer than [installed], has an APK, and was not announced. */
    fun shouldAnnounce(installed: String, tag: String, apkUrl: String?, announced: String?): Boolean =
        apkUrl != null && isNewer(tag, installed) && announced != tag

    /** A successful fetch records [now]; a failed fetch leaves the previous time. */
    fun lastCheckAfterFetch(success: Boolean, now: Long, previous: Long): Long =
        if (success) now else previous

    fun installed(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        com.spizganed.quickbuds.bluetooth.PacketLogger.error("installed version", e)
        "0"
    }

    /** The body of [url] with [accept]. It throws on a network failure or a status other than 2xx. Blocking. */
    private fun get(url: String, accept: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.setRequestProperty("Accept", accept)
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Blocking; call off the main thread. It asks the Worker first. When the Worker fails or its newest
     * release is not for Android, it asks GitHub once, the old path.
     */
    fun fetch(): Release {
        runCatching { release(JSONObject(get(API, "application/json"))) }.getOrNull()?.let { return it }
        // Newest first. Desktop releases (`desktop-v*`) and pre-releases share the repo; skip them.
        val list = JSONArray(get(GITHUB, "application/vnd.github+json"))
        for (i in 0 until list.length()) {
            runCatching { release(list.getJSONObject(i)) }.getOrNull()?.let { return it }
        }
        throw IllegalStateException("No Android release")
    }

    /** The Android release in [json], or null when the tag is not an app build or has no `.apk`. */
    private fun release(json: JSONObject): Release? {
        val tag = json.optString("tag_name")
        if (!tag.startsWith("v") || json.optBoolean("prerelease")) return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it).optString("browser_download_url") }
            .firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            ?: return null
        return Release(tag, apk, json.optString("body").takeIf { it != "null" }.orEmpty())
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

    /** On a cold start the last update's download (UpdateActivity, ~2.5 MB) is installed or abandoned: delete it. */
    fun clearDownload(c: Context) {
        java.io.File(c.cacheDir, "quickbuds-update.apk").delete()
    }

    /**
     * Runs on app open: schedules the background job, and checks at most every [INTERVAL_MS].
     * It is silent on failure and shows one dialog per new version, never again for the same tag.
     * A release that [mayShow] cannot show does not record a check, so the next resume offers it.
     */
    fun maybeAutoCheck(activity: Activity, mayShow: () -> Boolean) {
        val prefs = activity.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        val auto = prefs.getBoolean(KEY_AUTO, true)
        if (!auto) {
            cancelBackground(activity)
            return
        }
        scheduleBackground(activity)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!shouldCheck(auto, last, now)) return
        if (checking) return
        checking = true
        PacketLogger.log("UPDATE: check starts, last=$last")
        val installed = installed(activity)
        Thread {
            try {
                val release = runCatching { fetch() }.getOrElse {
                    PacketLogger.error("update fetch", it)
                    PacketLogger.log("UPDATE: fetch failed, last check unchanged")
                    return@Thread
                }
                val fetchedAt = System.currentTimeMillis()
                val announced = prefs.getString(KEY_ANNOUNCED, null)
                if (!shouldAnnounce(installed, release.tag, release.apkUrl, announced)) {
                    prefs.edit().putLong(KEY_LAST_CHECK, lastCheckAfterFetch(true, fetchedAt, last)).apply()
                    return@Thread
                }
                Handler(Looper.getMainLooper()).post {
                    // The background job can announce the same tag first.
                    if (prefs.getString(KEY_ANNOUNCED, null) == release.tag) return@post
                    // Another launch prompt is up: do not record the check, so the next resume offers this release.
                    if (activity.isFinishing || activity.isDestroyed || !mayShow()) {
                        PacketLogger.log("UPDATE: announcement postponed for ${release.tag}")
                        return@post
                    }
                    prefs.edit().putString(KEY_ANNOUNCED, release.tag).putLong(KEY_LAST_CHECK, fetchedAt).apply()
                    ConfirmDialog.show(
                        activity,
                        activity.getString(R.string.update_found_title, release.tag.removePrefix("v")),
                        activity.getString(R.string.update_found_body, installed),
                        activity.getString(R.string.update_download),
                        cancelRes = R.string.update_later
                    ) {
                        activity.startActivity(
                            Intent(activity, UpdateActivity::class.java).putExtra(UpdateActivity.EXTRA_DOWNLOAD, true)
                        )
                    }
                }
            } finally {
                checking = false
            }
        }.apply { isDaemon = true }.start()
    }

    /** Keeps the background job in step with the switch: scheduled when it is on, cancelled when it is off. */
    fun syncBackground(c: Context) {
        val prefs = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTO, true)) scheduleBackground(c) else cancelBackground(c)
    }

    /** Schedules the 6 h network job once. An app open does not reset the running period. */
    fun scheduleBackground(c: Context) {
        val scheduler = c.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(INTERVAL_MS)
            .build()
        val result = runCatching { scheduler.schedule(job) }.getOrElse {
            PacketLogger.error("update job schedule", it)
            -1
        }
        PacketLogger.log("UPDATE: job schedule result=$result")
    }

    fun cancelBackground(c: Context) {
        c.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        PacketLogger.log("UPDATE: job cancelled")
    }

    /** One background check. Blocking; the job runs it off the main thread. */
    fun runBackground(c: Context) {
        val prefs = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO, true)) return
        val release = runCatching { fetch() }.getOrElse {
            PacketLogger.error("update job fetch", it)
            return
        }
        // The switch can turn off while the fetch runs.
        if (!prefs.getBoolean(KEY_AUTO, true)) return
        val fetchedAt = System.currentTimeMillis()
        val installed = installed(c)
        val announced = prefs.getString(KEY_ANNOUNCED, null)
        PacketLogger.log("UPDATE: job fetched ${release.tag}")
        if (shouldAnnounce(installed, release.tag, release.apkUrl, announced)) {
            if (notifyRelease(c, release)) {
                prefs.edit().putString(KEY_ANNOUNCED, release.tag).putLong(KEY_LAST_CHECK, fetchedAt).apply()
            }
            // No notification permission: post nothing and record nothing, so the in-app check announces it.
        } else {
            prefs.edit().putLong(KEY_LAST_CHECK, fetchedAt).apply()
        }
    }

    /** Posts one notification for [release]. It returns false when Android 13+ has no notification permission. */
    private fun notifyRelease(c: Context, release: Release): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            PacketLogger.log("UPDATE: no notification permission, the in-app check announces ${release.tag}")
            return false
        }
        val manager = c.getSystemService(NotificationManager::class.java) ?: return false
        // A blocked app or channel drops the notification: record nothing, so the in-app check announces it.
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            PacketLogger.log("UPDATE: notifications blocked, the in-app check announces ${release.tag}")
            return false
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                c.getString(R.string.update_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { setShowBadge(false) }
        )
        val version = release.tag.removePrefix("v")
        val tap = PendingIntent.getActivity(
            c, 0, Intent(c, UpdateActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.update_found_title, version))
            .setContentText(c.getString(R.string.update_available, version))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        PacketLogger.log("UPDATE: notification posted for ${release.tag}")
        return true
    }
}
