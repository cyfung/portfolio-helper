package com.portfoliohelper.service

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal object MonteCarloParallel {
    private val parallelism = configuredParallelism()
    private val dispatcher = Executors
        .newFixedThreadPool(parallelism, MonteCarloThreadFactory())
        .asCoroutineDispatcher()

    suspend fun parallelForRange(size: Int, action: (Int) -> Unit) {
        if (size <= 0) return
        val workers = minOf(size, parallelism)
        coroutineScope {
            (0 until workers).map { worker ->
                val start = worker * size / workers
                val end = (worker + 1) * size / workers
                async(dispatcher) {
                    for (index in start until end) {
                        action(index)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun <T> parallelMapRange(size: Int, transform: suspend (Int) -> T): List<T> {
        if (size <= 0) return emptyList()
        val workers = minOf(size, parallelism)
        val results = arrayOfNulls<Any>(size)
        coroutineScope {
            (0 until workers).map { worker ->
                val start = worker * size / workers
                val end = (worker + 1) * size / workers
                async(dispatcher) {
                    for (index in start until end) {
                        results[index] = transform(index)
                    }
                }
            }.awaitAll()
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as T }
    }

    suspend fun <T, R> parallelMap(items: List<T>, transform: suspend (T) -> R): List<R> =
        parallelMapRange(items.size) { index -> transform(items[index]) }

    suspend fun <T, R> parallelMapIndexed(items: List<T>, transform: suspend (Int, T) -> R): List<R> =
        parallelMapRange(items.size) { index -> transform(index, items[index]) }

    private fun configuredParallelism(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 2
        val configured = System.getProperty("portfoliohelper.monteCarlo.parallelism")
            ?.toIntOrNull()
            ?: System.getenv("PORTFOLIOHELPER_MONTE_CARLO_PARALLELISM")?.toIntOrNull()
        return configured?.coerceAtLeast(1) ?: available
    }

    private class MonteCarloThreadFactory : ThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "monte-carlo-${counter.getAndIncrement()}").apply {
                isDaemon = true
            }
    }
}
