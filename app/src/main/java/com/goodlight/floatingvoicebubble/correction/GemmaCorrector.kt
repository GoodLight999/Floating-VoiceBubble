package com.goodlight.floatingvoicebubble.correction

import android.content.Context
import com.goodlight.floatingvoicebubble.GemmaBackend
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

class GemmaCorrector(
    context: Context,
    private val modelReference: String,
    private val backendPreference: GemmaBackend,
) : TextCorrector {
    override val id: String = "gemma:${GemmaModelSource.displayName(context, modelReference)}"
    private val appContext = context.applicationContext

    override fun correct(request: CorrectionRequest): String =
        GemmaEnginePool.withEngine(appContext, modelReference, backendPreference) { engine ->
            val config = ConversationConfig(
                systemInstruction = Contents.of(CorrectionPrompt.system(request)),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            )
            engine.createConversation(config).use { conversation ->
                conversation.sendMessage(CorrectionPrompt.user(request)).toString().trim()
            }
        }
}

private object GemmaEnginePool {
    private data class Loaded(
        val key: String,
        val engine: Engine,
        val source: GemmaModelSource.Opened,
    )

    private var loaded: Loaded? = null

    @Synchronized
    fun <T> withEngine(context: Context, modelReference: String, preference: GemmaBackend, block: (Engine) -> T): T {
        require(GemmaModelSource.isAvailable(context, modelReference)) { "Gemma model file is not configured or unavailable" }
        val candidates = when (preference) {
            GemmaBackend.CPU -> listOf(Backend.CPU())
            GemmaBackend.GPU -> listOf(Backend.GPU())
            GemmaBackend.AUTO -> listOf(Backend.GPU(), Backend.CPU())
        }
        var lastFailure: Throwable? = null
        for (backend in candidates) {
            val sourceKey = if (GemmaModelSource.isExternal(modelReference)) "content:$modelReference" else "file:$modelReference"
            val key = "$sourceKey:${backend.name}"
            var newlyOpened: GemmaModelSource.Opened? = null
            var initializingEngine: Engine? = null
            try {
                val engine = if (loaded?.key == key) {
                    loaded!!.engine
                } else {
                    closeLoaded()
                    val source = GemmaModelSource.openForEngine(context, modelReference)
                    newlyOpened = source
                    Engine(
                        EngineConfig(
                            modelPath = source.enginePath,
                            backend = backend,
                            cacheDir = java.io.File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
                        )
                    ).also { created ->
                        initializingEngine = created
                        created.initialize()
                        loaded = Loaded(key, created, source)
                        initializingEngine = null
                        newlyOpened = null
                    }
                }
                return block(engine)
            } catch (failure: Throwable) {
                lastFailure = failure
                runCatching { initializingEngine?.close() }
                newlyOpened?.close()
                if (loaded?.key == key) closeLoaded()
            }
        }
        throw IllegalStateException("Gemma initialization/inference failed", lastFailure)
    }

    private fun closeLoaded() {
        val current = loaded ?: return
        loaded = null
        runCatching { current.engine.close() }
        current.source.close()
    }
}
