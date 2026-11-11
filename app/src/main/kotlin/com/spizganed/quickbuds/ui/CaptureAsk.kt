package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.spizganed.quickbuds.R

/**
 * After a report or a model test with a broken or missing feature: asks for a GitHub issue with a snoop log
 * and a screen recording. The app's log alone is almost never enough to add or fix a feature.
 */
object CaptureAsk {
    /** The Missing feature issue form. */
    const val ISSUE_URL = AboutActivity.GITHUB_URL + "/issues/new?template=missing-feature.yml"

    /** Shows the sheet; [done] runs when it closes, whichever way. */
    fun show(activity: Activity, done: () -> Unit) {
        val sheet = BottomSheetDialog(activity)
        sheet.title(activity.getString(R.string.capture_ask_title))
            .message(activity.getString(R.string.capture_ask_body))
            .confirm(activity.getString(R.string.capture_ask_open)) {
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_URL))) }
                sheet.close()
            }
            .cancel(activity.getString(R.string.test_ask_later)) { sheet.close() }
            .onDismiss(done)
            .show()
    }
}
