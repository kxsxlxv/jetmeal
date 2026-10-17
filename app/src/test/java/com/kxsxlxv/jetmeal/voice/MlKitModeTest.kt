package com.kxsxlxv.jetmeal.voice

import org.junit.Assert.*
import org.junit.Test

class MlKitModeTest {
    @Test fun basicRemainsTheDefaultIndependentOfAdvancedAvailability() {
        assertEquals(MlKitMode.Basic, MlKitMode.entries.first())
        assertEquals(listOf("Basic","Advanced"),MlKitMode.entries.map { it.label })
        assertNotEquals(MlKitMode.Basic,MlKitMode.Advanced)
    }

    @Test fun unsupportedAdvancedMustNotBePresentedAsAnAvailableMode() {
        val advanced=MlKitMode.Advanced
        assertTrue(advanced.unavailableMessage().contains("недоступен"))
        assertTrue(advanced.unavailableMessage().contains("Basic"))
        assertTrue(advanced.selectionHint.contains("vivo X300"))
        assertFalse(advanced.unavailableMessage().contains("поддерживается на vivo"))
    }

    @Test fun downloadRequiresExplicitSystemAvailabilityAndConsent() {
        assertTrue(MlKitMode.Advanced.downloadableMessage().contains("Google"))
        assertTrue(MlKitMode.Basic.downloadableMessage().contains("Basic"))
    }
}
