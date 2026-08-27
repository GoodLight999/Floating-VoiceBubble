package com.goodlight.floatingvoicebubble.correction

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Enforces a wall-clock deadline around HttpURLConnection, including connect/write/header/body time.
 * HttpURLConnection's connectTimeout/readTimeout are phase-specific; neither alone bounds the whole
 * request. On deadline this guard disconnects the socket from a separate daemon thread so a cancelled
 * FinalizationEngine Future cannot leave an old provider call blocked in the background.
 */
internal object HttpConnectionDeadline {
    private const val RUNNING = 0
    private const val COMPLETED = 1
    private const val TIMED_OUT = 2

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "VoiceBubble-HttpDeadline").apply { isDaemon = true }
    }

    fun <T> run(connection: HttpURLConnection, timeoutMs: Long, block: () -> T): T {
        require(timeoutMs > 0L) { "HTTP deadline must be positive" }
        val state = AtomicInteger(RUNNING)
        val deadline = scheduler.schedule(
            {
                if (state.compareAndSet(RUNNING, TIMED_OUT)) {
                    runCatching { connection.disconnect() }
                }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )

        try {
            val result = block()
            if (state.compareAndSet(RUNNING, COMPLETED)) return result
            throw timeout(timeoutMs, null)
        } catch (failure: Throwable) {
            if (state.compareAndSet(RUNNING, COMPLETED)) throw failure
            if (state.get() == TIMED_OUT) throw timeout(timeoutMs, failure)
            throw failure
        } finally {
            deadline.cancel(false)
            runCatching { connection.disconnect() }
        }
    }

    private fun timeout(timeoutMs: Long, cause: Throwable?): SocketTimeoutException =
        SocketTimeoutException("HTTP call exceeded ${timeoutMs}ms deadline").also { timeout ->
            if (cause != null && cause !== timeout) runCatching { timeout.initCause(cause) }
        }
}
