package com.spizganed.quickbuds.ui

import android.app.Activity
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
import com.spizganed.quickbuds.protocol.ModelCatalog

/**
 * Settings › Report a problem ([ProblemReport]): buds model (filled in, with the model list as suggestions),
 * categories, a description and the log. See the report shows exactly what Send posts. Nothing goes out
 * without Send. From the crash dialog, [EXTRA_CRASH] fills it in: category Other, and the crash report after the log.
 */
class ReportActivity : Activity() {

    private lateinit var model: AutoCompleteTextView
    private lateinit var description: EditText
    private lateinit var withLog: Switch
    private lateinit var send: TextView
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
        root.addView(model)

        root.addView(SettingRowFactory.sectionLabel(this, R.string.problem_category))
        val card = SettingRowFactory.card(this)
        ProblemReport.CATEGORIES.zip(CATEGORY_LABELS).forEach { (value, label) ->
            val sw = SettingRowFactory.buildSwitch(this, false)
            sw.setOnCheckedChangeListener { _, _ -> paintSend() }
            categories[value] = sw
            SettingRowFactory.addRow(card, SettingRowFactory.build(this, 0, label, 0, sw) { sw.performClick() })
        }
        root.addView(card)
        val crash = intent.getStringExtra(EXTRA_CRASH)
        if (crash != null) categories.getValue("Other").isChecked = true

        root.addView(SettingRowFactory.sectionLabel(this, R.string.problem_description))
        description = field(EditText(this), true)
        description.setHint(R.string.problem_description_hint)
        if (crash != null) description.setText("App crash")
        description.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = paintSend()
        })
        root.addView(description)

        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, dp(16f)))
        val logCard = SettingRowFactory.card(this)
        withLog = SettingRowFactory.buildSwitch(this, true)
        SettingRowFactory.addRow(logCard, SettingRowFactory.build(this, R.drawable.ic_dev_tools, R.string.problem_log_title,
            R.string.problem_log_sub, withLog) { withLog.performClick() })
        root.addView(logCard)

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

    /** Send needs a description or a category. */
    private fun ready() = !sending && (description.text.isNotBlank() || categories.values.any { it.isChecked })

    private fun paintSend() {
        send.isEnabled = ready()
        send.alpha = if (send.isEnabled) 1f else 0.4f
    }

    private fun body() = ProblemReport.body(
        model.text.toString().trim(),
        categories.filterValues { it.isChecked }.keys.toList(),
        description.text.toString().trim(),
        if (withLog.isChecked) ProblemReport.log(this) + intent.getStringExtra(EXTRA_CRASH)?.let { "\n\n$it" }.orEmpty() else null
    )

    private fun preview() {
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.problem_preview)).message(ProblemReport.preview(body()))
        if (ready()) sheet.confirm(getString(R.string.problem_send)) { sheet.close(); submit() }
        sheet.cancel(getString(R.string.dialog_close)) { sheet.close() }
        sheet.show()
    }

    private fun submit() {
        if (!ready()) return
        sending = true
        send.setText(R.string.problem_sending)
        paintSend()
        val body = body()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val ok = ProblemReport.send(body)
            main.post {
                sending = false
                send.setText(R.string.problem_send)
                paintSend()
                Toast.makeText(this, if (ok) R.string.problem_sent else R.string.problem_failed, Toast.LENGTH_LONG).show()
                if (ok) finish()
            }
        }.start()
    }

    companion object {
        /** The crash report text, from MainActivity's crash dialog. */
        const val EXTRA_CRASH = "crash"

        /** Labels for [ProblemReport.CATEGORIES], same order. */
        private val CATEGORY_LABELS = listOf(R.string.problem_cat_ui, R.string.problem_cat_lag, R.string.problem_cat_connection,
            R.string.problem_cat_feature, R.string.problem_cat_battery, R.string.problem_cat_other)
    }
}
