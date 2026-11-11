package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R

/** Settings › Known issues: the limits of the current earbuds and phone ([KnownIssues]), one card each. */
class KnownIssuesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.settings_issues_title))

        val issues = KnownIssues.current(this)
        if (issues.isEmpty()) {
            root.addView(TextView(this).apply {
                setText(R.string.issues_none)
                textSize = 15f
                setTextColor(p.textSecondary)
                setPadding(dp(4f), dp(16f), dp(4f), 0)
            })
        }
        for (issue in issues) {
            root.addView(SettingRowFactory.card(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8f)
                addView(TextView(context).apply {
                    setText(issue.title)
                    textSize = 16f
                    typeface = ThemeRes.medium(context)
                    setTextColor(p.text)
                })
                addView(TextView(context).apply {
                    setText(issue.body)
                    textSize = 14f
                    setTextColor(p.textSecondary)
                    setPadding(0, dp(4f), 0, 0)
                })
            })
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@KnownIssuesActivity, R.attr.appColorBg))
            addView(root)
        })
    }
}
