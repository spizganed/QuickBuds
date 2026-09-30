package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Earbud fit test, a bottom sheet as in HeyMelody (`[VENDOR]`,
 * PROTOCOL.md §9): Play starts `0x0405 01`, the buds play a tone and push one `0x0204`
 * subType `04` result per bud ([result]). Both buds must be in an ear; no result within 15 s
 * is a failure; closing the sheet sends the stop, as HeyMelody does.
 *
 * Statuses: 1 good, 0 average, 6 poor; anything else (or a bud missing) fails the test.
 */
class FitTestSheet(private val activity: Activity, private val send: (Boolean) -> Unit) {

    private val sheet = BottomSheetDialog(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { running = false; fail() }
    private var running = false
    private var started = false
    private lateinit var leftLabel: TextView
    private lateinit var rightLabel: TextView

    private fun s(res: Int) = activity.getString(res)

    fun show() {
        sheet.title(s(R.string.fit_title))
            .message(s(R.string.fit_hint))
            .content(buds())
            .confirm(s(R.string.fit_play)) { play() }
            .onDismiss {
                handler.removeCallbacks(timeout)
                if (started) send(false)
            }
            .show()
    }

    private fun buds(): LinearLayout {
        val dp = { v: Float -> ThemeRes.dp(activity, v) }
        fun column(icon: Int, side: Int) = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(120f), LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(activity).apply {
                setText(side)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(ThemeRes.color(activity, R.attr.appColorTextSecondary))
                setPadding(0, 0, 0, 0)
                textSize = 15f
                if (icon == R.drawable.ic_bud_left) leftLabel = this else rightLabel = this
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20f), 0, dp(16f))
            addView(column(R.drawable.ic_bud_left, R.string.gesture_bud_left))
            addView(column(R.drawable.ic_bud_right, R.string.gesture_bud_right))
        }
    }

    private fun play() {
        if (running) return
        val st = WidgetStateStore.read(activity)
        if (listOf(st.leftStatus, st.rightStatus).any { it != 3 && it != 7 }) {
            sheet.title(s(R.string.fit_insert))
            return
        }
        running = true
        started = true
        paint(leftLabel, R.string.gesture_bud_left, -1)
        paint(rightLabel, R.string.gesture_bud_right, -1)
        sheet.title(s(R.string.fit_keep)).message(null).confirm(s(R.string.fit_playing)) {}
        send(true)
        handler.postDelayed(timeout, 15_000)
    }

    /** A result from the buds; ignored unless a test is running. */
    fun result(left: Int, right: Int) {
        if (!running) return
        running = false
        handler.removeCallbacks(timeout)
        val ok = setOf(0, 1, 6)
        if (left !in ok || right !in ok) { fail(); return }
        paint(leftLabel, R.string.gesture_bud_left, left)
        paint(rightLabel, R.string.gesture_bud_right, right)
        if (left == 1 && right == 1) {
            sheet.title(s(R.string.fit_perfect)).message(null).confirm(s(R.string.gesture_done)) { sheet.close() }
        } else {
            val which = when {
                left != 1 && right != 1 -> R.string.fit_both
                left != 1 -> R.string.fit_left
                else -> R.string.fit_right
            }
            sheet.title(s(R.string.fit_adjust))
                .message(activity.getString(R.string.fit_adjust_tips, s(which)))
                .confirm(s(R.string.fit_again)) { play() }
        }
    }

    private fun fail() {
        sheet.title(s(R.string.fit_failed)).message(s(R.string.fit_hint)).confirm(s(R.string.fit_play)) { play() }
    }

    /** "Left" before a result, "Left · Good" after; a good fit in the accent colour. */
    private fun paint(label: TextView, side: Int, status: Int) {
        val result = when (status) { 1 -> R.string.fit_good; 0 -> R.string.fit_average; 6 -> R.string.fit_poor; else -> 0 }
        label.text = if (result == 0) s(side) else "${s(side)} · ${s(result)}"
        label.setTextColor(ThemeRes.color(activity,
            if (status == 1) R.attr.appColorAccent else if (result == 0) R.attr.appColorTextSecondary else R.attr.appColorTextPrimary))
    }
}
