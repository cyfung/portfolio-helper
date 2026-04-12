package com.portfoliohelper.service

import com.portfoliohelper.AppDirs
import org.slf4j.LoggerFactory
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.system.exitProcess


object NewTrayService {
    private val logger = LoggerFactory.getLogger(NewTrayService::class.java)

    private fun loadTrayIcon(): java.awt.Image {
        return try {
            javaClass.getResourceAsStream("/static/favicon-96x96.png")
                ?.let { javax.imageio.ImageIO.read(it) }
                ?: run {
                    logger.warn("Custom tray icon not found, using default icon")
                    createDefaultImage()
                }
        } catch (e: Exception) {
            logger.warn("Failed to load tray icon: ${e.message}")
            createDefaultImage()
        }
    }

    private fun createDefaultImage(): java.awt.image.BufferedImage {
        val size = 32
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g2d = img.createGraphics()
        g2d.color = Color(100, 100, 100)
        g2d.fillOval(4, 4, 24, 24)
        g2d.dispose()
        return img
    }

    fun createTray(url: String): Boolean {
        if (!SystemTray.isSupported()) {
            return false
        }

        val tray = SystemTray.getSystemTray()
        val image = loadTrayIcon()
        val popup = PopupMenu()
        val openItem = MenuItem("Open")
        val openDirItem = MenuItem("Open Data Directory")
        val copyAdminCodeItem = MenuItem("Copy Admin Code")
        val restartItem = MenuItem("Restart")
        val exitItem = MenuItem("Exit")
        popup.add(openItem)
        popup.add(openDirItem)
        popup.add(copyAdminCodeItem)
        popup.addSeparator()
        popup.add(restartItem)
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

        copyAdminCodeItem.addActionListener {
            try {
                val code = AdminService.getCurrentOrGenerate()
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
                logger.info("Admin code copied to clipboard")
            } catch (e: Exception) {
                logger.warn("Failed to copy admin code to clipboard: ${e.message}")
            }
        }

        restartItem.addActionListener {
            tray.remove(trayIcon)
            UpdateService.relaunchSelf()
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