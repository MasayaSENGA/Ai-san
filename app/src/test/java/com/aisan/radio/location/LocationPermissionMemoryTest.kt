package com.aisan.radio.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionMemoryTest {

    @Test
    fun deniedWithRationaleIsNotPermanent() {
        assertFalse(isPermanentlyDenied(rationaleBefore = false, rationaleAfter = true, deniedOnceBefore = false))
        assertFalse(isPermanentlyDenied(rationaleBefore = true, rationaleAfter = true, deniedOnceBefore = true))
    }

    @Test
    fun secondDenialOrDontAskAgainIsPermanent() {
        assertTrue(isPermanentlyDenied(rationaleBefore = true, rationaleAfter = false, deniedOnceBefore = false))
        assertTrue(isPermanentlyDenied(rationaleBefore = true, rationaleAfter = false, deniedOnceBefore = true))
    }

    @Test
    fun automaticDenialAfterAnEarlierDenialIsPermanent() {
        assertTrue(isPermanentlyDenied(rationaleBefore = false, rationaleAfter = false, deniedOnceBefore = true))
    }

    @Test
    fun dismissingTheFirstDialogIsNotPermanent() {
        assertFalse(isPermanentlyDenied(rationaleBefore = false, rationaleAfter = false, deniedOnceBefore = false))
    }
}
