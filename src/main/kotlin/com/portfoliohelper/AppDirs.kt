package com.portfoliohelper

import java.nio.file.Path
import java.nio.file.Paths

object AppDirs {
    // Computed OS default (used as fallback only)
    val osDefaultDataDir: Path = run {
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

    // Set once by main() — do not access before main() resolves it
    var dataDir: Path = osDefaultDataDir
}
