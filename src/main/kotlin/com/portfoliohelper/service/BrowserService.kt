package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI

object BrowserService {
    private val logger = LoggerFactory.getLogger(BrowserService::class.java)

    fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop()
                    .isSupported(Desktop.Action.BROWSE)
            ) {
                Desktop.getDesktop().browse(URI(url))
                logger.info("Opened browser to $url")
            } else {
                logger.warn("Desktop browsing is not supported on this platform")
            }
        } catch (e: Exception) {
            logger.error("Failed to open browser: ${e.message}", e)
        }
    }
}