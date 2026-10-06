package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.widget.AncWidgetProvider

/**
 * The model list, opened from the header button before the connect pill: Automatic (what
 * [ModelCatalog] detects) and every model HeyMelody's list has, by brand (realme and its DIZO
 * included). A pick overrides detection until Automatic is picked again or
 * other buds connect. The rows are built once and only recoloured, so the selection outline slides
 * ([SelectionSlider]).
 */
class ModelActivity : Activity() {

    /** [id] is null for the Automatic row. */
    private class Item(val id: String?, val row: View, val title: TextView, val plain: ColorStateList)

    private val items = ArrayList<Item>()
    private lateinit var selection: SelectionSlider

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val models = ModelCatalog.all(this)
        // Two ids share a name (colour ranges, regional variants): those rows show their id.
        val nameCount = models.groupingBy { it.name }.eachCount()

        val root = SettingRowFactory.screen(this)
        selection = SelectionSlider(root)
        root.addView(SettingRowFactory.title(this, R.string.model_title))

        fun row(id: String?, title: String, subtitle: String?, onPick: () -> Unit): View {
            val r = SettingRowFactory.build(this, 0, 0, 0, null) {
                if (id == ModelCatalog.manual(this)) return@build
                Haptics.commit(root)
                onPick()
                AncWidgetProvider.refreshAll(this)
                show()
            }
            val t = r.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply { text = title }
            if (subtitle != null) SettingRowFactory.subtitle(this, r).text = subtitle
            items.add(Item(id, r, t, t.textColors))
            return r
        }

        val auto = SettingRowFactory.splitList(this)
        val detected = ModelCatalog.detected(this)?.name
        SettingRowFactory.addSplit(auto, row(null, getString(R.string.model_auto),
            if (detected != null) getString(R.string.model_detected, detected) else getString(R.string.model_not_detected)
        ) { ModelCatalog.setManual(this, null) })
        root.addView(auto)

        for ((brand, label) in listOf("OnePlus" to R.string.model_brand_oneplus, "OPPO" to R.string.model_brand_oppo,
                "realme" to R.string.model_brand_realme, "DIZO" to R.string.model_brand_dizo)) {
            root.addView(SettingRowFactory.sectionLabel(this, label))
            val card = SettingRowFactory.splitList(this)
            for (m in models.filter { it.name.startsWith("$brand ") }.sortedBy { it.name.lowercase() }) {
                SettingRowFactory.addSplit(card, row(m.id, m.name, m.id.takeIf { nameCount[m.name]!! > 1 }) {
                    ModelCatalog.setManual(this, m.id)
                })
            }
            root.addView(card)
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.palette(this@ModelActivity).background)
            addView(root)
        })
        show()
    }

    /** Accent label and the sliding outline on the manual model's row (Automatic when none). */
    private fun show() {
        val accent = ThemeRes.palette(this).accent
        val manual = ModelCatalog.manual(this)
        for (i in items) i.title.setTextColor(if (i.id == manual) ColorStateList.valueOf(accent) else i.plain)
        selection.moveTo(items.firstOrNull { it.id == manual }?.row, accent)
    }
}
