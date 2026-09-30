package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
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

    private var scroll: ScrollView? = null
    private var root: LinearLayout? = null
    private lateinit var selection: SelectionSlider

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

        // One root for the activity's life, so the selection outline can slide between rows.
        val root = this.root ?: SettingRowFactory.screen(this).also { this.root = it; selection = SelectionSlider(it, "language") }
        root.removeAllViews()
        var selectedRow: View? = null
        root.addView(SettingRowFactory.title(this, R.string.settings_language_title))
        val card = SettingRowFactory.splitList(this)
        for ((tag, name) in ThemeRes.LANGUAGES) {
            val selected = tag == current || (!exact && base != "zh" && tag == base)
            val row = SettingRowFactory.build(this, 0, 0, 0, null) {
                if (selected) return@build
                Haptics.commit(card)
                ThemeRes.setLanguage(this, tag)
                if (android.os.Build.VERSION.SDK_INT < 33) ThemeRes.recreateFaded(this)
            }
            // Accent label on the chosen row, as the EQ preset list does.
            row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply {
                text = name ?: getString(R.string.language_system)
                if (selected) setTextColor(p.accent)
            }
            if (selected) selectedRow = row
            SettingRowFactory.addSplit(card, row)
        }
        root.addView(card)
        selection.moveTo(selectedRow, p.accent)

        // Same ScrollView, new content: it keeps its scroll position. A new ScrollView drew one
        // frame at the top before scrolling back, which flickered on any row below the fold.
        val scroll = this.scroll ?: ScrollView(this).also { setContentView(it); this.scroll = it }
        scroll.setBackgroundColor(p.background)
        if (root.parent == null) scroll.addView(root)
        // The new root missed the first insets pass: without this it slid under the status bar.
        scroll.requestApplyInsets()
    }
}
