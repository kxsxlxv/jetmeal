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
    @Test fun googleAicoreFeatureNotFoundIsDistinguishedFromAnUnrelatedError() {
        assertTrue(mlKitFeatureNotProvisioned("AICore error code 606-FEATURE_NOT_FOUND"))
        assertTrue(mlKitFeatureNotProvisioned("PREPARATION_ERROR: 606"))
        assertTrue(mlKitFeatureNotProvisioned("Feature not found"))
        assertFalse(mlKitFeatureNotProvisioned("Some HTTP error 500"))
        assertFalse(mlKitFeatureNotProvisioned(null))
        assertFalse(mlKitFeatureNotProvisioned("code 6060"))
    }

}
