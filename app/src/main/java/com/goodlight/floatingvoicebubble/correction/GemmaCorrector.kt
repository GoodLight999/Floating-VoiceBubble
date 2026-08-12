package com.goodlight.floatingvoicebubble.correction

import android.content.Context
import com.goodlight.floatingvoicebubble.GemmaBackend
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

class GemmaCorrector(context: Context, private val modelPath: String, private val backendPreference: GemmaBackend) : TextCorrector {
    override val id: String = "gemma:${File(modelPath).name}"
    private val appContext = context.applicationContext

    override fun correct(request: CorrectionRequest): String = GemmaEnginePool.withEngine(appContext, modelPath, backendPreference) { engine ->
        val config = ConversationConfig(
            systemInstruction = Contents.of(CorrectionPrompt.SYSTEM.trim()),
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
        )
        engine.createConversation(config).use { conversation ->
            conversation.sendMessage(CorrectionPrompt.user(request)).toString().trim()
        }
    }
}

private object GemmaEnginePool {
    private data class Loaded(val key: String, val engine: Engine)
    private var loaded: Loaded? = null

    @Synchronized
    fun <T> withEngine(context: Context, modelPath: String, preference: GemmaBackend, block: (Engine) -> T): T {
        require(File(modelPath).isFile) { "Gemma model file is not configured" }
        val candidates = when (preference) {
            GemmaBackend.CPU -> listOf(Backend.CPU())
            GemmaBackend.GPU -> listOf(Backend.GPU())
            GemmaBackend.AUTO -> listOf(Backend.GPU(), Backend.CPU())
        }
        var lastFailure: Throwable? = null
        for (backend in candidates) {
            val key = "$modelPath:${backend.name}"
            try {
                val engine = if (loaded?.key == key) loaded!!.engine else {
                    loaded?.engine?.close()
                    Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend,
                            cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
                        )
                    ).also {
                        it.initialize()
                        loaded = Loaded(key, it)
                    }
                }
                return block(engine)
            } catch (failure: Throwable) {
                lastFailure = failure
                if (loaded?.key == key) {
                    runCatching { loaded?.engine?.close() }
                    loaded = null
                }
            }
        }
        throw IllegalStateException("Gemma initialization/inference failed", lastFailure)
    }
}
