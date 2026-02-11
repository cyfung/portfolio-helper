package com.portfoliohelper.service

import dorkbox.systemTray.MenuItem
import dorkbox.systemTray.Separator
import dorkbox.systemTray.SystemTray
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.awt.event.ActionListener
import java.net.URI

/**
 * Manages system tray icon lifecycle, menu, and browser integration.
 * Uses Dorkbox SystemTray for cross-platform native menu rendering with custom styling.
 */
object SystemTrayService {
    private val logger = LoggerFactory.getLogger(SystemTrayService::class.java)
    private var systemTray: SystemTray? = null

    /**
     * Initialize system tray with icon and menu.
     *
     * @param serverUrl URL of the web server (e.g., "http://localhost:8080")
     * @param onExit Callback invoked when user clicks "Exit" in menu
     * @return true if system tray is supported and initialized successfully, false otherwise
     */
    fun initialize(serverUrl: String, onExit: () -> Unit): Boolean {
        try {
            // Configure SystemTray before initialization
            SystemTray.AUTO_SIZE = true

            // Get SystemTray instance (returns null if not supported)
            val tray = SystemTray.get()
            if (tray == null) {
                logger.warn("System tray is not supported on this platform")
                return false
            }

            // Load and set tray icon
            val iconPath = extractTrayIcon()
            tray.setImage(iconPath)
            // Note: Not setting status/title - no tray.setStatus() call

            // Build menu structure
            val menu = tray.menu

            // Load browser icon for menu item
            val browserIconStream = javaClass.getResourceAsStream("/static/images/browser-icon.png")

            // "Open Browser" menu item with icon
            val openBrowserItem = if (browserIconStream != null) {
                MenuItem("Open Browser", browserIconStream, ActionListener {
                    logger.info("Opening browser to $serverUrl")
                    openBrowser(serverUrl)
                })
            } else {
                logger.warn("Browser icon not found, using text-only menu item")
                MenuItem("Open Browser", ActionListener {
                    logger.info("Opening browser to $serverUrl")
                    openBrowser(serverUrl)
                })
            }
            menu.add(openBrowserItem)

            // Separator for visual division
            menu.add(Separator())

            // "Exit" menu item
            menu.add(MenuItem("Exit", ActionListener {
                logger.info("Exit requested from system tray menu")
                onExit()
            }))

            // Store reference for cleanup
            systemTray = tray

            logger.info("System tray initialized successfully")
            return true

        } catch (e: Exception) {
            logger.error("Unexpected error initializing system tray: ${e.message}", e)
            return false
        }
    }

    /**
     * Display a desktop notification.
     *
     * @param title Notification title
     * @param message Notification message
     */
    fun showNotification(title: String, message: String) {
        if (systemTray == null) {
            logger.debug("Skipping notification (system tray not initialized)")
            return
        }

        try {
            // Dorkbox SystemTray doesn't have built-in notifications
            // Use AWT TrayIcon notification as fallback
            logger.debug("Notification requested: $title - $message")
            // Note: Could integrate Dorkbox Notify library here if needed

        } catch (e: Exception) {
            logger.warn("Failed to display notification: ${e.message}")
        }
    }

    /**
     * Remove system tray icon and clean up resources.
     */
    fun shutdown() {
        systemTray?.let { tray ->
            try {
                tray.shutdown()
                systemTray = null
                logger.info("System tray removed")
            } catch (e: Exception) {
                logger.warn("Error removing system tray icon: ${e.message}")
            }
        }
    }

    /**
     * Extract tray icon from resources to file.
     * Dorkbox SystemTray requires file path.
     */
    private fun extractTrayIcon(): String {
        return try {
            val iconStream = javaClass.getResourceAsStream("/static/images/tray-icon.png")

            if (iconStream != null) {
                // Create temp file for icon (deleted on JVM exit)
                val tempIcon = java.io.File.createTempFile("portfolio-helper-tray", ".png")
                tempIcon.deleteOnExit()

                // Copy resource to temp file
                iconStream.use { input ->
                    tempIcon.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                logger.debug("Extracted tray icon to: ${tempIcon.absolutePath}")
                tempIcon.absolutePath

            } else {
                logger.warn("Custom tray icon not found, creating default icon")
                createDefaultIconFile()
            }

        } catch (e: Exception) {
            logger.warn("Failed to extract tray icon: ${e.message}")
            createDefaultIconFile()
        }
    }

    /**
     * Create a simple default icon file.
     */
    private fun createDefaultIconFile(): String {
        val tempIcon = java.io.File.createTempFile("portfolio-helper-default", ".png")
        tempIcon.deleteOnExit()

        val size = 32
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g2d = img.createGraphics()

        // Draw a simple filled circle
        g2d.color = java.awt.Color(100, 100, 100)
        g2d.fillOval(4, 4, 24, 24)
        g2d.dispose()

        javax.imageio.ImageIO.write(img, "png", tempIcon)

        logger.debug("Created default tray icon at: ${tempIcon.absolutePath}")
        return tempIcon.absolutePath
    }

    /**
     * Open URL in default browser.
     */
    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
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
