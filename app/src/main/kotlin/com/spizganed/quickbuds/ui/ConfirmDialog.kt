package com.spizganed.quickbuds.ui

import android.app.Activity
import com.spizganed.quickbuds.R

/**
 * Confirm prompt as a bottom sheet (dialogs are sheets): title, secondary body, the action
 * button and, unless [show] gets no `cancelRes`, a Cancel button under it. Used for Disconnect, deleting a preset,
 * warnings and notices.
 */
object ConfirmDialog {

    fun show(
        activity: Activity, title: String, body: String?, action: String,
        cancelRes: Int? = R.string.dialog_cancel, onCancel: () -> Unit = {}, onConfirm: () -> Unit = {}
    ) {
        val sheet = BottomSheetDialog(activity)
        sheet.title(title).message(body).confirm(action) { sheet.close(); onConfirm() }
        cancelRes?.let { sheet.cancel(activity.getString(it)) { sheet.close(); onCancel() } }
        sheet.show()
    }
}
