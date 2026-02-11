package com.portfoliohelper.service

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.util.concurrent.TimeUnit

/**
 * Watches a CSV file for modifications and triggers callbacks when changes are detected.
 * Uses Java's WatchService API with debouncing to avoid triggering on incomplete writes.
 */
class CsvFileWatcher(
    private val csvPath: Path,
    private val debounceMillis: Long = 500
) {
    private val logger = LoggerFactory.getLogger(CsvFileWatcher::class.java)
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private var watchJob: Job? = null
    private var lastModified: Long = 0
    private val callbacks = mutableListOf<suspend () -> Unit>()

    /**
     * Register a callback to be invoked when the CSV file is modified.
     */
    fun onFileChanged(callback: suspend () -> Unit) {
        callbacks.add(callback)
    }

    /**
     * Start watching the CSV file for modifications.
     * Runs in a background coroutine.
     */
    fun start(scope: CoroutineScope) {
        val parentDir = csvPath.parent ?: throw IllegalArgumentException("CSV path must have a parent directory")

        // Register the parent directory for watching
        parentDir.register(watchService, ENTRY_MODIFY, ENTRY_CREATE)

        logger.info("Started watching CSV file: $csvPath")

        watchJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val key = watchService.poll(1, TimeUnit.SECONDS) ?: continue

                    for (event in key.pollEvents()) {
                        val kind = event.kind()

                        if (kind == OVERFLOW) {
                            logger.warn("Watch service overflow - some events may have been lost")
                            continue
                        }

                        @Suppress("UNCHECKED_CAST")
                        val eventPath = event.context() as Path
                        val fullPath = parentDir.resolve(eventPath)

                        // Check if this event is for our CSV file
                        if (fullPath == csvPath && (kind == ENTRY_MODIFY || kind == ENTRY_CREATE)) {
                            val now = System.currentTimeMillis()

                            // Debounce: only trigger if enough time has passed since last modification
                            if (now - lastModified >= debounceMillis) {
                                lastModified = now

                                // Wait for debounce period to ensure file write is complete
                                delay(debounceMillis)

                                logger.info("CSV file modified, triggering callbacks")
                                callbacks.forEach { callback ->
                                    try {
                                        callback()
                                    } catch (e: Exception) {
                                        logger.error("Error in file change callback", e)
                                    }
                                }
                            }
                        }
                    }

                    // Reset the key for next events
                    val valid = key.reset()
                    if (!valid) {
                        logger.warn("Watch key no longer valid, stopping watcher")
                        break
                    }
                }
            } catch (e: Exception) {
                logger.error("Error in CSV file watcher", e)
            }
        }
    }

    /**
     * Stop watching the CSV file and clean up resources.
     */
    fun stop() {
        watchJob?.cancel()
        watchService.close()
        logger.info("Stopped watching CSV file")
    }
}
