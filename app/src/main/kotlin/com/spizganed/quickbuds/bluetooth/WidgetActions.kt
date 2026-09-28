package com.spizganed.quickbuds.bluetooth

/** Action strings broadcast between the widget receiver, provider, and service. */
object WidgetActions {
    const val ACTION_NOOP       = "com.spizganed.quickbuds.action.NOOP"
    const val ACTION_ANC_SELECT = "com.spizganed.quickbuds.action.ANC_SELECT"
    const val ACTION_GAME_TOGGLE = "com.spizganed.quickbuds.action.GAME_TOGGLE"
    /**
     * Controls page ([USER] 2026-09-28): a quick button, target in [EXTRA_ANC_TARGET]. "anc" opens the
     * level picker; "trans" / "adapt" select that mode, or Off when it is the current one.
     */
    const val ACTION_QUICK      = "com.spizganed.quickbuds.action.QUICK"

    /** 2x2 widget: show the page in [EXTRA_PAGE] (the swap button). */
    const val ACTION_PAGE_SWAP  = "com.spizganed.quickbuds.action.PAGE_SWAP"
    /** 2x2 widget background tap in double-tap mode: opens the app if "Open app on tap" is on. */
    const val ACTION_OPEN_APP   = "com.spizganed.quickbuds.action.OPEN_APP"

    const val EXTRA_ANC_TARGET = "anc_target"
    /**
     * The 2x2 page to switch to. On any action but [ACTION_PAGE_SWAP] it marks a double-tap
     * wrapped tap: the receiver waits for a second tap before running the action.
     */
    const val EXTRA_PAGE = "page"
}
