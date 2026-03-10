package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ImageIcon
import kotlin.system.exitProcess


object NewTrayService {
    private val logger = LoggerFactory.getLogger(NewTrayService::class.java)

    private fun extractTrayIcon(): String {
        return try {
            val iconStream = javaClass.getResourceAsStream("/static/favicon-96x96.png")

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
        val img =
            java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g2d = img.createGraphics()

        // Draw a simple filled circle
        g2d.color = Color(100, 100, 100)
        g2d.fillOval(4, 4, 24, 24)
        g2d.dispose()

        javax.imageio.ImageIO.write(img, "png", tempIcon)

        logger.debug("Created default tray icon at: ${tempIcon.absolutePath}")
        return tempIcon.absolutePath
    }

    fun createTray(url: String, scope: CoroutineScope): Boolean {
        if (!SystemTray.isSupported()) {
            return false
        }

        val tray = SystemTray.getSystemTray()
        val image = ImageIcon(extractTrayIcon()).image
        val popup = PopupMenu()
        val openItem = MenuItem("Open")
        val openDirItem = MenuItem("Open Data Directory")
        val checkUpdateItem = MenuItem("Check for Updates")
        val exitItem = MenuItem("Exit")
        popup.add(openItem)
        popup.add(openDirItem)
        popup.add(checkUpdateItem)
        popup.addSeparator()
        popup.add(exitItem)

        // Create the tray icon
        val trayIcon = TrayIcon(image, "Portfolio Helper", popup)
        trayIcon.isImageAutoSize = true
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.id == MouseEvent.MOUSE_CLICKED) {
                    BrowserService.openBrowser(url)
                }
            }
        })
        openItem.addActionListener {
            BrowserService.openBrowser(url)
        }

        openDirItem.addActionListener {
            try {
                val dir = AppDirs.dataDir.toFile().also { it.mkdirs() }
                Desktop.getDesktop().open(dir)
            } catch (e: Exception) {
                logger.warn("Failed to open data directory: ${e.message}")
            }
        }

        checkUpdateItem.addActionListener {
            scope.launch { UpdateService.checkForUpdate() }
            BrowserService.openBrowser("$url/config")
        }

        exitItem.addActionListener {
            tray.remove(trayIcon)
            exitProcess(0)
        }

        try {
            tray.add(trayIcon)
        } catch (_: AWTException) {
            return false
        }
        return true
    }
}