package io.github.daermond.artoptimizer

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AdbOperationRunnerTest {
    @Test fun concurrentRefreshProbeAndShellOperationsAreSerialized() = runBlocking {
        val runner = AdbOperationRunner()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val results = (1..12).map { number ->
            async {
                runner.run(5_000, { fail("Successful operation must not close the transport") }) {
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    Thread.sleep(10)
                    active.decrementAndGet()
                    number
                }
            }
        }.awaitAll()
        assertEquals(1, maximum.get())
        assertEquals((1..12).toList(), results)
    }

    @Test fun failedCommandClosesTransportWithoutReplayingIt() = runBlocking {
        val attempts = AtomicInteger()
        val aborted = AtomicInteger()
        try {
            AdbOperationRunner().run(5_000, { aborted.incrementAndGet() }) {
                attempts.incrementAndGet()
                throw IOException("TLS write returned -1")
            }
            fail("Expected connection failure")
        } catch (error: AdbConnectionException) {
            assertTrue(error.message.orEmpty().contains("Retry"))
        }
        assertEquals(1, attempts.get())
        assertEquals(1, aborted.get())
    }

    @Test fun deadlineClosesBlockedSocketAndReleasesNextOperation() = runBlocking {
        val runner = AdbOperationRunner()
        val socketClosed = CountDownLatch(1)
        try {
            runner.run(100, { socketClosed.countDown() }) {
                check(socketClosed.await(5, TimeUnit.SECONDS))
                throw IOException("Socket closed")
            }
            fail("Expected timeout")
        } catch (error: AdbConnectionException) {
            assertTrue(error.message.orEmpty().contains("timed out"))
        }
        assertEquals(0L, socketClosed.count)
        assertEquals("ready", runner.run(5_000, {}) { "ready" })
    }

    @Test fun cancellationClosesBlockedSocket() = runBlocking {
        val runner = AdbOperationRunner()
        val started = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        val task = launch {
            runner.run(5_000, { socketClosed.countDown() }) {
                started.countDown()
                check(socketClosed.await(5, TimeUnit.SECONDS))
                throw IOException("Socket closed")
            }
        }
        while (started.count != 0L) delay(1)
        task.cancel()
        task.join()
        assertEquals(0L, socketClosed.count)
    }
}
