package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.spizganed.quickbuds.R
import java.io.File

/**
 * App update: installed and latest version side by side, one action pill (Check / Download /
 * Install), a download progress bar and the release notes. The APK is handed to Android's own
 * installer through the FileProvider; the user confirms in the system dialog.
 *
 * Checks as soon as it opens: opening this screen is the request.
 */
class UpdateActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var latestText: TextView
    private lateinit var statusText: TextView
    private lateinit var action: TextView
    private lateinit var progress: ProgressBar
    private lateinit var notesLabel: TextView
    private lateinit var notesCard: LinearLayout
    private lateinit var notesText: TextView

    private var busy = false
    private var release: UpdateChecker.Release? = null
    private var downloadedApk: File? = null

    private val installed by lazy { UpdateChecker.installed(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.update_title))

        // Installed | Latest, side by side.
        fun column(labelRes: Int, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@UpdateActivity).apply {
                text = value
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(p.text)
                gravity = Gravity.CENTER
                tag = "value"
            })
            addView(TextView(this@UpdateActivity).apply {
                setText(labelRes)
                textSize = 13f
                setTextColor(p.textSecondary)
                gravity = Gravity.CENTER
            })
        }
        val latestColumn = column(R.string.update_latest, getString(R.string.update_unknown))
        latestText = latestColumn.findViewWithTag("value")
        root.addView(SettingRowFactory.card(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18f), 0, dp(18f))
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12f)
            addView(column(R.string.update_installed, installed))
            addView(View(this@UpdateActivity).apply {
                setBackgroundColor(p.outline)
                layoutParams = LinearLayout.LayoutParams(dp(1f), LinearLayout.LayoutParams.MATCH_PARENT)
            })
            addView(latestColumn)
        })

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(p.textSecondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(14f), 0, 0)
        }
        root.addView(statusText)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.outline)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8f))
                .apply { topMargin = dp(10f) }
        }
        root.addView(progress)

        action = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(p.onAccent)
            background = ThemeRes.ripple(this@UpdateActivity, ThemeRes.shape(this@UpdateActivity, p.accent, null, 22f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44f))
                .apply { topMargin = dp(14f) }
            setOnClickListener { onAction() }
        }
        root.addView(action)

        notesLabel = SettingRowFactory.sectionLabel(this, R.string.update_notes).apply { visibility = View.GONE }
        root.addView(notesLabel)
        notesText = TextView(this).apply {
            textSize = 14f
            setTextColor(p.text)
            setLineSpacing(0f, 1.15f)
        }
        notesCard = SettingRowFactory.card(this).apply {
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            visibility = View.GONE
            addView(notesText)
        }
        root.addView(notesCard)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
        check()
    }

    private fun setAction(textRes: Int, enabled: Boolean = true) {
        action.setText(textRes)
        action.isEnabled = enabled
        action.alpha = if (enabled) 1f else 0.35f
    }

    private fun onAction() {
        if (busy) return
        val r = release
        when {
            downloadedApk != null -> install()
            r?.apkUrl != null && UpdateChecker.isNewer(r.tag, installed) -> download(r.apkUrl)
            else -> check()
        }
    }

    private fun check() {
        busy = true
        statusText.setText(R.string.update_checking)
        setAction(R.string.update_check, enabled = false)
        Thread {
            val result = runCatching { UpdateChecker.fetch() }
            handler.post {
                busy = false
                val r = result.getOrElse {
                    statusText.text = getString(R.string.update_failed, it.message ?: "network error")
                    setAction(R.string.update_check)
                    return@post
                }
                release = r
                latestText.text = r.tag.removePrefix("v")
                showNotes(r.notes)
                when {
                    r.apkUrl == null -> {
                        statusText.text = getString(R.string.update_failed, "release has no .apk asset")
                        setAction(R.string.update_check)
                    }
                    UpdateChecker.isNewer(r.tag, installed) -> {
                        statusText.text = getString(R.string.update_available, r.tag.removePrefix("v"))
                        setAction(R.string.update_download)
                    }
                    else -> {
                        statusText.setText(R.string.update_up_to_date)
                        setAction(R.string.update_check)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** GitHub notes are Markdown; this strips the markers that read as noise in plain text. */
    private fun showNotes(raw: String) {
        val text = raw.lines().joinToString("\n") { line ->
            line.replace(Regex("^#+\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ").replace("**", "").replace("`", "")
        }.trim()
        notesText.text = text.ifEmpty { getString(R.string.update_no_notes) }
        notesLabel.visibility = View.VISIBLE
        notesCard.visibility = View.VISIBLE
    }

    private fun download(url: String) {
        busy = true
        setAction(R.string.update_download, enabled = false)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        statusText.text = getString(R.string.update_downloading, 0)
        Thread {
            val out = File(cacheDir, "quickbuds-update.apk")
            val result = runCatching {
                UpdateChecker.download(url, out) { pct ->
                    handler.post {
                        if (pct < 0) return@post
                        progress.isIndeterminate = false
                        progress.progress = pct
                        statusText.text = getString(R.string.update_downloading, pct)
                    }
                }
            }
            handler.post {
                busy = false
                if (result.isFailure) {
                    progress.visibility = View.GONE
                    statusText.text = getString(R.string.update_failed, result.exceptionOrNull()?.message ?: "download error")
                    setAction(R.string.update_download)
                    return@post
                }
                downloadedApk = out
                progress.isIndeterminate = false
                progress.progress = 100
                statusText.setText(R.string.update_downloaded)
                setAction(R.string.update_install)
                Haptics.commit(action)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun install() {
        val apk = downloadedApk ?: return
        if (!apk.exists() || apk.length() == 0L) {
            statusText.text = getString(R.string.update_failed, "downloaded file is missing")
            downloadedApk = null
            setAction(R.string.update_download)
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            ConfirmDialog.show(
                this, getString(R.string.update_permission_title), getString(R.string.update_permission_body),
                getString(R.string.update_permission_action)
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName")))
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
