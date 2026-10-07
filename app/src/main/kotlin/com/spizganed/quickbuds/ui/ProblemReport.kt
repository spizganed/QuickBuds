package com.spizganed.quickbuds.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The problem report: a Google Form (`formResponse`, no sign-in), filled by [ReportActivity].
 * The desktop app posts to the same form (desktop/src/report.rs). A changed form question gets a new id.
 */
object ProblemReport {

    private const val FORM = "https://docs.google.com/forms/d/e/1FAIpQLScPNdmogyip2EDWZayNUSW3ElYz674GAC7t7BZuFNHgvxGP_Q/formResponse"
    private const val ENTRY_MODEL = "entry.849813820"
    private const val ENTRY_CATEGORY = "entry.494675210"
    private const val ENTRY_DESCRIPTION = "entry.1786789589"
    private const val ENTRY_LOG = "entry.863055756"

    /** Google answers 413 somewhere between 31 and 41 KB of form body. */
    private const val MAX_BODY = 30_000

    /** The form's Category options, in the form's own words (it rejects any other value). */
    val CATEGORIES = listOf("UI", "Lag", "Connection", "Feature not working", "Battery", "Other")

    /** The log's first line: phone, Android, QuickBuds, buds model and firmware. Exported logs start with it too. */
    fun header(c: Context): String {
        val p = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        val model = ModelCatalog.current(c)?.name ?: p.getString(ModelCatalog.KEY_DEVICE_NAME, null) ?: "unknown buds"
        val id = p.getString(Capabilities.KEY_PRODUCT_ID, null) ?: "?"
        val fw = p.getString(BudsConnectionManager.KEY_FIRMWARE, null) ?: "?"
        return "QuickBuds ${UpdateChecker.installed(c)} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | $model ($id) firmware $fw"
    }

    /** The log as sent: header, then the packet log without Bluetooth addresses or this phone's name. */
    fun log(c: Context): String {
        var text = header(c) + "\n" + PacketLogger.getFileContent()
        text = MAC.replace(text, "XX:XX:XX:XX:XX:XX")
        for (name in phoneNames(c)) text = text.replace(name, "[this phone]")
        return text
    }

    private val MAC = Regex("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

    /** This phone's own names: the device name and the Bluetooth name (null without the permission). */
    private fun phoneNames(c: Context): List<String> = listOfNotNull(
        runCatching { Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        runCatching { c.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
    ).filter { it.length >= 3 }.distinct()

    /**
     * The form body. A log over [MAX_BODY] loses its oldest lines (the header stays): the newest part shows the
     * problem. [categories] are [CATEGORIES] values.
     */
    fun body(model: String, categories: List<String>, description: String, log: String?): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val fields = listOf(ENTRY_MODEL to model, ENTRY_DESCRIPTION to description) + categories.map { ENTRY_CATEGORY to it }
        val head = fields.joinToString("&") { "${it.first}=${enc(it.second)}" }
        if (log == null) return head
        val lines = log.lines()
        var from = 1
        while (true) {
            val kept = (listOf(lines[0]) + lines.drop(from)).joinToString("\n")
            val body = "$head&$ENTRY_LOG=${enc(kept)}"
            if (body.length <= MAX_BODY || from >= lines.size) return body
            // Drop a tenth of what is left: a few passes, not one per line.
            from += maxOf(1, (lines.size - from) / 10)
        }
    }

    /** [body] as the person reads it before Send: one block per question, the log last. */
    fun preview(body: String): String {
        val names = mapOf(ENTRY_MODEL to "Model", ENTRY_CATEGORY to "Category", ENTRY_DESCRIPTION to "Description", ENTRY_LOG to "Log")
        return body.split('&').joinToString("\n\n") {
            val (k, v) = it.split('=', limit = 2)
            names.getValue(k) + ":\n" + java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    /** Posts [body]. Network thread only. True when Google took it. */
    fun send(body: String): Boolean {
        val conn = URL(FORM).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) PacketLogger.log("REPORT: form answered $code")
            code == 200
        } catch (e: Exception) {
            PacketLogger.error("report send", e)
            false
        } finally {
            conn.disconnect()
        }
    }
}
