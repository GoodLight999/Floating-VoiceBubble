package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpConnectionDeadlineTest {
    @Test
    fun deadlineDisconnectsBlockedConnectionAndClassifiesTimeout() {
        val connection = BlockingConnection(URL("https://example.invalid/test"))
        val started = System.nanoTime()
        try {
            HttpConnectionDeadline.run(connection, 100L) {
                connection.inputStream.read()
            }
            fail("SocketTimeoutException expected")
        } catch (failure: SocketTimeoutException) {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue("deadline did not fire promptly: ${elapsedMs}ms", elapsedMs < 2_000L)
            assertTrue(connection.disconnected.get())
        }
    }

    private class BlockingConnection(url: URL) : HttpURLConnection(url) {
        val disconnected = AtomicBoolean(false)
        private val released = CountDownLatch(1)

        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                released.await(5, TimeUnit.SECONDS)
                if (disconnected.get()) throw java.io.IOException("disconnected")
                return -1
            }
        }

        override fun disconnect() {
            disconnected.set(true)
            released.countDown()
        }

        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }
}
