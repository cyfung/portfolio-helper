package com.portfoliohelper

import java.nio.file.Path
import java.nio.file.Paths

object AppDirs {
    // Computed OS default (used as fallback only)
    val osDefaultDataDir: Path = defaultDataDir()

    internal fun defaultDataDir(
        osName: String = System.getProperty("os.name"),
        userHome: String = System.getProperty("user.home"),
        environment: Map<String, String> = System.getenv()
    ): Path {
        val appName = "PortfolioHelper"
        return when {
            osName.lowercase().contains("win") -> {
                val appData = environment["APPDATA"]?.takeIf { it.isNotBlank() }
                    ?: "$userHome/AppData/Roaming"
                Paths.get(appData, appName)
            }
            osName.lowercase().contains("mac") -> {
                Paths.get(userHome, "Library", "Application Support", appName)
            }
            else -> {
                // Linux/Unix — XDG Base Directory spec
                val xdgData = environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }
                    ?: "$userHome/.local/share"
                Paths.get(xdgData, appName)
            }
        }
    }

    internal fun resolveDataDir(environment: Map<String, String> = System.getenv()): Path =
        environment["PORTFOLIO_HELPER_DATA_DIR"]
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?: osDefaultDataDir

    // Set once by main() — do not access before main() resolves it
    var dataDir: Path = osDefaultDataDir
}
