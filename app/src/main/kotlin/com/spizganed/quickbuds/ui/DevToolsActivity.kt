package com.spizganed.quickbuds.ui

import android.Manifest
import com.spizganed.quickbuds.QuickBudsApp
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.LogDecoder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dev Tools screen.
 *
 * Shows two views of the PacketLogger log:
 *   - Human-readable: decoded packet descriptions (e.g. "L=EAR R=OUT Case=CASE", "ANC -> Deep")
 *   - Raw hex: the original timestamped log lines as written to packets.log
 *
 * Also provides Clear / Export of the log, Reconnect / Disconnect and the crash logger test.
 * The layout, screenshot and widget reports were removed 2026-09-27: adb covers them.
 */
class DevToolsActivity : Activity() {

    private companion object {
        /** Folder created under Download for exported logs. */
        const val EXPORT_DIR_NAME = "QuickBudsLogs"

        val DIRECTION = Regex("\\b(TX|RX)\\b")
    }

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tabs: AncSegmentedView

    private val handler = Handler(Looper.getMainLooper())

    private var isHumanTab = true

    /** Line count at the last paint — lets refreshLog() skip no-op rebuilds. */
    private var lastLineCount = -1

    /** Polling task that refreshes the log every 500ms. */
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshLog()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        // Same frame as Settings: screen, title, then cards. Labels stay English by design.
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.action_dev_tools))

        tabs = AncSegmentedView(this, listOf("Human-readable", "Raw hex")).apply {
            selected = 0
            onSegmentTapped = { i -> if (i == 0) switchToHumanTab() else switchToRawTab() }
        }
        root.addView(tabs, spaced(dp(12f)))

        val actions = SettingRowFactory.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), 0, dp(4f), 0)
        }
        actions.addView(action(R.drawable.ic_update, "Export") { if (checkStoragePermission()) exportLog() })
        actions.addView(action(R.drawable.ic_delete, "Clear") {
            PacketLogger.clear()
            logText.text = ""
            lastLineCount = 0
        })
        // Connection controls go through the service actions the rest of the app already uses:
        // this screen does not bind BudsService.
        actions.addView(action(R.drawable.ic_earbud, "Reconnect") {
            startService(
                Intent(this, BudsService::class.java)
                    .setAction(BudsService.ACTION_FORCE_CONNECT)
                    .putExtra(BudsService.EXTRA_WITH_AUDIO, true)
            )
            showInLog("reconnect requested (FORCE_CONNECT)")
        })
        actions.addView(action(R.drawable.ic_power, "Disconnect") {
            startService(Intent(this, BudsService::class.java).setAction(BudsService.ACTION_FORCE_DISCONNECT))
            showInLog("disconnect requested (FORCE_DISCONNECT)")
        })
        // Proves the crash logger catches a crash before any app logic: arms a throw at the
        // very start of the next launch (QuickBudsApp.attachBaseContext), then closes the app.
        actions.addView(action(R.drawable.ic_warning, "Crash test") {
            ConfirmDialog.show(
                this, "Crash test?",
                "The app closes now and crashes once on its next launch, to test the crash logger.",
                "Crash test"
            ) {
                getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(QuickBudsApp.PREF_CRASH_ON_LAUNCH, true).commit()
                Toast.makeText(this, "Reopen the app: it crashes once at launch", Toast.LENGTH_LONG).show()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        })
        root.addView(actions, spaced(dp(12f)))

        logText = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2f).toFloat(), 1f)
            setTextColor(ThemeRes.color(this@DevToolsActivity, R.attr.appColorTextPrimary))
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        scroll = ScrollView(this).apply { addView(logText) }
        val logCard = SettingRowFactory.card(this).apply { addView(scroll) }
        root.addView(logCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12f)
        })
        setContentView(root)

        // Long press copies the visible tab; selection stays on for partial copies.
        logText.setOnLongClickListener {
            val body = logText.text?.toString().orEmpty()
            if (body.isBlank()) {
                Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val label = if (isHumanTab) "QuickBuds Log (human)" else "QuickBuds Log (raw)"
            cm.setPrimaryClip(ClipData.newPlainText(label, body))
            val tabName = if (isHumanTab) "human-readable" else "raw hex"
            Toast.makeText(
                this,
                "Copied ${body.count { it == '\n' }} lines ($tabName)",
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        refreshLog()
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    /** One action in the actions card: accent icon over a short label, equal width. */
    private fun action(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(68f), 1f)
            background = ThemeRes.ripple(this@DevToolsActivity)
            setOnClickListener { onClick() }
            addView(ImageView(this@DevToolsActivity).apply {
                setImageDrawable(ThemeRes.tint(this@DevToolsActivity, iconRes, ThemeRes.color(this@DevToolsActivity, R.attr.appColorAccent)))
                layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
            })
            addView(TextView(this@DevToolsActivity).apply {
                text = label
                textSize = 11.5f
                maxLines = 1
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setTextColor(ThemeRes.color(this@DevToolsActivity, R.attr.appColorTextPrimary))
                setPadding(0, dp(5f), 0, 0)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshTask)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
    }

    private fun switchToHumanTab() {
        isHumanTab = true
        lastLineCount = -1
        tabs.selected = 0
        refreshLog()
    }

    private fun switchToRawTab() {
        isHumanTab = false
        lastLineCount = -1
        tabs.selected = 1
        refreshLog()
    }

    /**
     * Shows a one-off diagnostic message in the log pane.
     *
     * Used by Reconnect / Disconnect, which are not packets and therefore have
     * nowhere else to report to. It writes to the on-screen TextView only — NOT
     * through PacketLogger, because injecting non-packet text into the packet
     * timeline would corrupt a capture, and the log file is the thing being used
     * to reason about the protocol.
     *
     * The result is only readable while the human tab is selected, so this checks
     * first rather than silently writing into a hex view.
     */
    private fun showInLog(message: String) {
        if (!isHumanTab) switchToHumanTab()
        logText.append("\n[dev] $message\n")
        lastLineCount = PacketLogger.getLines().size
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun refreshLog() {
        val lines = PacketLogger.getLines()
        // Nothing new since the last paint — don't rebuild or move the scroll.
        if (lines.size == lastLineCount) return
        lastLineCount = lines.size

        // Follow only if the user is already at the bottom.
        val wasAtBottom = !scroll.canScrollVertically(1)

        val sb = StringBuilder()
        for (line in lines) {
            val decoded = LogDecoder.decode(line)
            val display: String
            if (isHumanTab) {
                // Human-readable: timestamp + direction + description
                val dirMarker = when (decoded.direction) {
                    LogDecoder.Direction.TX -> "\u2192 TX${if (decoded.label != null) "[${decoded.label}]" else ""} "
                    LogDecoder.Direction.RX -> "\u2190 RX${if (decoded.label != null) "[${decoded.label}]" else ""} "
                    LogDecoder.Direction.STATUS -> ""
                }
                display = "${LogDecoder.displayTime(decoded.rawTimestamp)}  $dirMarker${decoded.description}"
            } else {
                // Raw: show the original line as-is
                display = line
            }
            sb.append(display).append("\n")
        }
        logText.text = accentDirections(sb)
        if (wasAtBottom) {
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun checkStoragePermission(): Boolean {
        // On Android 10+ we can write to app-specific external dir without permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1002)
            return false
        }
        return true
    }

    private fun exportLog() {
        try {
            val content = PacketLogger.getFileContent()
            if (content.isEmpty()) {
                Toast.makeText(this, "Nothing to export yet (log is empty).", Toast.LENGTH_LONG).show()
                return
            }
            val name = "packets_export_${stamp()}.log"
            val where = writeToDownloads(name, content)
            Toast.makeText(this, "Exported to\n$where", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * Writes the export into Download/QuickBudsLogs/ so it is visible to file managers
     * and USB/MTP, and reachable over adb.
     *
     * Why MediaStore rather than File(): on Android 10+ the shared Download directory is
     * only writable through MediaStore for apps that do not hold All-Files-Access. Writing
     * it as a plain File fails silently or throws on 11+. The sibling
     * /storage/emulated/0/QuickBudsLogs/ some file managers show is NOT reliably writable
     * for the same reason, so Download/QuickBudsLogs is the dependable location.
     *
     * Returns a human-readable location for the toast.
     */
    private fun writeToDownloads(name: String, content: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR_NAME)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore refused the export entry")
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: throw IllegalStateException("Could not open $name for writing")
            return "Download/$EXPORT_DIR_NAME/$name"
        }

        // Legacy path (API < 29): plain files, permission already checked by caller.
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            EXPORT_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Could not create ${dir.absolutePath}")
        }
        File(dir, name).writeText(content)
        return "${dir.absolutePath}/$name"
    }

    /** TX / RX markers in the accent colour, so the two directions read apart at a glance. */
    private fun accentDirections(text: CharSequence): SpannableString {
        val out = SpannableString(text)
        val accent = ThemeRes.color(this, R.attr.appColorAccent)
        for (m in DIRECTION.findAll(text)) {
            out.setSpan(ForegroundColorSpan(accent), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
