package com.portfoliohelper

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingConfigurationTest {
    @Test
    fun `bootstrap creates and publishes the log directory`() {
        val dataDir = createTempDirectory("portfolio-helper-logging")
        val previous = System.getProperty(LOG_DIR_PROPERTY)

        try {
            configureLogging(dataDir)

            val expected = dataDir.resolve("logs").toAbsolutePath()
            assertTrue(Files.isDirectory(expected))
            assertEquals(expected.toString(), System.getProperty(LOG_DIR_PROPERTY))
        } finally {
            if (previous == null) System.clearProperty(LOG_DIR_PROPERTY)
            else System.setProperty(LOG_DIR_PROPERTY, previous)
        }
    }

    @Test
    fun `packaged logback configuration writes application messages to a file`() {
        val logDir = createTempDirectory("portfolio-helper-logback").toAbsolutePath()
        val context = LoggerContext().apply {
            name = "file-appender-test"
            mdcAdapter = LogbackMDCAdapter()
            putProperty(LOG_DIR_PROPERTY, logDir.toString())
        }

        try {
            val config = checkNotNull(javaClass.classLoader.getResource("logback.xml"))
            JoranConfigurator().apply {
                this.context = context
                doConfigure(config)
            }
            context.start()

            context.getLogger("Application").info("logging-regression-marker")
        } finally {
            context.stop()
        }

        val logFile = logDir.resolve("portfolio-helper.log")
        assertTrue(Files.isRegularFile(logFile), "Expected Logback to create $logFile")
        assertTrue("logging-regression-marker" in logFile.readText())
    }
}
