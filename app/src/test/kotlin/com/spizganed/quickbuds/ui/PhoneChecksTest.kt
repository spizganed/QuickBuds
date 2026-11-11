package com.spizganed.quickbuds.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone check uses the manufacturer string of OnePlus, OPPO and realme in any letter case. */
class PhoneChecksTest {

    @Test fun onePlusInAnyCaseIsTrue() {
        assertTrue(PhoneChecks.isColorOsPhone("OnePlus"))
        assertTrue(PhoneChecks.isColorOsPhone("oneplus"))
        assertTrue(PhoneChecks.isColorOsPhone("ONEPLUS"))
    }

    @Test fun oppoInAnyCaseIsTrue() {
        assertTrue(PhoneChecks.isColorOsPhone("OPPO"))
        assertTrue(PhoneChecks.isColorOsPhone("oppo"))
    }

    @Test fun realmeInAnyCaseIsTrue() {
        assertTrue(PhoneChecks.isColorOsPhone("realme"))
        assertTrue(PhoneChecks.isColorOsPhone("Realme"))
    }

    @Test fun otherManufacturersAreFalse() {
        assertFalse(PhoneChecks.isColorOsPhone("Google"))
        assertFalse(PhoneChecks.isColorOsPhone("Nothing"))
        assertFalse(PhoneChecks.isColorOsPhone("samsung"))
        assertFalse(PhoneChecks.isColorOsPhone(""))
        assertFalse(PhoneChecks.isColorOsPhone(null))
    }
}
