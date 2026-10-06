package com.spizganed.quickbuds.bluetooth

/** Action strings broadcast between the widget receiver, provider, and service. */
object WidgetActions {
    const val ACTION_NOOP       = "com.spizganed.quickbuds.action.NOOP"
    const val ACTION_ANC_SELECT = "com.spizganed.quickbuds.action.ANC_SELECT"
    const val ACTION_GAME_TOGGLE = "com.spizganed.quickbuds.action.GAME_TOGGLE"
    /**
     * Controls page: a quick button, target in [EXTRA_ANC_TARGET]. "anc" opens the
     * level picker; "trans" / "adapt" select that mode, or Off when it is the current one.
     */
    const val ACTION_QUICK      = "com.spizganed.quickbuds.action.QUICK"

    /** Widget background tap: carries the page a double tap switches to; a single tap does nothing. */
    const val ACTION_OPEN_APP   = "com.spizganed.quickbuds.action.OPEN_APP"

    const val EXTRA_ANC_TARGET = "anc_target"
    /**
     * The 2x2 page to switch to. It marks a double-tap
     * wrapped tap: the receiver waits for a second tap before running the action.
     */
    const val EXTRA_PAGE = "page"
}
