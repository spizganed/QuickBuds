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
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spizganed.quickbuds.R
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
    }

    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnTabHuman: Button
    private lateinit var btnTabRaw: Button
    private lateinit var btnClear: Button
    private lateinit var btnExport: Button
    private lateinit var btnCrashTest: Button
    private lateinit var btnReconnect: Button
    private lateinit var btnDisconnect: Button

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
        // Theme before super.onCreate, same as every other screen. Previously this
        // screen hardcoded its own colours and never called setTheme at all, which
        // is part of why it did not match the rest of the app.
        ThemeRes.select(this)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_tools)

        logText = findViewById<TextView>(R.id.logText)
        scroll = findViewById<ScrollView>(R.id.scroll)
        btnTabHuman = findViewById<Button>(R.id.btnTabHuman)
        btnTabRaw = findViewById<Button>(R.id.btnTabRaw)
        btnClear = findViewById<Button>(R.id.btnClear)
        btnExport = findViewById<Button>(R.id.btnExport)
        btnCrashTest = findViewById<Button>(R.id.btnCrashTest)
        btnReconnect = findViewById<Button>(R.id.btnReconnect)
        btnDisconnect = findViewById<Button>(R.id.btnDisconnect)
        applyTheme()

        btnTabHuman.setOnClickListener { switchToHumanTab() }
        btnTabRaw.setOnClickListener { switchToRawTab() }
        btnClear.setOnClickListener {
            PacketLogger.clear()
            logText.text = ""
            lastLineCount = 0
        }
        // Proves the crash logger catches a crash before any app logic: arms a throw at the
        // very start of the next launch (QuickBudsApp.attachBaseContext), then closes the app.
        btnCrashTest.setOnClickListener {
            getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(QuickBudsApp.PREF_CRASH_ON_LAUNCH, true).commit()
            Toast.makeText(this, "Reopen the app: it crashes once at launch", Toast.LENGTH_LONG).show()
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        btnExport.setOnClickListener {
            if (checkStoragePermission()) {
                exportLog()
            }
        }

        // CONNECTION CONTROLS, moved here from the main screen's settings cog at his
        // request. They are connection plumbing rather than a setting, and Dev Tools
        // is where the connection is already diagnosed.
        //
        // Sent as a service ACTION rather than through a bound manager: this screen
        // does not bind BudsService, and ACTION_FORCE_CONNECT / ACTION_FORCE_DISCONNECT
        // already exist and are already handled there, so this reuses a path that is
        // known to work instead of adding a second one.
        btnReconnect.setOnClickListener {
            startService(
                Intent(this, com.spizganed.quickbuds.bluetooth.BudsService::class.java)
                    .setAction(com.spizganed.quickbuds.bluetooth.BudsService.ACTION_FORCE_CONNECT)
                    .putExtra(com.spizganed.quickbuds.bluetooth.BudsService.EXTRA_WITH_AUDIO, true)
            )
            showInLog("reconnect requested (FORCE_CONNECT)")
        }
        btnDisconnect.setOnClickListener {
            startService(
                Intent(this, com.spizganed.quickbuds.bluetooth.BudsService::class.java)
                    .setAction(com.spizganed.quickbuds.bluetooth.BudsService.ACTION_FORCE_DISCONNECT)
            )
            showInLog("disconnect requested (FORCE_DISCONNECT)")
        }

        // Long-press the log to copy its whole visible contents.
        //
        // This was previously missing: the TextView had textIsSelectable, so text
        // COULD be selected, but there was no one-gesture copy and no clipboard
        // write at all — which is why copying felt broken. Selectable stays on so
        // precise partial selection still works; long-press now copies everything
        // at once, which is what is actually wanted when pasting a capture out.
        //
        // Copies the CURRENTLY DISPLAYED tab (human-readable or raw hex), matching
        // what is on screen, and says which so the toast is self-explanatory.
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

        updateTabButtons()
        refreshLog()
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
        updateTabButtons()
        refreshLog()
    }

    private fun switchToRawTab() {
        isHumanTab = false
        lastLineCount = -1
        updateTabButtons()
        refreshLog()
    }

    /**
     * Paints the selected tab.
     *
     * Rewritten to use the shared chip drawables instead of flat background
     * colours. The old version set the active tab's background to a hardcoded
     * #CC0000, which is why the active log button rendered as red on a screen that
     * otherwise has no red in it. The drawables read the theme, so this now matches
     * the rest of the app in all three themes.
     */
    private fun updateTabButtons() {
        val activeText = ThemeRes.palette(this).onAccent
        val normalText = ThemeRes.color(this, R.attr.appColorTextPrimary)

        btnTabHuman.background = ThemeRes.chip(this, isHumanTab)
        btnTabHuman.setTextColor(if (isHumanTab) activeText else normalText)

        btnTabRaw.background = ThemeRes.chip(this, !isHumanTab)
        btnTabRaw.setTextColor(if (isHumanTab) normalText else activeText)
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
        val existing = logText.text?.toString().orEmpty()
        logText.text = existing + "\n[dev] " + message + "\n"
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
        logText.text = sb.toString()
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

    /**
     * Applies the theme to this screen.
     *
     * REWRITTEN to read the themed @color/app_* resources instead of hardcoding
     * hex values. The old version used Color.WHITE for the background on the LIGHT
     * branch and Color.BLACK for text in some paths, which is how the log screen
     * ended up with white text on a white background: two independent literals that
     * were not guaranteed to agree, and a "theme" that did not follow the palette
     * the rest of the app uses.
     *
     * Using the same resources as every other screen means this cannot drift from
     * the main screen's colours, and a palette change is one edit in one file.
     */
    private fun applyTheme() {
        val bgColor = ThemeRes.color(this, R.attr.appColorBg)
        val cardColor = ThemeRes.color(this, R.attr.appColorCard)
        val txtColor = ThemeRes.color(this, R.attr.appColorTextPrimary)

        findViewById<android.view.View>(R.id.devToolsRoot).let {
            it.setBackgroundColor(bgColor)
            ThemeRes.screenPadding(it)
        }
        findViewById<android.view.View>(R.id.devActionsCard).background = ThemeRes.card(this)
        logText.setBackgroundColor(cardColor)
        logText.setTextColor(txtColor)
        scroll.background = ThemeRes.card(this).apply { setColor(bgColor) }

        // The title is now found by ID. It used to be found BY POSITION (child 0 of
        // child 0 of the root), which only worked while this screen happened to keep
        // that exact shape — the restyle moved the buttons into their own card, so a
        // positional walk would have been one edit away from silently colouring the
        // wrong view. The id makes the binding explicit.
        findViewById<TextView>(R.id.devToolsTitle)?.setTextColor(txtColor)


        // Every action button gets the shared chip drawable. The tab buttons are NOT
        // here: updateTabButtons() repaints them, since it knows which one is selected.
        for (b in listOf(
            btnClear, btnExport, btnCrashTest,
            btnReconnect, btnDisconnect
        )) {
            b.background = ThemeRes.chip(this, false)
            b.setTextColor(txtColor)
        }

        // Re-apply active tab highlight
        updateTabButtons()
    }
}
