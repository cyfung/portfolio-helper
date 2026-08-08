package com.portfoliohelper

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class AppDirsTest {
    @Test
    fun `explicit data directory overrides the OS default`() {
        val configured = Paths.get("custom", "portfolio-data")

        assertEquals(
            configured,
            AppDirs.resolveDataDir(mapOf("PORTFOLIO_HELPER_DATA_DIR" to configured.toString()))
        )
    }

    @Test
    fun `windows default uses roaming application data`() {
        assertEquals(
            Paths.get("C:/Users/Test/AppData/Roaming", "PortfolioHelper"),
            AppDirs.defaultDataDir(
                osName = "Windows 11",
                userHome = "C:/Users/Test",
                environment = mapOf("APPDATA" to "C:/Users/Test/AppData/Roaming")
            )
        )
    }

    @Test
    fun `macOS and Linux defaults use platform data locations`() {
        assertEquals(
            Paths.get("/Users/test", "Library", "Application Support", "PortfolioHelper"),
            AppDirs.defaultDataDir("Mac OS X", "/Users/test", emptyMap())
        )
        assertEquals(
            Paths.get("/tmp/xdg-data", "PortfolioHelper"),
            AppDirs.defaultDataDir(
                osName = "Linux",
                userHome = "/home/test",
                environment = mapOf("XDG_DATA_HOME" to "/tmp/xdg-data")
            )
        )
    }
}
