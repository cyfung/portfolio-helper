package com.portfoliohelper

import java.nio.file.Path
import java.nio.file.Paths

object AppDirs {
    val dataDir: Path = run {
        val override = System.getenv("PORTFOLIO_HELPER_DATA_DIR")
        if (!override.isNullOrBlank()) {
            return@run Paths.get(override)
        }
        val home = System.getProperty("user.home")
        val appName = "PortfolioHelper"
        when {
            System.getProperty("os.name").lowercase().contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$home/AppData/Roaming"
                Paths.get(appData, appName)
            }
            System.getProperty("os.name").lowercase().contains("mac") -> {
                Paths.get(home, "Library", "Application Support", appName)
            }
            else -> {
                // Linux/Unix — XDG Base Directory spec
                val xdgData = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: "$home/.local/share"
                Paths.get(xdgData, appName)
            }
        }
    }
}
