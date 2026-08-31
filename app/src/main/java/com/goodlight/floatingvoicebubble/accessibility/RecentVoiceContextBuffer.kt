package com.goodlight.floatingvoicebubble.accessibility

/** In-memory, non-persistent context from Floating VoiceBubble's own successful commits only. */
internal data class VoiceContextKey(
    val packageName: String,
    val fieldId: Int,
    val fieldName: String?,
)

internal class RecentVoiceContextBuffer(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxUtterances: Int = DEFAULT_MAX_UTTERANCES,
    private val maxSingleChars: Int = DEFAULT_MAX_SINGLE_CHARS,
    private val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
) {
    private data class Entry(val text: String, val atMs: Long)
    private val entries = LinkedHashMap<VoiceContextKey, MutableList<Entry>>()

    fun add(key: VoiceContextKey, text: String, nowMs: Long = System.currentTimeMillis()) {
        val normalized = text.trim().takeLast(maxSingleChars)
        if (normalized.isBlank()) return
        prune(nowMs)
        val list = entries.getOrPut(key) { mutableListOf() }
        if (list.lastOrNull()?.text != normalized) list += Entry(normalized, nowMs)
        while (list.size > maxUtterances) list.removeAt(0)
    }

    fun build(
        key: VoiceContextKey,
        currentEditor: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        prune(nowMs)
        val recent = entries[key].orEmpty()
            .filterNot { currentEditor.isNotBlank() && currentEditor.contains(it.text) }
            .takeLast(maxUtterances)
            .joinToString("\n") { it.text }
        return buildString {
            if (recent.isNotBlank()) {
                appendLine("[RECENT_VOICE_INPUT]")
                appendLine(recent)
            }
            if (currentEditor.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("[CURRENT_EDITOR]")
                append(currentEditor)
            }
        }.takeLast(maxContextChars)
    }

    fun clear() = entries.clear()

    private fun prune(nowMs: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            item.value.removeAll { nowMs - it.atMs > ttlMs }
            if (item.value.isEmpty()) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_MAX_UTTERANCES = 3
        const val DEFAULT_MAX_SINGLE_CHARS = 700
        const val DEFAULT_MAX_CONTEXT_CHARS = 2_000
    }
}
