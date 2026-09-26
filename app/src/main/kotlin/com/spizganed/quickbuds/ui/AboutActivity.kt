package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * About ([USER] 2026-09-26): icon, name, version and tagline, a GitHub and a Ko-fi button (both open
 * the phone's default browser), and the license with a pointer to the credits.
 */
class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)

        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(88f), dp(88f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12f)
            }
        })
        root.addView(TextView(this).apply {
            setText(R.string.app_name)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.text)
            gravity = Gravity.CENTER
            setPadding(0, dp(10f), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.about_version, UpdateChecker.installed(this@AboutActivity))
            textSize = 14f
            setTextColor(p.textSecondary)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            setText(R.string.about_tagline)
            textSize = 15f
            setTextColor(p.text)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(14f), dp(8f), 0)
        })

        // GitHub and Ko-fi, side by side.
        fun linkButton(icon: Int, label: Int, desc: Int, url: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ThemeRes.ripple(this@AboutActivity, ThemeRes.card(this@AboutActivity, 18f))
            layoutParams = LinearLayout.LayoutParams(0, dp(52f), 1f)
            contentDescription = getString(desc)
            addView(ImageView(this@AboutActivity).apply {
                setImageDrawable(ThemeRes.tint(this@AboutActivity, icon, p.accent))
                layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply { marginEnd = dp(10f) }
            })
            addView(TextView(this@AboutActivity).apply {
                setText(label)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(p.text)
            })
            setOnClickListener { open(url) }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20f), 0, 0)
            addView(linkButton(R.drawable.ic_github, R.string.about_github, R.string.about_github_desc, GITHUB_URL))
            addView(linkButton(R.drawable.ic_kofi, R.string.about_kofi, R.string.about_kofi_desc, KOFI_URL).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10f)
            })
        })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.about_license_title))
        root.addView(SettingRowFactory.card(this).apply {
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            addView(TextView(this@AboutActivity).apply {
                setText(R.string.about_license_body)
                textSize = 14f
                setTextColor(p.textSecondary)
                setLineSpacing(0f, 1.15f)
            })
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    /** Hands the link to the default browser; a phone with none just does nothing. */
    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/spizganed/QuickBuds"
        // ponytail: placeholder until the Ko-fi page exists; swap in the real handle.
        const val KOFI_URL = "https://ko-fi.com/"
    }
}
