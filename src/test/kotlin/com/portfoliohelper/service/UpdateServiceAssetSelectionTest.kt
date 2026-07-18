package com.portfoliohelper.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateServiceAssetSelectionTest {
    @Test
    fun `windows x64 runtime prefers windows x64 jar over legacy jar`() {
        val selected = UpdateService.selectUpdateJarAsset(
            listOf(
                "portfolio-helper-jpackage-0.9.12.jar" to "legacy",
                "portfolio-helper-windows-x64-0.9.12.jar" to "windows-x64",
            ),
            windowsX64Runtime = true,
        )

        assertEquals("windows-x64", selected)
    }

    @Test
    fun `non windows x64 runtime ignores windows x64 jar and keeps legacy jar`() {
        val selected = UpdateService.selectUpdateJarAsset(
            listOf(
                "portfolio-helper-windows-x64-0.9.12.jar" to "windows-x64",
                "portfolio-helper-jpackage-0.9.12.jar" to "legacy",
            ),
            windowsX64Runtime = false,
        )

        assertEquals("legacy", selected)
    }

    @Test
    fun `non windows x64 runtime does not select a windows x64 only release`() {
        val selected = UpdateService.selectUpdateJarAsset(
            listOf("portfolio-helper-windows-x64-0.9.12.jar" to "windows-x64"),
            windowsX64Runtime = false,
        )

        assertNull(selected)
    }
}
