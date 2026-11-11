package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.ModelCatalog

/**
 * Settings › Feedback ([ProblemReport]): buds model (filled in, with the model list as suggestions),
 * categories, a description and the log. See the report shows exactly what Send posts. Nothing goes out
 * without Send. From the crash dialog, [EXTRA_CRASH] fills it in: category Other, and the crash report after the log.
 * Send needs a model from the list, except for a crash report. A log without one RX frame blocks Send: the buds
 * never connected. Send without the log still works.
 */
class ReportActivity : Activity() {

    private lateinit var model: AutoCompleteTextView
    private lateinit var description: EditText
    private lateinit var withLog: Switch
    private lateinit var send: TextView
    private lateinit var unsupported: LinearLayout
    private lateinit var logEmpty: TextView
    private lateinit var models: Set<String>
    private var crash: String? = null
    /** No RX frame in the packet log: the buds never answered, so the log holds nothing useful. */
    private var noFrames = false
    private val categories = LinkedHashMap<String, Switch>()
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.problem_title))
        root.addView(TextView(this).apply {
            setText(R.string.problem_intro)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(dp(4f), 0, 0, dp(4f))
        })

        fun field(view: EditText, multiLine: Boolean) = view.apply {
            setTextColor(p.text)
            setHintTextColor(p.textSecondary)
            textSize = 16f
            background = ThemeRes.card(this@ReportActivity, 16f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            gravity = Gravity.TOP or Gravity.START
            if (multiLine) minLines = 4 else isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        root.addView(SettingRowFactory.sectionLabel(this, R.string.problem_model))
        model = field(AutoCompleteTextView(this), false) as AutoCompleteTextView
        model.setText(ModelCatalog.current(this)?.name.orEmpty())
        model.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ModelCatalog.all(this).map { it.name }))
        model.threshold = 1
        model.addTextChangedListener(watcher)
        root.addView(model)
        models = ModelCatalog.all(this).map { it.name.lowercase() }.toSet()
        fun note(text: Int, color: Int) = TextView(this).apply {
            setText(text)
            setTextColor(color)
            textSize = 13f
            setPadding(dp(4f), dp(6f), 0, 0)
        }
        unsupported = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(note(R.string.problem_unsupported, p.textSecondary))
            addView(note(R.string.problem_supported_list, p.accent).apply {
                append(" ›")
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.GITHUB_URL + "#supported-earbuds"))) }
                }
            })
        }
        root.addView(unsupported)

        root.addView(SettingRowFactory.sectionLabel(this, R.string.problem_category))
        val card = SettingRowFactory.card(this)
        ProblemReport.CATEGORIES.zip(CATEGORY_LABELS).forEach { (value, label) ->
            val sw = SettingRowFactory.buildSwitch(this, false)
            sw.setOnCheckedChangeListener { _, _ -> paintSend() }
            categories[value] = sw
            SettingRowFactory.addRow(card, SettingRowFactory.build(this, 0, label, 0, sw) { sw.performClick() })
        }
        root.addView(card)
        root.addView(note(R.string.problem_capture_hint, p.textSecondary))
        root.addView(note(R.string.capture_ask_open, p.accent).apply {
            append(" ›")
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CaptureAsk.ISSUE_URL))) }
            }
        })
        root.addView(note(R.string.problem_capture_link, p.accent).apply {
            append(" ›")
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CAPTURE_URL))) }
            }
        })
        crash = intent.getStringExtra(EXTRA_CRASH)
        noFrames = crash == null && PacketLogger.getFileContent().lines().none { it.contains("  RX") }
        if (crash != null) categories.getValue("Other").isChecked = true

        root.addView(SettingRowFactory.sectionLabel(this, R.string.problem_description))
        description = field(EditText(this), true)
        description.setHint(R.string.problem_description_hint)
        if (crash != null) description.setText("App crash")
        description.addTextChangedListener(watcher)
        root.addView(description)

        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(16f)))
        val logCard = SettingRowFactory.card(this)
        withLog = SettingRowFactory.buildSwitch(this, true)
        withLog.setOnCheckedChangeListener { _, _ -> paintSend() }
        SettingRowFactory.addRow(logCard, SettingRowFactory.build(this, R.drawable.ic_dev_tools, R.string.problem_log_title,
            R.string.problem_log_sub, withLog) { withLog.performClick() })
        root.addView(logCard)
        logEmpty = note(R.string.problem_log_empty, p.textSecondary)
        root.addView(logEmpty)

        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label)
            textSize = 16f
            typeface = ThemeRes.medium(context)
            gravity = Gravity.CENTER
            setTextColor(if (primary) p.onAccent else p.accent)
            background = ThemeRes.ripple(this@ReportActivity,
                if (primary) ThemeRes.pill(this@ReportActivity, p.accent, 26f) else ThemeRes.card(this@ReportActivity, 26f))
            layoutParams = LinearLayout.LayoutParams(0, dp(52f), 1f)
            setOnClickListener { onClick() }
        }
        send = button(R.string.problem_send, true) { submit() }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20f), 0, dp(24f))
            addView(button(R.string.problem_preview, false) { preview() })
            addView(send.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(12f) })
        })
        paintSend()

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = paintSend()
    }

    /** A model from the list; a crash report sends with any model. */
    private fun known() = crash != null || model.text.toString().trim().lowercase() in models

    /** Send needs a known model, a log with frames (when included), and a description. */
    // A description is required: a person who cannot connect at all has no log, only words to reproduce it from.
    private fun ready() = !sending && known() && !(noFrames && withLog.isChecked) && description.text.isNotBlank()

    private fun paintSend() {
        if (!::logEmpty.isInitialized) return
        unsupported.visibility = if (known()) android.view.View.GONE else android.view.View.VISIBLE
        logEmpty.visibility = if (noFrames && withLog.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        send.isEnabled = ready()
        send.alpha = if (send.isEnabled) 1f else 0.4f
    }

    private fun report() = ProblemReport.Report(
        model.text.toString().trim(),
        categories.filterValues { it.isChecked }.keys.toList(),
        description.text.toString().trim(),
        if (withLog.isChecked) ProblemReport.log(this, crash?.let { "\n\n$it" }.orEmpty()) else null,
        crash != null
    )

    private fun preview() {
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.problem_preview)).message(ProblemReport.preview(report()))
        if (ready()) sheet.confirm(getString(R.string.problem_send)) { sheet.close(); submit() }
        sheet.cancel(getString(R.string.dialog_close)) { sheet.close() }
        sheet.show()
    }

    private fun submit() {
        if (!ready()) return
        sending = true
        send.setText(R.string.problem_sending)
        paintSend()
        val r = report()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val (ok, refused) = ProblemReport.send(this, r)
            main.post {
                sending = false
                send.setText(R.string.problem_send)
                paintSend()
                val text = if (ok) getString(R.string.problem_sent) else refused ?: getString(R.string.problem_failed)
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                // A broken or missing feature needs a snoop log in a GitHub issue.
                if (ok && categories["Feature not working"]?.isChecked == true) CaptureAsk.show(this) { finish() }
                else if (ok) finish()
            }
        }.start()
    }

    companion object {
        /** The crash report text, from MainActivity's crash dialog. */
        const val EXTRA_CRASH = "crash"

        /** The guide for a snoop log of the vendor app, for a missing or broken feature. */
        private const val CAPTURE_URL = AboutActivity.GITHUB_URL +
            "/blob/main/docs/PACKET-CAPTURE.md#send-a-snoop-log-for-a-missing-feature"

        /** Labels for [ProblemReport.CATEGORIES], same order. */
        private val CATEGORY_LABELS = listOf(R.string.problem_cat_ui, R.string.problem_cat_lag, R.string.problem_cat_connection,
            R.string.problem_cat_feature, R.string.problem_cat_battery, R.string.problem_cat_other)
    }
}
