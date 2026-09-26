package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * Centred confirm dialog (design/SPEC.md 3.4): `card` background, 24dp radius, `outline` stroke,
 * a 19sp bold title, a 14sp secondary body, and two 44dp pills at the end — Cancel (outlined)
 * and the action (accent fill). A plain Dialog, no Material dependency. Used for Disconnect and
 * for deleting a preset (3.8).
 */
object ConfirmDialog {

    fun show(activity: Activity, title: String, body: String?, action: String, onConfirm: () -> Unit) {
        val p = ThemeRes.palette(activity)
        val dp = { v: Float -> ThemeRes.dp(activity, v) }
        val d = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

        fun pill(text: String, filled: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (filled) p.onAccent else p.text)
            setPadding(dp(22f), 0, dp(22f), 0)
            background = ThemeRes.ripple(
                activity,
                if (filled) ThemeRes.shape(activity, p.accent, null, 22f)
                else ThemeRes.shape(activity, p.card, p.outline, 22f)
            )
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44f))
                .apply { marginStart = dp(10f) }
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeRes.card(activity)
            setPadding(dp(24f), dp(24f), dp(24f), dp(20f))
            addView(TextView(activity).apply {
                text = title
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.text)
            })
            if (body != null) addView(TextView(activity).apply {
                text = body
                textSize = 14f
                setTextColor(p.textSecondary)
                setPadding(0, dp(10f), 0, 0)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(24f), 0, 0)
                addView(pill(activity.getString(R.string.dialog_cancel), false) { d.dismiss() })
                addView(pill(action, true) { d.dismiss(); onConfirm() })
            })
        }

        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.5f)
            w.setLayout(
                (activity.resources.displayMetrics.widthPixels - dp(40f)).coerceAtMost(dp(420f)),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        d.show()
    }
}
