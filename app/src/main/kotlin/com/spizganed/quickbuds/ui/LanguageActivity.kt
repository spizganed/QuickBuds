package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.res.ColorStateList
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
 * updates the list in place ([onConfigurationChanged]) instead of recreating the window, which
 * flickered. The rows are built once and only recoloured, so the selection outline slides
 * ([SelectionSlider]). The activities behind it still recreate when they come back.
 */
class LanguageActivity : Activity() {

    private class Item(val tag: String, val row: View, val title: TextView, val plain: ColorStateList)

    private val items = ArrayList<Item>()
    private lateinit var heading: TextView
    private lateinit var selection: SelectionSlider

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        selection = SelectionSlider(root, "language")
        heading = SettingRowFactory.title(this, R.string.settings_language_title)
        root.addView(heading)
        val card = SettingRowFactory.splitList(this)
        for ((tag, name) in ThemeRes.LANGUAGES) {
            val row = SettingRowFactory.build(this, 0, 0, 0, null) {
                pick(tag, card)
            }
            val title = row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG)
            title.text = name ?: getString(R.string.language_system)
            items.add(Item(tag, row, title, title.textColors))
            SettingRowFactory.addSplit(card, row)
        }
        root.addView(card)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.palette(this@LanguageActivity).background)
            addView(root)
        })
        show(chosen())
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeRes.markApplied(this)
        heading.setText(R.string.settings_language_title)
        items.first { it.tag.isEmpty() }.title.text = getString(R.string.language_system)
        show(chosen())
    }

    private fun pick(tag: String, card: View) {
        if (tag == chosen()) return
        Haptics.commit(card)
        show(tag)
        ThemeRes.setLanguage(this, tag)
        if (android.os.Build.VERSION.SDK_INT < 33) ThemeRes.recreateFaded(this)
    }

    /** The saved language's tag. A language set in Android's own screen may carry a region (de-AT): tick its language. */
    private var picked: String? = null

    private fun chosen(): String {
        picked?.let { return it }
        val current = ThemeRes.language(this)
        val base = current.substringBefore('-')
        val exact = ThemeRes.LANGUAGES.any { it.first == current }
        return items.first { it.tag == current || (!exact && base != "zh" && it.tag == base) }.tag
    }

    /** Accent label and the sliding outline on [tag]'s row (an accent label on the chosen row, as the EQ preset list does). */
    private fun show(tag: String) {
        picked = tag
        val accent = ThemeRes.palette(this).accent
        for (i in items) i.title.setTextColor(if (i.tag == tag) ColorStateList.valueOf(accent) else i.plain)
        selection.moveTo(items.first { it.tag == tag }.row, accent)
    }
}
