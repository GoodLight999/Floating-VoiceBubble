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
        correctionLineBreakMode = LineBreakMode.NONE,
        recognitionRepairMode = RecognitionRepairMode.NORMAL,
    )

    @Test
    fun gmailCanEnablePoliteModeWithoutFreezingOtherGlobalPreferences() {
        val profile = AppCorrectionProfile(
            packageName = "com.google.android.gm",
            register = ProfileRegister.POLITE,
        )
        val effective = profile.applyTo(
            global.copy(
                correctionAddPeriods = false,
                correctionLineBreakMode = LineBreakMode.SMART,
                recognitionRepairMode = RecognitionRepairMode.STRONG,
            ),
        )
        assertTrue(effective.correctionPolite)
        assertFalse(effective.correctionBusinessPolite)
        assertTrue(effective.correctionAddCommas)
        assertFalse(effective.correctionAddPeriods)
        assertTrue(effective.correctionRemoveFillers)
        assertEquals(LineBreakMode.SMART, effective.correctionLineBreakMode)
        assertEquals(RecognitionRepairMode.STRONG, effective.recognitionRepairMode)
        assertEquals(CorrectionMode.BYOK, effective.correctionMode)
    }

    @Test
    fun perAppFormattingAndRepairCanOverrideWithoutFreezingOtherFields() {
        val profile = AppCorrectionProfile(
            packageName = "com.google.android.gm",
            lineBreakMode = ProfileLineBreakMode.SMART_SPACED,
            recognitionRepairMode = ProfileRecognitionRepairMode.STRONG,
        )
        val effective = profile.applyTo(global.copy(correctionAddPeriods = false))
        assertEquals(LineBreakMode.SMART_SPACED, effective.correctionLineBreakMode)
        assertEquals(RecognitionRepairMode.STRONG, effective.recognitionRepairMode)
        assertFalse(effective.correctionAddPeriods)
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
            lineBreakMode = ProfileLineBreakMode.SMART_SPACED,
            recognitionRepairMode = ProfileRecognitionRepairMode.STRONG,
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
            lineBreakMode = ProfileLineBreakMode.SMART_SPACED,
            recognitionRepairMode = ProfileRecognitionRepairMode.STRONG,
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
                                for (lineBreakMode in ProfileLineBreakMode.entries) {
                                    for (repairMode in ProfileRecognitionRepairMode.entries) {
                                        val profile = AppCorrectionProfile(
                                            packageName = "com.example.exhaustive",
                                            enabled = enabled,
                                            addCommas = commas,
                                            addPeriods = periods,
                                            removeFillers = fillers,
                                            register = register,
                                            correctionMode = mode,
                                            lineBreakMode = lineBreakMode,
                                            recognitionRepairMode = repairMode,
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
                                                correctionLineBreakMode = LineBreakMode.SMART,
                                                recognitionRepairMode = RecognitionRepairMode.STRONG,
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
            }
        }
        assertEquals(17280, checked)
    }

    @Test
    fun codecFallsBackPerFieldForFutureOrCorruptEnumValues() {
        val decoded = AppCorrectionProfileCodec.decode(
            "com.example.app",
            "v1|enabled=1|commas=NOPE|periods=OFF|fillers=ON|register=WHAT|mode=BYOK|breaks=BAD|repair=WHAT",
        )!!
        assertEquals(ProfileToggle.INHERIT, decoded.addCommas)
        assertEquals(ProfileToggle.OFF, decoded.addPeriods)
        assertEquals(ProfileToggle.ON, decoded.removeFillers)
        assertEquals(ProfileRegister.INHERIT, decoded.register)
        assertEquals(ProfileCorrectionMode.BYOK, decoded.correctionMode)
        assertEquals(ProfileLineBreakMode.INHERIT, decoded.lineBreakMode)
        assertEquals(ProfileRecognitionRepairMode.INHERIT, decoded.recognitionRepairMode)
    }

    @Test
    fun oldV1ProfilesWithoutNewFieldsRemainBackwardCompatible() {
        val decoded = AppCorrectionProfileCodec.decode(
            "com.example.old",
            "v1|enabled=1|commas=ON|periods=OFF|fillers=ON|register=POLITE|mode=BYOK",
        )!!
        assertEquals(ProfileLineBreakMode.INHERIT, decoded.lineBreakMode)
        assertEquals(ProfileRecognitionRepairMode.INHERIT, decoded.recognitionRepairMode)
    }

    @Test
    fun packageValidationRejectsPathsAndSingleTokens() {
        assertTrue(AppCorrectionProfileCodec.isValidPackageName("com.google.android.gm"))
        assertFalse(AppCorrectionProfileCodec.isValidPackageName("gmail"))
        assertFalse(AppCorrectionProfileCodec.isValidPackageName("../../escape"))
        assertFalse(AppCorrectionProfileCodec.isValidPackageName("com.example/app"))
    }
}