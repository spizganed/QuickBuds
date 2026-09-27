package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * In-app language (Settings > General > Language): System default and one row per language we
 * ship, instead of Android's per-app screen with every regional variant. Saved through [ThemeRes].
 *
 * The manifest gives this activity `configChanges="locale|layoutDirection"`: on Android 13+ a pick
 * rebuilds the list in place ([onConfigurationChanged]) instead of recreating the window, which
 * flickered. The activities behind it still recreate when they come back.
 */
class LanguageActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeRes.markApplied(this)
        build()
    }

    private fun build() {
        val p = ThemeRes.palette(this)
        val current = ThemeRes.language(this)
        // A language set in Android's own screen may carry a region (de-AT): tick its language.
        val base = current.substringBefore('-')
        val exact = ThemeRes.LANGUAGES.any { it.first == current }

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.settings_language_title))
        val card = SettingRowFactory.card(this)
        for ((tag, name) in ThemeRes.LANGUAGES) {
            val selected = tag == current || (!exact && base != "zh" && tag == base)
            val check = if (!selected) null else ImageView(this).apply {
                setImageDrawable(ThemeRes.tint(this@LanguageActivity, R.drawable.ic_check, p.accent))
            }
            val row = SettingRowFactory.build(this, 0, 0, 0, check) {
                if (selected) return@build
                Haptics.commit(card)
                ThemeRes.setLanguage(this, tag)
                if (android.os.Build.VERSION.SDK_INT < 33) recreate()
            }
            // Accent label on the chosen row, as the EQ preset list does.
            row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply {
                text = name ?: getString(R.string.language_system)
                if (selected) setTextColor(p.accent)
            }
            SettingRowFactory.addRow(card, row)
        }
        root.addView(card)

        val scroll = (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)
            .getChildAt(0) as? ScrollView)?.scrollY ?: 0
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
            post { scrollTo(0, scroll) }
        })
    }
}
