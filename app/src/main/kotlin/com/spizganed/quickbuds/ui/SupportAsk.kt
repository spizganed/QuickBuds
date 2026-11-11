package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.spizganed.quickbuds.R

/**
 * Two optional cards ask for support: Ko-fi, then a GitHub star. Each shows as a bottom sheet with Support, Later and
 * "Do not ask again". A card is due after [MIN_DAYS] days of use or [MIN_CONNECTIONS] good connections. It shows only
 * while the buds are connected and idle, at most one card a day. Showing a card sets its next date at once, so a sheet
 * the user dismisses counts as "Later". "Later" and a dismissal bring the card back after [LATER_DAYS] days.
 */
object SupportAsk {

    enum class Card { KOFI, GITHUB }

    const val MIN_DAYS = 14
    const val MIN_CONNECTIONS = 10
    const val LATER_DAYS = 30
    /** A connection counts as good when it lived this long. */
    const val GOOD_MS = 60_000L

    private const val DAY_MS = 86_400_000L
    private const val KEY_CONNECTIONS = "supportConnections"
    private const val KEY_LAST_DAY = "supportLastDay"

    private fun keyNext(card: Card) = "supportNext_${card.name}"
    private fun keyOff(card: Card) = "supportOff_${card.name}"

    /** The card to show now, or null. Pure: the caller passes the state. */
    fun pick(
        installedAt: Long, connections: Int, now: Long, lastShownDay: Long,
        nextAt: (Card) -> Long, off: (Card) -> Boolean,
    ): Card? {
        val used = now - installedAt >= MIN_DAYS * DAY_MS || connections >= MIN_CONNECTIONS
        if (!used || lastShownDay == now / DAY_MS) return null
        return Card.values().firstOrNull { !off(it) && now >= nextAt(it) }
    }

    /** The service calls this when a link ends. A short link does not count. */
    fun countConnection(c: Context, livedMs: Long) {
        if (livedMs < GOOD_MS) return
        val p = prefs(c)
        p.edit().putInt(KEY_CONNECTIONS, p.getInt(KEY_CONNECTIONS, 0) + 1).apply()
    }

    /** Shows the due card, if any. Call only while the buds are connected and idle. */
    fun maybeShow(activity: Activity) {
        val p = prefs(activity)
        val now = System.currentTimeMillis()
        val installed = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime }
            .getOrDefault(now)
        val card = pick(installed, p.getInt(KEY_CONNECTIONS, 0), now, p.getLong(KEY_LAST_DAY, -1L),
            { p.getLong(keyNext(it), 0L) }, { p.getBoolean(keyOff(it), false) }) ?: return
        p.edit().putLong(KEY_LAST_DAY, now / DAY_MS).putLong(keyNext(card), now + LATER_DAYS * DAY_MS).apply()
        val (title, body, action, url) = when (card) {
            Card.KOFI -> Quad(R.string.support_kofi_title, R.string.support_kofi_body, R.string.support_kofi_action, AboutActivity.KOFI_URL)
            Card.GITHUB -> Quad(R.string.support_github_title, R.string.support_github_body, R.string.support_github_action, AboutActivity.GITHUB_URL)
        }
        val sheet = BottomSheetDialog(activity)
        sheet.title(activity.getString(title)).message(activity.getString(body))
            .items(listOf(BottomSheetDialog.Item(activity.getString(R.string.support_never), false) {
                p.edit().putBoolean(keyOff(card), true).apply()
            }))
            .confirm(activity.getString(action)) {
                sheet.close()
                p.edit().putBoolean(keyOff(card), true).apply()
                url?.let { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
            }
            .cancel(activity.getString(R.string.support_later)) { sheet.close() }
            .show()
    }

    private data class Quad(val title: Int, val body: Int, val action: Int, val url: String?)

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
