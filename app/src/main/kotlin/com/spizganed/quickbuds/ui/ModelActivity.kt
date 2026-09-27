package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.widget.AncWidgetProvider

/**
 * The model list, opened from the device name on the main screen: Automatic (what
 * [ModelCatalog] detects) and every OnePlus and OPPO model HeyMelody lists. A pick overrides
 * detection until Automatic is picked again or other buds connect. realme is left out: HeyMelody
 * does not support it.
 */
class ModelActivity : Activity() {

    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        val p = ThemeRes.palette(this)
        val manual = ModelCatalog.manual(this)
        val models = ModelCatalog.all(this)
        // Two ids share a name (colour ranges, regional variants): those rows show their id.
        val nameCount = models.groupingBy { it.name }.eachCount()

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.model_title))

        fun row(title: String, subtitle: String?, selected: Boolean, onPick: () -> Unit) =
            SettingRowFactory.build(this, 0, 0, 0, if (!selected) null else ImageView(this).apply {
                setImageDrawable(ThemeRes.tint(this@ModelActivity, R.drawable.ic_check, p.accent))
            }) {
                if (selected) return@build
                Haptics.commit(root)
                onPick()
                AncWidgetProvider.refreshAll(this)
                build()
            }.also { r ->
                r.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply {
                    text = title
                    if (selected) setTextColor(p.accent)
                }
                if (subtitle != null) SettingRowFactory.subtitle(this, r).text = subtitle
            }

        val auto = SettingRowFactory.card(this)
        val detected = ModelCatalog.detected(this)?.name
        SettingRowFactory.addRow(auto, row(getString(R.string.model_auto),
            if (detected != null) getString(R.string.model_detected, detected) else getString(R.string.model_not_detected),
            manual == null) { ModelCatalog.setManual(this, null) })
        root.addView(auto)

        for ((brand, label) in listOf("OnePlus" to R.string.model_brand_oneplus, "OPPO" to R.string.model_brand_oppo)) {
            root.addView(SettingRowFactory.sectionLabel(this, label))
            val card = SettingRowFactory.card(this)
            for (m in models.filter { it.name.startsWith("$brand ") }.sortedBy { it.name.lowercase() }) {
                SettingRowFactory.addRow(card, row(m.name, m.id.takeIf { nameCount[m.name]!! > 1 },
                    m.id == manual) { ModelCatalog.setManual(this, m.id) })
            }
            root.addView(card)
        }

        // Same ScrollView, new content: it keeps its scroll position (see LanguageActivity).
        val scroll = this.scroll ?: ScrollView(this).also { setContentView(it); this.scroll = it }
        scroll.setBackgroundColor(p.background)
        scroll.removeAllViews()
        scroll.addView(root)
        scroll.requestApplyInsets()
    }
}
