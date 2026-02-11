package com.portfoliohelper.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory

/**
 * Broadcasts portfolio update events to SSE clients.
 * Uses SharedFlow to allow multiple subscribers to receive reload notifications.
 */
object PortfolioUpdateBroadcaster {
    private val logger = LoggerFactory.getLogger(PortfolioUpdateBroadcaster::class.java)

    private val _reloadEvents = MutableSharedFlow<ReloadEvent>(
        replay = 0,
        extraBufferCapacity = 10
    )

    /**
     * Flow of reload events that SSE clients can subscribe to.
     */
    val reloadEvents: SharedFlow<ReloadEvent> = _reloadEvents.asSharedFlow()

    /**
     * Broadcast a reload event to all connected SSE clients.
     * This signals that the portfolio structure has changed and clients should reload.
     */
    suspend fun broadcastReload() {
        logger.info("Broadcasting portfolio reload event to all SSE clients")
        _reloadEvents.emit(ReloadEvent())
    }

    /**
     * Event indicating that the portfolio should be reloaded.
     */
    data class ReloadEvent(val timestamp: Long = System.currentTimeMillis())
}
