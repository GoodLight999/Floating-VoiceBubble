package com.goodlight.floatingvoicebubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCorrectionProfileTest {
    private val global = AppSettings(
        correctionMode = CorrectionMode.BYOK,
        correctionAddCommas = true,
        correctionAddPeriods = true,
        correctionRemoveFillers = true,
        correctionPolite = false,
        correctionBusinessPolite = false,
    )

    @Test
    fun gmailCanEnablePoliteModeWithoutFreezingOtherGlobalPreferences() {
        val profile = AppCorrectionProfile(
            packageName = "com.google.android.gm",
            register = ProfileRegister.POLITE,
        )
        val effective = profile.applyTo(global.copy(correctionAddPeriods = false))
        assertTrue(effective.correctionPolite)
        assertFalse(effective.correctionBusinessPolite)
        assertTrue(effective.correctionAddCommas)
        assertFalse(effective.correctionAddPeriods)
        assertTrue(effective.correctionRemoveFillers)
        assertEquals(CorrectionMode.BYOK, effective.correctionMode)
    }

    @Test
    fun explicitPlainOverridesGlobalPoliteness() {
        val profile = AppCorrectionProfile(
            packageName = "com.example.chat",
            register = ProfileRegister.PLAIN,
        )
        val effective = profile.applyTo(global.copy(correctionBusinessPolite = true))
        assertFalse(effective.correctionPolite)
        assertFalse(effective.correctionBusinessPolite)
    }

    @Test
    fun individualBooleanAndCorrectionEngineOverridesAreIndependent() {
        val profile = AppCorrectionProfile(
            packageName = "com.example.notes",
            addPeriods = ProfileToggle.OFF,
            removeFillers = ProfileToggle.OFF,
            correctionMode = ProfileCorrectionMode.GEMMA,
        )
        val effective = profile.applyTo(global)
        assertTrue(effective.correctionAddCommas)
        assertFalse(effective.correctionAddPeriods)
        assertFalse(effective.correctionRemoveFillers)
        assertEquals(CorrectionMode.GEMMA, effective.correctionMode)
    }

    @Test
    fun disabledProfileIsPureGlobalSettings() {
        val profile = AppCorrectionProfile(
            packageName = "com.example.disabled",
            enabled = false,
            addCommas = ProfileToggle.OFF,
            register = ProfileRegister.BUSINESS,
            correctionMode = ProfileCorrectionMode.NONE,
        )
        assertEquals(global, profile.applyTo(global))
    }

    @Test
    fun codecRoundTripsAndSafelyRejectsUnknownVersion() {
        val original = AppCorrectionProfile(
            packageName = "com.google.android.gm",
            enabled = true,
            addCommas = ProfileToggle.OFF,
            addPeriods = ProfileToggle.INHERIT,
            removeFillers = ProfileToggle.ON,
            register = ProfileRegister.BUSINESS,
            correctionMode = ProfileCorrectionMode.AUTO,
        )
        assertEquals(
            original,
            AppCorrectionProfileCodec.decode(original.packageName, AppCorrectionProfileCodec.encode(original)),
        )
        assertNull(AppCorrectionProfileCodec.decode(original.packageName, "v999|enabled=1"))
    }

    @Test
    fun everyProfileCombinationRoundTripsAndKeepsRegisterExclusive() {
        var checked = 0
        for (enabled in listOf(false, true)) {
            for (commas in ProfileToggle.entries) {
                for (periods in ProfileToggle.entries) {
                    for (fillers in ProfileToggle.entries) {
                        for (register in ProfileRegister.entries) {
                            for (mode in ProfileCorrectionMode.entries) {
                                val profile = AppCorrectionProfile(
                                    packageName = "com.example.exhaustive",
                                    enabled = enabled,
                                    addCommas = commas,
                                    addPeriods = periods,
                                    removeFillers = fillers,
                                    register = register,
                                    correctionMode = mode,
                                )
                                val decoded = AppCorrectionProfileCodec.decode(
                                    profile.packageName,
                                    AppCorrectionProfileCodec.encode(profile),
                                )
                                assertEquals(profile, decoded)

                                val effective = profile.applyTo(
                                    global.copy(
                                        correctionPolite = true,
                                        correctionBusinessPolite = false,
                                    ),
                                )
                                assertFalse(
                                    "register must never resolve to polite and business simultaneously: $profile",
                                    effective.correctionPolite && effective.correctionBusinessPolite,
                                )
                                checked += 1
                            }
                        }
                    }
                }
            }
        }
        assertEquals(1080, checked)
    }

    @Test
    fun codecFallsBackPerFieldForFutureOrCorruptEnumValues() {
        val decoded = AppCorrectionProfileCodec.decode(
            "com.example.app",
            "v1|enabled=1|commas=NOPE|periods=OFF|fillers=ON|register=WHAT|mode=BYOK",
        )!!
        assertEquals(ProfileToggle.INHERIT, decoded.addCommas)
        assertEquals(ProfileToggle.OFF, decoded.addPeriods)
        assertEquals(ProfileToggle.ON, decoded.removeFillers)
        assertEquals(ProfileRegister.INHERIT, decoded.register)
        assertEquals(ProfileCorrectionMode.BYOK, decoded.correctionMode)
    }

    @Test
    fun packageValidationRejectsPathsAndSingleTokens() {
        assertTrue(AppCorrectionProfileCodec.isValidPackageName("com.google.android.gm"))
        assertFalse(AppCorrectionProfileCodec.isValidPackageName("gmail"))
        assertFalse(AppCorrectionProfileCodec.isValidPackageName("../../escape"))
        assertFalse(AppCorrectionProfileCodec.isValidPackageName("com.example/app"))
    }
}
