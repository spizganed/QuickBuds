package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Find my earbuds — the buds' OWN locator tone, `0x0400` `01` start / `00` stop
 * (`[CAPTURE]` 2026-09-23, PROTOCOL.md §9).
 *
 * It rings BOTH buds at once: the command has no side byte, and HeyMelody offers no
 * per-bud choice (`[USER]`). The tone is loud, so, like HeyMelody, starting it while a
 * bud reports being in an ear (wear status 3/7) asks first.
 *
 * Stop is sent on onStop too, so the tone cannot outlive this screen.
 */
class FindBudsActivity : Activity() {

    private var playing = false
    private lateinit var playButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate — see ThemeRes.select.
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeRes.color(this@FindBudsActivity, R.attr.appColorBg))
            ThemeRes.screenPadding(this)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(TextView(this).apply {
            setText(R.string.find_title)
            setTextColor(ThemeRes.color(this@FindBudsActivity, R.attr.appColorTextPrimary))
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        root.addView(TextView(this).apply {
            setText(R.string.find_hint)
            setTextColor(ThemeRes.color(this@FindBudsActivity, R.attr.appColorTextSecondary))
            textSize = 14f
            setPadding(0, dp(10f), 0, dp(30f))
        })

        playButton = Button(this).apply {
            setTextColor(ThemeRes.color(this@FindBudsActivity, R.attr.appColorTextPrimary))
            background = ThemeRes.iconButton(context)
            setPadding(dp(32f), dp(12f), dp(32f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { if (playing) setTone(false) else startWithWarning() }
        }
        root.addView(playButton)
        render()

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startWithWarning() {
        val st = WidgetStateStore.read(this)
        val inEar = listOf(st.leftStatus, st.rightStatus).any { it == 3 || it == 7 }
        if (!inEar) {
            setTone(true)
            return
        }
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.find_warn_title))
            .message(getString(R.string.find_warn_msg))
            .confirm(getString(R.string.find_warn_play)) { sheet.close(); setTone(true) }
            .show()
    }

    private fun setTone(on: Boolean) {
        playing = on
        startService(
            Intent(this, BudsService::class.java)
                .setAction(BudsService.ACTION_FIND_BUDS)
                .putExtra(BudsService.EXTRA_FIND_ON, on)
        )
        render()
    }

    private fun render() {
        playButton.setText(if (playing) R.string.find_stop else R.string.find_play)
    }

    override fun onStop() {
        super.onStop()
        if (playing) setTone(false)
    }
}
