package com.goodlight.floatingvoicebubble.accessibility

/**
 * Main-thread reorder buffer for asynchronous finalization results.
 *
 * Work may resolve out of order, but callers only receive a contiguous prefix in registration
 * order. Discarding an earlier item (RAW commit/cancel) immediately releases any already-resolved
 * later items without letting a late result resurrect the discarded item.
 */
internal class OrderedResolutionQueue<K : Any, V : Any> {
    private val active = LinkedHashSet<K>()
    private val resolved = HashMap<K, V>()

    fun register(key: K) {
        check(active.add(key)) { "Duplicate pending key: $key" }
    }

    fun contains(key: K): Boolean = key in active

    /** First resolution wins. Duplicate/late resolutions are ignored. */
    fun resolve(key: K, value: V): List<Pair<K, V>> {
        if (key !in active || resolved.containsKey(key)) return emptyList()
        resolved[key] = value
        return drainReadyPrefix()
    }

    /** Removes one pending item and releases any now-unblocked resolved prefix. */
    fun discard(key: K): List<Pair<K, V>> {
        if (!active.remove(key)) return emptyList()
        resolved.remove(key)
        return drainReadyPrefix()
    }

    fun clear() {
        active.clear()
        resolved.clear()
    }

    fun size(): Int = active.size

    private fun drainReadyPrefix(): List<Pair<K, V>> {
        val ready = mutableListOf<Pair<K, V>>()
        while (true) {
            val first = active.firstOrNull() ?: break
            val value = resolved[first] ?: break
            active.remove(first)
            resolved.remove(first)
            ready += first to value
        }
        return ready
    }
}
