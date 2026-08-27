package com.goodlight.floatingvoicebubble

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.benchmark.BenchmarkReferenceStore
import com.goodlight.floatingvoicebubble.benchmark.ExternalAsrResultStore
import com.goodlight.floatingvoicebubble.diagnostics.SelfDiagnostics
import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.trace.FinalizationTrace
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import com.k2fsa.sherpa.onnx.VersionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun launcherActivityStarts() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("Launcher intent missing")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(launchIntent)
        try {
            assertTrue(activity is MainActivity)
            assertFalse(activity.isFinishing)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun advancedManagementActivityStarts() {
        val intent = Intent(context, AdvancedToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            assertTrue(activity is AdvancedToolsActivity)
            assertFalse(activity.isFinishing)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun correctionSetupActivityStarts() {
        val intent = Intent(context, CorrectionSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            assertTrue(activity is CorrectionSetupActivity)
            assertFalse(activity.isFinishing)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun recognitionSetupActivityStarts() {
        val intent = Intent(context, RecognitionSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            assertTrue(activity is RecognitionSetupActivity)
            assertFalse(activity.isFinishing)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun appProfilesActivityStarts() {
        val intent = Intent(context, AppProfilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            assertTrue(activity is AppProfilesActivity)
            assertFalse(activity.isFinishing)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun appProfileStorePersistsAndAppliesOverrides() {
        val packageName = "com.example.voicebubble.instrumentation${System.nanoTime()}"
        val store = AppProfileStore(context)
        val profile = AppCorrectionProfile(
            packageName = packageName,
            addPeriods = ProfileToggle.OFF,
            register = ProfileRegister.POLITE,
            correctionMode = ProfileCorrectionMode.GEMMA,
            lineBreakMode = ProfileLineBreakMode.SMART_SPACED,
        )
        try {
            store.save(profile)
            assertEquals(profile, AppProfileStore(context).profile(packageName))
            val effective = store.effectiveSettings(
                AppSettings(
                    correctionMode = CorrectionMode.BYOK,
                    correctionAddPeriods = true,
                    correctionPolite = false,
                    correctionBusinessPolite = false,
                    correctionLineBreakMode = LineBreakMode.NONE,
                ),
                packageName,
            )
            assertFalse(effective.correctionAddPeriods)
            assertTrue(effective.correctionPolite)
            assertFalse(effective.correctionBusinessPolite)
            assertEquals(CorrectionMode.GEMMA, effective.correctionMode)
            assertEquals(LineBreakMode.SMART_SPACED, effective.correctionLineBreakMode)
        } finally {
            store.delete(packageName)
        }
    }

    @Test
    fun newCorrectionSettingsPersistAcrossStoreInstances() {
        val store = SettingsStore(context)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    reasoningEffort = ReasoningEffort.HIGH,
                    correctionLineBreakMode = LineBreakMode.SMART_SPACED,
                )
            }
            val reloaded = SettingsStore(context).load()
            assertEquals(ReasoningEffort.HIGH, reloaded.reasoningEffort)
            assertEquals(LineBreakMode.SMART_SPACED, reloaded.correctionLineBreakMode)
        } finally {
            store.update { previous }
        }
    }

    @Test
    fun sherpaNativeLibraryLoadsOnAndroidRuntime() {
        assertTrue(VersionInfo.version.isNotBlank())
        assertTrue(VersionInfo.gitSha1.isNotBlank())
    }

    @Test
    fun keystoreBackedByokSecretRoundTrips() {
        val store = SettingsStore(context)
        val previous = store.apiKey()
        val secret = "diagnostic-${System.nanoTime()}"
        store.setApiKey(secret)
        try {
            assertEquals(secret, SettingsStore(context).apiKey())
        } finally {
            store.setApiKey(previous)
        }
    }

    @Test
    fun keystoreBackedGeminiTranscribeSecretRoundTrips() {
        val store = SettingsStore(context)
        val previous = store.geminiTranscribeApiKey()
        val secret = "gemini-transcribe-${System.nanoTime()}"
        store.setGeminiTranscribeApiKey(secret)
        try {
            assertEquals(secret, SettingsStore(context).geminiTranscribeApiKey())
        } finally {
            store.setGeminiTranscribeApiKey(previous)
        }
    }

    @Test
    fun automaticDiagnosticsRunsAndRedactsSecrets() {
        val store = SettingsStore(context)
        val previousByok = store.apiKey()
        val previousGemini = store.geminiTranscribeApiKey()
        val byokSentinel = "SUPER_SECRET_DIAGNOSTIC_${System.nanoTime()}"
        val geminiSentinel = "SUPER_SECRET_GEMINI_${System.nanoTime()}"
        store.setApiKey(byokSentinel)
        store.setGeminiTranscribeApiKey(geminiSentinel)
        try {
            val report = SelfDiagnostics(context, store).run(includeExternalProbes = false)
            val ids = report.items.map { it.id }.toSet()
            assertTrue("offline-cloud-block" in ids)
            assertTrue("offline-recognition-policy" in ids)
            assertTrue("gemini-transcribe-readiness" in ids)
            assertTrue("sherpa-jni" in ids)
            assertTrue("final-recognition-readiness" in ids)
            assertTrue("correction-output-integrity" in ids)
            assertTrue("dictionary-db" in ids)
            assertTrue("trace-storage" in ids)
            val json = report.toRedactedJson()
            assertFalse(json.contains(byokSentinel))
            assertFalse(json.contains(geminiSentinel))
            assertTrue(json.contains("offline-recognition-policy"))
            assertTrue(json.contains("gemini-transcribe-readiness"))
            assertTrue(json.contains("final-recognition-readiness"))
        } finally {
            store.setApiKey(previousByok)
            store.setGeminiTranscribeApiKey(previousGemini)
        }
    }

    @Test
    fun personalDictionaryPersistsSearchesExportsAndDeletes() {
        val token = "診断固有名詞${System.nanoTime()}"
        PersonalDictionary(context).use { dictionary ->
            val before = dictionary.count()
            try {
                dictionary.upsert(
                    DictionaryTerm(
                        term = token,
                        reading = "しんだんこゆうめいし",
                        aliases = listOf("診断別名$token"),
                        weight = 900,
                    )
                )
                assertTrue(dictionary.count() >= before + 1L)
                assertTrue(dictionary.relevantTerms("今日は${token}について話す").any { it.term == token })
                assertTrue(dictionary.search("診断別名$token").any { it.term == token })
                assertNotNull(dictionary.get(token))
                val tsv = dictionary.exportTsv()
                assertTrue(tsv.startsWith("term\treading\taliases\tweight"))
                assertTrue(tsv.contains(token))
                assertTrue(dictionary.delete(token))
                assertNull(dictionary.get(token))
            } finally {
                dictionary.delete(token)
            }
        }
    }

    @Test
    fun traceStoreUsesNoBackupAndWritesSessionMetadataAndAudio() {
        val store = SessionTraceStore(context)
        assertTrue(store.audioDir.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        val id = "instrumented-${System.nanoTime()}"
        val wav = File(store.audioDir, "$id.wav")
        wav.writeBytes(ByteArray(128) { index -> (index and 0xff).toByte() })
        val started = System.currentTimeMillis() - 250L
        store.save(
            FinalizationTrace(
                outcome = RecognitionOutcome(
                    sessionId = id,
                    rawTranscript = "テスト",
                    alternatives = listOf("テスト", "てすと"),
                    audioFile = wav,
                    startedAtMs = started,
                    recognitionFinishedAtMs = started + 120L,
                    recognizerKind = "instrumentation-fake",
                ),
                finalText = "テスト。",
                correctorId = "instrumentation-fake",
                correctionAccepted = true,
                correctionDistance = 0.1,
            ),
            enabled = true,
        )
        val json = File(store.audioDir, "$id.json")
        try {
            assertTrue(wav.isFile)
            assertTrue(json.isFile)
            val text = json.readText(Charsets.UTF_8)
            assertTrue(text.contains("\"sessionId\": \"$id\"") || text.contains("\"sessionId\":\"$id\""))
            assertTrue(text.contains("instrumentation-fake"))
            assertTrue(text.contains("finalAsr"))
        } finally {
            json.delete()
            wav.delete()
        }
    }

    @Test
    fun benchmarkGroundTruthRoundTripsThroughNoBackupStorageAndTemplate() {
        val traces = SessionTraceStore(context)
        val references = BenchmarkReferenceStore(context)
        val id = "reference-${System.nanoTime()}"
        val metadata = File(traces.audioDir, "$id.json")
        metadata.writeText(
            """{"sessionId":"$id","liveRawTranscript":"ライブ誤認識","rawTranscript":"ライブ誤認識"}""",
            Charsets.UTF_8,
        )
        try {
            val result = references.importText(
                "sessionId\tliveTranscript\treference\n$id\tライブ誤認識\t正しい文字起こし\n"
            )
            assertEquals(1, result.imported)
            assertEquals(0, result.skipped)
            assertEquals("正しい文字起こし", references.get(id))
            assertTrue(references.count() >= 1)

            val template = references.exportTemplate(limit = 30)
            assertTrue(template.contains("sessionId\tliveTranscript\treference"))
            assertTrue(template.contains("$id\tライブ誤認識\t正しい文字起こし"))
        } finally {
            references.set(id, "")
            metadata.delete()
        }
    }

    @Test
    fun externalAsrTranscriptScoresOnlyAgainstHumanGroundTruth() {
        val references = BenchmarkReferenceStore(context)
        val external = ExternalAsrResultStore(context)
        val id = "external-${System.nanoTime()}"
        val system = "Instrumented-${System.nanoTime()}"
        try {
            references.set(id, "今日はガンダムを見る")
            external.set(id, system, "今日はガンダムを見る")
            val score = external.scoreAll().first { it.system == system }
            assertEquals(1, score.labeled)
            assertEquals(0.0, score.averageContentCer!!, 0.0)
            assertEquals(0.0, score.averageStrictCer!!, 0.0)
        } finally {
            external.set(id, system, "")
            references.set(id, "")
        }
    }
}
