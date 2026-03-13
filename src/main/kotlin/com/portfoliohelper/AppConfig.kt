package com.portfoliohelper

import java.io.File
import java.util.Properties
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object AppConfig {
    const val KEY_DATA_DIR            = "dataDir"
    const val KEY_BIND_HOST           = "bindHost"
    const val KEY_OPEN_BROWSER        = "openBrowser"
    const val KEY_NAV_UPDATE_INTERVAL = "navUpdateInterval"
    const val KEY_EXCHANGE_SUFFIXES   = "exchangeSuffixes"
    const val KEY_TWS_HOST            = "twsHost"
    const val KEY_TWS_PORT            = "twsPort"
    const val KEY_IBKR_RATE_INTERVAL  = "ibkrRateInterval"
    const val KEY_GITHUB_REPO         = "githubRepo"
    const val KEY_AUTO_UPDATE              = "autoUpdate"
    const val KEY_UPDATE_CHECK_INTERVAL   = "updateCheckInterval"

    // Fixed OS config path (NOT inside dataDir — avoids circular dependency)
    val userConfigFile: File = run {
        val home = System.getProperty("user.home")
        when {
            System.getProperty("os.name").lowercase().contains("win") ->
                File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "PortfolioHelper/app.conf")
            System.getProperty("os.name").lowercase().contains("mac") ->
                File("$home/Library/Application Support/PortfolioHelper/app.conf")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
                File("$xdg/PortfolioHelper/app.conf")
            }
        }
    }
    private val localConfigFile = File("app.conf")
    private val readFile: File get() = if (localConfigFile.exists()) localConfigFile else userConfigFile
    private val saveFile: File get() = if (localConfigFile.exists()) localConfigFile else userConfigFile

    private val ENV_MAP = mapOf(
        KEY_BIND_HOST           to "PORTFOLIO_HELPER_BIND_HOST",
        KEY_NAV_UPDATE_INTERVAL to "NAV_UPDATE_INTERVAL",
        KEY_TWS_HOST            to "TWS_HOST",
        KEY_TWS_PORT            to "TWS_PORT"
    )
    private val DEFAULTS = mapOf(
        KEY_BIND_HOST           to "0.0.0.0",
        KEY_OPEN_BROWSER        to "true",
        KEY_DATA_DIR            to "",
        KEY_NAV_UPDATE_INTERVAL to "",
        KEY_EXCHANGE_SUFFIXES   to "SBF=.PA,LSEETF=.L",
        KEY_TWS_HOST            to "127.0.0.1",
        KEY_TWS_PORT            to "7496",
        KEY_IBKR_RATE_INTERVAL  to "3600",
        KEY_GITHUB_REPO         to "cyfung/portfolio-helper",
        KEY_AUTO_UPDATE              to "true",
        KEY_UPDATE_CHECK_INTERVAL   to "86400"
    )

    private val lock = ReentrantReadWriteLock()
    private var fileProps = Properties()

    init { reload() }

    fun reload() {
        lock.write {
            fileProps = Properties()
            val f = readFile
            if (f.exists()) f.inputStream().use { fileProps.load(it) }
        }
    }

    fun getRaw(key: String): String? = lock.read { fileProps.getProperty(key) }
    fun isEnvOverridden(key: String) = ENV_MAP[key]?.let { !System.getenv(it).isNullOrBlank() } ?: false

    fun get(key: String): String {
        ENV_MAP[key]?.let { envName -> System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it } }
        return lock.read { fileProps.getProperty(key) } ?: DEFAULTS[key] ?: ""
    }

    fun save(updates: Map<String, String>) {
        lock.write {
            val f = saveFile
            f.parentFile?.mkdirs()
            val current = Properties().also { if (f.exists()) f.inputStream().use { s -> it.load(s) } }
            updates.forEach { (k, v) -> if (v.isBlank()) current.remove(k) else current[k] = v }
            f.outputStream().use { current.store(it, "Portfolio Helper app config") }
            fileProps = current
        }
    }

    // Typed accessors
    val bindHost: String get() = get(KEY_BIND_HOST).ifBlank { "0.0.0.0" }
    val openBrowser: Boolean get() = get(KEY_OPEN_BROWSER).lowercase() != "false"
    val navUpdateInterval: Long? get() = get(KEY_NAV_UPDATE_INTERVAL).toLongOrNull()?.takeIf { it > 0 }
    val twsHost: String get() = get(KEY_TWS_HOST).ifBlank { "127.0.0.1" }
    val twsPort: Int    get() = get(KEY_TWS_PORT).toIntOrNull()?.takeIf { it > 0 } ?: 7496
    val ibkrRateIntervalMs: Long get() =
        (get(KEY_IBKR_RATE_INTERVAL).toLongOrNull()?.takeIf { it > 0 } ?: 3600L) * 1000L
    val githubRepo: String get() = get(KEY_GITHUB_REPO).ifBlank { "cyfung/portfolio-helper" }
    val autoUpdate: Boolean get() = get(KEY_AUTO_UPDATE).lowercase() != "false"
    val updateCheckIntervalMs: Long get() =
        (get(KEY_UPDATE_CHECK_INTERVAL).toLongOrNull()?.takeIf { it >= 60 } ?: 86400L) * 1000L
    val exchangeSuffixes: Map<String, String>
        get() = get(KEY_EXCHANGE_SUFFIXES).split(",")
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }
            .toMap()
}
