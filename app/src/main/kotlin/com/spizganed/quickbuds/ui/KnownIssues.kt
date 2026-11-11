package com.spizganed.quickbuds.ui

import android.content.Context
import android.os.Build
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.ModelCatalog

/**
 * Limits of the current earbuds and phone that the person should know. The keys come from the model's
 * `knownIssues` in `models.json` and from checks on the phone (Samsung codec, link hold) and the rows. Each key has a title and a body string.
 */
object KnownIssues {

    class Issue(val key: String, val title: Int, val body: Int)

    private val ALL = listOf(
        Issue("spatial_silent", R.string.issue_spatial_silent_title, R.string.issue_spatial_silent_body),
        Issue("samsung_codec", R.string.issue_samsung_codec_title, R.string.issue_samsung_codec_body),
        Issue("oneplus_link", R.string.issue_oneplus_title, R.string.oneplus_hint),
        Issue("hires_spatial", R.string.issue_hires_spatial_title, R.string.issue_hires_spatial_body),
        Issue("hires_no_codec", R.string.issue_hires_no_codec_title, R.string.issue_hires_no_codec_body),
    )

    /** The issues of the current model and phone, in the order of [ALL]. The link hint shows without a model too. */
    fun current(c: Context): List<Issue> {
        val keys = mutableSetOf<String>()
        if (PhoneChecks.isColorOsPhone(Build.MANUFACTURER)) keys += ONEPLUS_LINK
        val model = ModelCatalog.current(c) ?: return ALL.filter { it.key in keys }
        keys += model.profile.knownIssues
        val hires = MainActivity.rowSupported(c, "hires")
        if (hires && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) keys += "samsung_codec"
        if (hires && MainActivity.rowSupported(c, "spatial") && !MainActivity.spatialSwitchOnly(c)) keys += "hires_spatial"
        // The data says this model has no high-quality codec, so the switch cannot change the sound.
        if (hires && model.json.has("codecs") && ModelCatalog.bestHiResCodec(model.json) == null) keys += "hires_no_codec"
        return ALL.filter { it.key in keys }
    }

    /** The launch prompt asks once per model that has issues. The link hint is a phone fact, so it does not ask. */
    fun shouldAnnounce(c: Context): Boolean {
        val model = ModelCatalog.current(c) ?: return false
        return model.id !in announced(c) && current(c).any { it.key != ONEPLUS_LINK }
    }

    /** The link dialog asks once per install on a OnePlus, OPPO or realme phone, with or without a model. */
    fun shouldAnnounceLink(manufacturer: String?, shownBefore: Boolean): Boolean =
        PhoneChecks.isColorOsPhone(manufacturer) && !shownBefore

    fun linkAnnounced(c: Context): Boolean = prefs(c).getBoolean(KEY_LINK_ANNOUNCED, false)

    fun markLinkAnnounced(c: Context) {
        prefs(c).edit().putBoolean(KEY_LINK_ANNOUNCED, true).apply()
    }

    fun markAnnounced(c: Context) {
        val model = ModelCatalog.current(c) ?: return
        prefs(c).edit().putString(KEY_ANNOUNCED, (announced(c) + model.id).joinToString(",")).apply()
    }

    private fun announced(c: Context) = prefs(c).getString(KEY_ANNOUNCED, null)?.split(',').orEmpty().toSet()

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    private const val KEY_ANNOUNCED = "knownIssuesAnnounced"
    private const val KEY_LINK_ANNOUNCED = "knownIssuesLinkAnnounced"
    private const val ONEPLUS_LINK = "oneplus_link"
}
