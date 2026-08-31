package com.goodlight.floatingvoicebubble

import android.content.Context
import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionBackend
import com.goodlight.floatingvoicebubble.correction.CorrectionBackendResolver
import com.goodlight.floatingvoicebubble.correction.CorrectionCallException
import com.goodlight.floatingvoicebubble.correction.CorrectionPreferences
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.correction.ReasoningCapabilities
import com.goodlight.floatingvoicebubble.correction.ReasoningWireDescriptor
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticItem
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticStatus
import com.goodlight.floatingvoicebubble.model.GemmaModelSource

/**
 * Lightweight generation-endpoint probe kept deliberately separate from production-equivalent
 * FinalizationEngine diagnostics. It verifies provider routing/auth/model/response parsing without
 * N-best, dictionary, surrounding context, final-ASR, post-processing, or integrity decisions.
 *
 * Returned details are operational metadata only; no API key, request text, response text, or
 * surrounding content is included.
 */
object CorrectionApiReachabilityProbe {
    fun run(context: Context): DiagnosticItem {
        val appContext = context.applicationContext
        val store = SettingsStore(appContext)
        val global = store.load()
        val profiles = AppProfileStore(appContext)
        val recentPackage = profiles.recentPackages(limit = 1).firstOrNull()
        val settings = recentPackage?.let { profiles.effectiveSettings(global, it) } ?: global
        val backend = CorrectionBackendResolver.resolve(
            settings,
            GemmaModelSource.isAvailable(appContext, settings.gemmaModelPath),
        )

        if (backend != CorrectionBackend.BYOK) {
            return DiagnosticItem(
                id = "correction-api-reachability",
                status = DiagnosticStatus.SKIP,
                detail = "cloud correction is not the effective route",
            )
        }
        if (settings.byokModel.isBlank()) {
            return DiagnosticItem(
                id = "correction-api-reachability",
                status = DiagnosticStatus.FAIL,
                detail = "model is not configured",
            )
        }
        val key = store.apiKey().trim()
        if (key.isBlank()) {
            return DiagnosticItem(
                id = "correction-api-reachability",
                status = DiagnosticStatus.FAIL,
                detail = "credential is not configured",
            )
        }

        val resolved = runCatching { ByokEndpointResolver.resolve(settings.byokEndpoint) }
            .getOrElse {
                return DiagnosticItem(
                    id = "correction-api-reachability",
                    status = DiagnosticStatus.FAIL,
                    detail = "endpoint normalization failed; error=${it.javaClass.simpleName}",
                )
            }
        val endpoint = resolved.generationUrl.substringBefore('?').substringBefore('#')
        val wire = ReasoningWireDescriptor.describe(
            settings.byokEndpoint,
            settings.byokModel,
            settings.reasoningEffort,
        )
        val reasoning = ReasoningCapabilities.label(
            settings.byokEndpoint,
            settings.byokModel,
            settings.reasoningEffort,
        )
        val request = CorrectionRequest(
            rawTranscript = "疎通確認",
            alternatives = emptyList(),
            surroundingContext = "",
            dictionaryTerms = emptyList(),
            preferences = CorrectionPreferences(
                addCommas = false,
                addPeriods = false,
                removeFillers = false,
                lineBreakMode = LineBreakMode.NONE,
                recognitionRepairMode = RecognitionRepairMode.OFF,
            ),
        )

        val started = System.nanoTime()
        return try {
            val result = CloudCorrectorFactory.create(
                endpoint = settings.byokEndpoint,
                model = settings.byokModel,
                apiKey = key,
                reasoningEffort = settings.reasoningEffort,
            ).correctDetailed(request)
            check(result.text.isNotBlank()) { "provider returned no final text" }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            DiagnosticItem(
                id = "correction-api-reachability",
                status = DiagnosticStatus.PASS,
                detail = buildString {
                    append("provider=").append(resolved.protocol.name.lowercase())
                    append("; endpoint=").append(endpoint)
                    append("; model=").append(settings.byokModel)
                    append("; reasoning=").append(reasoning)
                    append("; wire=").append(wire)
                    append("; attempts=").append(result.metadata.attempts)
                    result.metadata.httpStatus?.let { append("; http=").append(it) }
                    append("; responsePresent=").append(result.metadata.responsePresent)
                    append("; elapsed=").append(elapsedMs).append("ms")
                    if (result.metadata.attemptTimings.isNotEmpty()) {
                        append("; timings=")
                        append(result.metadata.attemptTimings.joinToString(" | ") { it.redactedSummary() })
                    }
                },
            )
        } catch (failure: CorrectionCallException) {
            DiagnosticItem(
                id = "correction-api-reachability",
                status = DiagnosticStatus.FAIL,
                detail = buildString {
                    append("provider=").append(resolved.protocol.name.lowercase())
                    append("; endpoint=").append(endpoint)
                    append("; model=").append(settings.byokModel)
                    append("; reasoning=").append(reasoning)
                    append("; wire=").append(wire)
                    append("; stage=").append(failure.stage)
                    append("; attempts=").append(failure.attempts)
                    failure.httpStatus?.let { append("; http=").append(it) }
                    append("; responsePresent=").append(failure.responsePresent)
                    append("; errorClass=").append(failure.errorClass)
                    if (failure.attemptTimings.isNotEmpty()) {
                        append("; timings=")
                        append(failure.attemptTimings.joinToString(" | ") { it.redactedSummary() })
                    }
                },
            )
        } catch (failure: Throwable) {
            DiagnosticItem(
                id = "correction-api-reachability",
                status = DiagnosticStatus.FAIL,
                detail = buildString {
                    append("provider=").append(resolved.protocol.name.lowercase())
                    append("; endpoint=").append(endpoint)
                    append("; model=").append(settings.byokModel)
                    append("; reasoning=").append(reasoning)
                    append("; wire=").append(wire)
                    append("; errorClass=").append(failure.javaClass.simpleName)
                },
            )
        }
    }
}
