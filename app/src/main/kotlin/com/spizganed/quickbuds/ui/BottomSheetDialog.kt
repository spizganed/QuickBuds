package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * The app's own bottom sheet dialog.
 *
 * WHY THIS EXISTS RATHER THAN AlertDialog
 *
 * Three places needed the same thing and each had grown its own AlertDialog: the
 * theme picker, the ANC mode chooser and the gesture action picker. An AlertDialog
 * on this project's theme renders as a platform box with the stock blue accents,
 * which is the "does not match the rest of the app" problem, and it appears
 * CENTRED — the request was explicitly for these to come up from the BOTTOM.
 *
 * A real Material BottomSheetDialog is not an option: this project has no Material
 * dependency (see SettingRowFactory's note on why the switch had to be tinted by
 * hand). So this is a plain Dialog with a bottom-gravity window, no new dependency.
 *
 * WHY NOT A View ON TOP OF THE ACTIVITY: it would need its own back handling, its
 * own outside-tap dismissal and its own dim. A Dialog gets all three for free.
 *
 * MUTABLE IN PLACE, ON PURPOSE. `updateItems` and `updateMessage` re-render the
 * already-open sheet. The first version of this class could only be built once, so
 * the multi-select gesture picker had to create a NEW dialog on every toggle —
 * which stacked dialogs and made the screen flicker on each tap. Rows also never
 * dismissed the sheet, so a single-select tap (theme, ANC) left it open. Both are
 * fixed here: rows dismiss by default via [dismissOnSelect], and updates happen in
 * place.
 */
class BottomSheetDialog(private val activity: Activity) {

    /**
     * One selectable row.
     *
     * @param label    already-localised text, so this class never resolves strings
     *                 itself and cannot disagree with its callers.
     * @param selected draws the tick and the accent label.
     * @param enabled  false renders the row dimmed and untappable.
     */
    data class Item(
        val label: String,
        val selected: Boolean,
        val enabled: Boolean = true,
        val onClick: (() -> Unit)? = null
    )

    private var titleText: String? = null
    private var messageText: String? = null
    private var itemList: List<Item> = emptyList()
    private var confirmText: String? = null
    private var onConfirm: (() -> Unit)? = null

    /**
     * Whether tapping a row closes the sheet. True is right for a chooser where one
     * tap settles the question (theme, ANC, single-select gestures). False is for
     * multi-select, where the user is editing a set and a tap is not a decision.
     */
    private var dismissOnSelect = true

    private var dialog: Dialog? = null
    private var listColumn: LinearLayout? = null
    private var messageView: TextView? = null
    private var titleView: TextView? = null
    private var confirmView: TextView? = null
    private var content: View? = null
    private var onDismiss: (() -> Unit)? = null

    fun title(text: String?) = apply {
        titleText = text
        titleView?.text = text
    }

    /** A view shown between the message and the rows (e.g. the fit test's buds). */
    fun content(view: View) = apply { content = view }

    /** Called once when the sheet closes, however it was closed. */
    fun onDismiss(block: () -> Unit) = apply { onDismiss = block }

    fun message(text: String?) = apply {
        messageText = text
        // If the sheet is already up, reflect it immediately rather than storing a
        // value that only appears next time — that is the whole point of the
        // in-place update.
        messageView?.let { renderMessage(it) }
    }
    fun items(list: List<Item>) = apply {
        itemList = list
        listColumn?.let { renderItems(it) }
    }

    fun dismissOnSelect(enabled: Boolean) = apply { dismissOnSelect = enabled }

    /** The confirm button. Does NOT auto-dismiss — the caller calls [close]. */
    fun confirm(label: String, onClick: () -> Unit) = apply {
        confirmText = label
        onConfirm = onClick
        confirmView?.text = label
    }

    private var cancelText: String? = null
    private var onCancel: (() -> Unit)? = null

    /** A second, outlined button under the confirm button. */
    fun cancel(label: String, onClick: () -> Unit) = apply { cancelText = label; onCancel = onClick }

    private var inputInitial: String? = null
    private var inputMaxLength = 0
    private var inputField: EditText? = null

    /** An optional one-line text field above the confirm button (e.g. a rename), with the keyboard up. */
    fun input(initial: String, maxLength: Int) = apply {
        inputInitial = initial
        inputMaxLength = maxLength
    }

    /** The field's current text, trimmed; empty when there is no field. */
    fun inputValue(): String = inputField?.text?.toString()?.trim().orEmpty()

    /** Dismisses the sheet if it is showing. Safe to call when it is not. */
    fun close() {
        dialog?.dismiss()
    }

