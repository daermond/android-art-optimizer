package io.github.daermond.artoptimizer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AdbConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One blocking ADB operation at a time; closing its transport interrupts blocked socket reads. */
class AdbOperationRunner {
    private val mutex = Mutex()

    suspend fun <T> run(timeoutMillis: Long, abort: () -> Unit, operation: () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                supervisorScope {
                    val worker = async { operation() }
                    try {
                        withTimeout(timeoutMillis) { worker.await() }
                    } catch (timeout: TimeoutCancellationException) {
                        runCatching(abort)
                        throw AdbConnectionException("ADB command timed out. Retry the connection.", timeout)
                    } catch (cancelled: CancellationException) {
                        runCatching(abort)
                        throw cancelled
                    } catch (error: Exception) {
                        runCatching(abort)
                        throw AdbConnectionException("ADB connection interrupted. Retry the connection.", error)
                    } finally {
                        worker.cancel()
                    }
                }
            }
        }
}
