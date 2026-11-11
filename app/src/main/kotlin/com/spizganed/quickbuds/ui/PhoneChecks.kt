package com.spizganed.quickbuds.ui

/** Facts about the phone that change what the app shows. These functions are pure, so a JVM test can call them. */
object PhoneChecks {

    /** True when the phone's manufacturer is OnePlus, OPPO or realme, in any letter case. */
    fun isColorOsPhone(manufacturer: String?): Boolean =
        manufacturer != null && (
            manufacturer.equals("OnePlus", ignoreCase = true) ||
                manufacturer.equals("OPPO", ignoreCase = true) ||
                manufacturer.equals("realme", ignoreCase = true)
            )
}