    fun show() {
        if (dialog != null) return

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dp = { v: Float -> ThemeRes.dp(activity, v) }
        val primary = ThemeRes.color(activity, R.attr.appColorTextPrimary)
        // Glass: Apple's floating sheet, centred text, capsule buttons.
        val glass = ThemeRes.glass(activity) && !ThemeRes.nothing(activity)
        val blur = glass && ThemeRes.windowBlur(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = if (glass) ThemeRes.floatingSheet(activity) else ThemeRes.card(activity, 28f, solid = true, base = ThemeRes.palette(activity).background)
            if (glass) setPadding(dp(14f), dp(22f), dp(14f), dp(16f)) else setPadding(dp(6f), dp(22f), dp(6f), dp(14f))
        }

        titleText?.let {
            root.addView(TextView(activity).apply {
                titleView = this
                setText(it)
                setTextColor(primary)
                textSize = if (glass) 17f else 16f
                typeface = ThemeRes.bold(context)
                if (glass) gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14f), 0, dp(14f), dp(4f))
            })
        }

        // Created unconditionally and hidden when empty, so a later updateMessage()
        // has a view to write into without rebuilding the sheet.
        // Created unconditionally and hidden when empty, so a later updateMessage()
        // has a view to write into without rebuilding the sheet.
        val msgView = TextView(activity).apply {
            setTextColor(ThemeRes.color(activity, R.attr.appColorTextSecondary))
            textSize = if (glass) 14f else 13f
            if (glass) gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14f), dp(4f), dp(14f), dp(if (glass) 14f else 8f))
        }
        messageView = msgView
        renderMessage(msgView)
        root.addView(msgView)
        content?.let { root.addView(it) }

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        listColumn = column
        renderItems(column)

        // Rows scroll rather than pushing the sheet past the top of the screen: the
        // gesture action list is six entries. Capped by the window layout below so
        // the content behind stays visible, which is the point of a bottom sheet.
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(column)
        }
        root.addView(scroll)

        inputInitial?.let { initial ->
            val field = EditText(activity).apply {
                setText(initial)
                setSelection(initial.length)
                filters = arrayOf(InputFilter.LengthFilter(inputMaxLength))
                isSingleLine = true
                setTextColor(primary)
                // The app's accent, not the platform's default blue underline.
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ThemeRes.color(activity, R.attr.appColorAccent)
                )
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(14f); marginEnd = dp(14f) }
            }
            inputField = field
            root.addView(field)
            field.requestFocus()
        }

        // The two buttons sit side by side, Cancel on the left and the main one on the right; one alone fills the row.
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f); marginStart = dp(10f); marginEnd = dp(10f) }
        }
        val buttonH = dp(if (glass) 50f else 46f)
        cancelText?.let { label ->
            buttons.addView(TextView(activity).apply {
                setText(label)
                textSize = 15f
                typeface = ThemeRes.bold(context)
                gravity = Gravity.CENTER
                setTextColor(primary)
                background = if (glass) ThemeRes.GlassDrawable(activity, ThemeRes.FULL, flat = true) else ThemeRes.chip(activity, false)
                layoutParams = LinearLayout.LayoutParams(0, buttonH, 1f).apply { marginEnd = if (confirmText != null) dp(4f) else 0 }
                setOnClickListener { onCancel?.invoke() }
            })
        }
        confirmText?.let { label ->
            buttons.addView(TextView(activity).apply {
                confirmView = this
                setText(label)
                textSize = 15f
                typeface = ThemeRes.bold(context)
                gravity = Gravity.CENTER
                setTextColor(if (glass) ThemeRes.palette(activity).onAccent else ThemeRes.onFill(activity))
                // Glass: the prominent capsule is lit glass.
                background = if (glass) ThemeRes.shape(activity, ThemeRes.palette(activity).accent, null, ThemeRes.FULL) else ThemeRes.chip(activity, true)
                layoutParams = LinearLayout.LayoutParams(0, buttonH, 1f).apply { marginStart = if (cancelText != null) dp(4f) else 0 }
                setOnClickListener { onConfirm?.invoke() }
            })
        }
        if (buttons.childCount > 0) root.addView(buttons)

        present(activity, d, root, keyboard = inputInitial != null)

        d.setOnDismissListener { dialog = null; open--; onDismiss?.invoke() }
        dialog = d
        open++
        d.show()
    }

    private fun renderMessage(view: TextView) {
        val text = messageText
        val empty = text == null || text.isEmpty()
        if (dialog == null) {
            // Not showing yet: no animation.
            view.visibility = if (empty) View.GONE else View.VISIBLE
            if (!empty) view.text = text
            return
        }
        // Showing: the message grows open or folds away, it does not pop in and out (that moved the whole dialog).
        if (empty) {
            if (view.visibility == View.VISIBLE) Motion.slide(view, false)
        } else {
            view.text = text
            if (view.visibility != View.VISIBLE) Motion.slide(view, true)
        }
    }

    private fun renderItems(column: LinearLayout) {
        // Same rows as before (a multi-select toggled): update them in place, so nothing flashes and the change can animate.
        if (column.childCount == itemList.size && column.childCount > 0) {
            itemList.forEachIndexed { i, item -> bindRow(column.getChildAt(i) as LinearLayout, item, animate = true) }
            return
        }
        column.removeAllViews()
        for (item in itemList) column.addView(buildRow(item))
    }

    private fun buildRow(item: Item): View {
        val dp = { v: Float -> ThemeRes.dp(activity, v) }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
            setPadding(dp(14f), 0, dp(14f), 0)
            addView(TextView(activity).apply {
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        bindRow(row, item, animate = false)
        return row
    }

    /** Sets a row's text, click, and selection look. With [animate] a change of selection fades. */
    private fun bindRow(row: LinearLayout, item: Item, animate: Boolean) {
        val primary = ThemeRes.color(activity, R.attr.appColorTextPrimary)
        val secondary = ThemeRes.color(activity, R.attr.appColorTextSecondary)
        val accent = ThemeRes.color(activity, R.attr.appColorAccent)
        val glassRows = ThemeRes.glass(activity) && !ThemeRes.nothing(activity)
        val label = row.getChildAt(0) as TextView
        if (label.text.toString() != item.label) label.text = item.label

        row.isClickable = item.enabled
        row.setOnClickListener(null)
        if (item.enabled && item.onClick != null) {
            row.setOnClickListener {
                Haptics.commit(it)
                item.onClick.invoke()
                if (dismissOnSelect) close()
            }
        }
        // A disabled row is dimmed rather than hidden, so a rule-restricted option is visible as something that exists
        // but is not currently allowed.
        row.alpha = if (item.enabled) 1f else 0.4f

        val sel = item.selected
        val was = row.tag as? Boolean
        val toColor = if (sel && item.enabled && !glassRows) accent else primary
        if (was == sel) { label.setTextColor(toColor); return }
        row.tag = sel
        val fade = animate && was != null
        if (!fade) label.setTextColor(toColor)
        else android.animation.ValueAnimator.ofArgb(label.currentTextColor, toColor).apply {
            duration = 180
            addUpdateListener { label.setTextColor(it.animatedValue as Int) }
            start()
        }
        when {
            // Glass: a selected row is a lit capsule, not an outline.
            glassRows -> {
                val lit = ThemeRes.GlassDrawable(activity, ThemeRes.FULL, selected = true, flat = true)
                val none = android.graphics.drawable.ColorDrawable(0)
                if (!fade) row.background = if (sel) lit else null
                else row.background = android.graphics.drawable.TransitionDrawable(if (sel) arrayOf(none, lit) else arrayOf(lit, none)).apply { isCrossFadeEnabled = true; startTransition(180) }
            }
            sel -> row.foreground = ThemeRes.selectedBorder(activity, if (item.enabled) accent else secondary)
            else -> row.foreground = null
        }
    }

    companion object {
        /**
         * Shows [root] as a centred dialog window: a rounded card (Glass: a floating Liquid Glass plate with a blurred backdrop
         * on Android 12+), 24dp from the screen's sides, entering with a quick overshoot. Shared by every dialog in the app.
         */
        fun present(activity: Activity, d: Dialog, root: View, keyboard: Boolean = false) {
            val dp = { v: Float -> ThemeRes.dp(activity, v) }
            val glass = ThemeRes.glass(activity) && !ThemeRes.nothing(activity)
            val blur = glass && ThemeRes.windowBlur(activity)
            val max = dp(420f)
            val frame = android.widget.FrameLayout(activity).apply {
                setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
                clipChildren = false
                addView(root, android.widget.FrameLayout.LayoutParams(
                    minOf(activity.resources.displayMetrics.widthPixels - dp(48f), max), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            d.setContentView(frame)
            d.setCanceledOnTouchOutside(true)
            d.window?.let { w ->
                Haptics.watch(activity, w)
                w.setBackgroundDrawable(ColorDrawable(0x00000000))
                if (blur) {
                    root.background = null
                    w.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(ThemeRes.floatingSheet(activity), dp(24f)))
                    w.setBackgroundBlurRadius(dp(28f))
                }
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0.45f)
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                w.setGravity(Gravity.CENTER)
                w.setWindowAnimations(android.R.style.Animation_Dialog)
                @Suppress("DEPRECATION")
                if (keyboard) w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            // Glass: the screen behind blurs while the dialog is open (an in-app effect, so it needs no compositor support).
            if (glass && android.os.Build.VERSION.SDK_INT >= 31) {
                val decor = activity.window.decorView
                decor.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(10f).toFloat(), dp(10f).toFloat(), Shader.TileMode.CLAMP))
                d.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) { decor.setRenderEffect(null) }
                })
            }
            // Quick, with a spring: the card grows from 92% and settles. Nothing runs when the system removes animations.
            if (android.provider.Settings.Global.getFloat(activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) {
                frame.alpha = 0f
                frame.scaleX = 0.92f; frame.scaleY = 0.92f
                frame.post { frame.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start() }
            }
        }

        /** Sheets showing now, in every screen. A launch prompt waits while one is open (MainActivity). */
        var open = 0
            private set
    }
}
