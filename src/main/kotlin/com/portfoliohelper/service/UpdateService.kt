package com.portfoliohelper.service

import com.portfoliohelper.APP_VERSION
import com.portfoliohelper.AppConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

object UpdateService {
    private val logger = LoggerFactory.getLogger(UpdateService::class.java)

    enum class DownloadPhase { IDLE, DOWNLOADING, READY, APPLYING }

    data class DownloadProgress(
        val phase: DownloadPhase = DownloadPhase.IDLE,
        val bytesReceived: Long = 0,
        val totalBytes: Long = 0
    )

    data class UpdateInfo(
        val currentVersion: String,
        val latestVersion: String?,
        val releaseUrl: String?,
        val jpackageAssetUrl: String?,
        val hasUpdate: Boolean,
        val isJpackageInstall: Boolean,
        val lastCheckedMs: Long,
        val lastCheckError: String?,
        val download: DownloadProgress,
        val pendingJarPath: String?
    )

    val isJpackageInstall: Boolean
    val installDir: Path?

    init {
        val jarPath = runCatching {
            val src = UpdateService::class.java.protectionDomain?.codeSource?.location
            src?.toURI()?.let { Paths.get(it) }
        }.getOrNull()

        val detected = jarPath?.let { p ->
            runCatching {
                val parent = p.parent
                val root = parent?.parent
                if (parent?.fileName?.toString() == "app" &&
                    root != null && Files.exists(root.resolve("runtime"))
                ) root else null
            }.getOrNull()
        }
        installDir = detected
        isJpackageInstall = detected != null
        logger.debug("UpdateService: isJpackageInstall=$isJpackageInstall, installDir=$installDir")
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10 * 60, TimeUnit.SECONDS)
        .build()

    private val state = AtomicReference(
        UpdateInfo(
            currentVersion = APP_VERSION,
            latestVersion = null,
            releaseUrl = null,
            jpackageAssetUrl = null,
            hasUpdate = false,
            isJpackageInstall = isJpackageInstall,
            lastCheckedMs = 0,
            lastCheckError = null,
            download = DownloadProgress(),
            pendingJarPath = null
        )
    )

    fun getInfo(): UpdateInfo = state.get()

    var onDownloadReady: (() -> Unit)? = null

    fun initialize(scope: CoroutineScope) {
        scope.launch {
            delay(5_000)
            while (true) {
                runCatching { checkForUpdate() }
                    .onFailure { logger.warn("Update check failed: ${it.message}") }
                val current = state.get()
                if (AppConfig.autoUpdate && current.hasUpdate && isJpackageInstall &&
                    current.download.phase == DownloadPhase.IDLE) {
                    launch {
                        runCatching { downloadUpdate() }
                            .onFailure { logger.warn("Auto-download failed: ${it.message}") }
                    }
                }
                delay(24L * 60 * 60_000)
            }
        }
    }

    suspend fun checkForUpdate() {
        val repo = AppConfig.githubRepo.ifBlank { "cyfung/portfolio-helper" }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "portfolio-helper/$APP_VERSION")
            .build()
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub API returned ${response.code}")
                val text = response.body?.string() ?: error("Empty response")
                val tagName = parseJsonString(text, "tag_name")
                val latestVersion = tagName?.removePrefix("v")
                val htmlUrl = parseJsonString(text, "html_url")
                val jpackageAssetUrl = parseAssetsForJpackage(text)
                val hasUpdate = latestVersion != null && isNewerVersion(latestVersion, APP_VERSION)
                state.set(
                    state.get().copy(
                        latestVersion = latestVersion,
                        releaseUrl = htmlUrl,
                        jpackageAssetUrl = jpackageAssetUrl,
                        hasUpdate = hasUpdate,
                        lastCheckedMs = System.currentTimeMillis(),
                        lastCheckError = null
                    )
                )
                logger.info("Update check: currentVersion=$APP_VERSION, latestVersion=$latestVersion, hasUpdate=$hasUpdate")
            }
        }
    }

    suspend fun downloadUpdate() {
        val dir = installDir ?: error("Not a jpackage install")
        val pendingJar = dir.resolve("app/portfolio-helper-pending.jar")

        val current = state.get()
        if (current.download.phase != DownloadPhase.IDLE) error("Download already in progress (phase=${current.download.phase})")

        state.set(state.get().copy(download = DownloadProgress(DownloadPhase.DOWNLOADING), lastCheckError = null))
        try {
            val url = state.get().jpackageAssetUrl ?: error("No jpackage asset URL available")
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "portfolio-helper/$APP_VERSION")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    val totalBytes = body.contentLength()
                    Files.newOutputStream(pendingJar).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(512 * 1024)
                            var received = 0L
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                received += n
                                state.set(
                                    state.get().copy(
                                        download = DownloadProgress(DownloadPhase.DOWNLOADING, received, totalBytes)
                                    )
                                )
                            }
                        }
                    }
                }
            }
            state.set(
                state.get().copy(
                    download = state.get().download.copy(phase = DownloadPhase.READY),
                    pendingJarPath = pendingJar.toString()
                )
            )
            logger.info("Download complete: $pendingJar")
            onDownloadReady?.invoke()
        } catch (e: Exception) {
            logger.error("Download failed: ${e.message}", e)
            state.set(state.get().copy(download = DownloadProgress(DownloadPhase.IDLE), lastCheckError = "Download failed: ${e.message}"))
            throw e
        }
    }

    fun relaunchSelf() {
        val dir = installDir
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        if (dir != null) {
            val exeName = if (isWindows) "Portfolio Helper.exe" else "Portfolio Helper"
            // Windows app-image: exe is at root; Linux app-image: exe is in bin/
            val exePath = if (isWindows) dir.resolve(exeName) else dir.resolve("bin/$exeName")
            ProcessBuilder(exePath.toString())
                .also { it.inheritIO() }
                .start()
        } else {
            val info = ProcessHandle.current().info()
            val command = info.command().orElse(null)
            val args = info.arguments().map { it.toList() }.orElse(emptyList())
            if (command != null) {
                ProcessBuilder(listOf(command) + args)
                    .also { it.inheritIO() }
                    .start()
            }
        }
        exitProcess(0)
    }

    fun applyUpdate() {
        val dir = installDir ?: error("Not a jpackage install")
        state.set(state.get().copy(download = state.get().download.copy(phase = DownloadPhase.APPLYING)))

        val pid = ProcessHandle.current().pid().toString()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        if (isWindows) {
            val scriptPath = dir.resolve("portfolio-helper-update.bat")
            Files.writeString(scriptPath, buildString {
                appendLine("@echo off")
                appendLine("set INSTALL=%~dp0")
                appendLine("set PENDING=%INSTALL%app\\portfolio-helper-pending.jar")
                appendLine("set DEST=%INSTALL%app\\portfolio-helper-all.jar")
                appendLine(":wait")
                appendLine("tasklist /FI \"PID eq %1\" 2>nul | find \"%1\" >nul || goto replace")
                appendLine("timeout /t 1 /nobreak >nul")
                appendLine("goto wait")
                appendLine(":replace")
                appendLine("del /f /q \"%DEST%\"")
                appendLine("move /y \"%PENDING%\" \"%DEST%\"")
                appendLine("start \"\" \"%INSTALL%Portfolio Helper.exe\"")
                appendLine("del /f /q \"%~f0\"")
            })
            ProcessBuilder("cmd", "/c", "start", "/min", "cmd", "/c", scriptPath.toString(), pid)
                .also { it.inheritIO() }
                .start()
        } else {
            val scriptPath = dir.resolve("portfolio-helper-update.sh")
            Files.writeString(scriptPath, buildString {
                appendLine("#!/bin/sh")
                appendLine("D=\"\$(cd \"\$(dirname \"\$0\")\" && pwd)\"")
                appendLine("PENDING=\"\$D/app/portfolio-helper-pending.jar\"")
                appendLine("DEST=\"\$D/app/portfolio-helper-all.jar\"")
                appendLine("while kill -0 \"\$1\" 2>/dev/null; do sleep 0.5; done")
                appendLine("rm -f \"\$DEST\"")
                appendLine("mv \"\$PENDING\" \"\$DEST\"")
                appendLine("\"\$D/bin/Portfolio Helper\" &")
                appendLine("rm -f \"\$0\"")
            })
            scriptPath.toFile().setExecutable(true)
            ProcessBuilder("sh", scriptPath.toString(), pid)
                .also { it.inheritIO() }
                .start()
        }
        exitProcess(0)
    }

    private fun parseJsonString(json: String, key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"\\\\]|\\\\.)*\"").find(json)?.value
            ?.substringAfter("\"$key\"")
            ?.substringAfter(":")
            ?.trim()
            ?.removeSurrounding("\"")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")

    private fun parseAssetsForJpackage(json: String): String? {
        val assetsSection = Regex("\"assets\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return null
        // Match name→browser_download_url pairs across each asset object.
        // Splitting at '{' was wrong: the uploader sub-object pushes browser_download_url
        // into a different chunk than the asset's own "name" field.
        val pairRx = Regex(
            "\"name\"\\s*:\\s*\"([^\"]+)\".*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL
        )
        var fallbackUrl: String? = null
        for (match in pairRx.findAll(assetsSection)) {
            val name = match.groupValues[1]
            val url  = match.groupValues[2]
            if ("jpackage" in name.lowercase()) {
                if (name.endsWith(".jar")) return url  // prefer JAR for in-place update
                if (fallbackUrl == null) fallbackUrl = url
            }
        }
        return fallbackUrl
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    fun UpdateInfo.toJson(): String = buildString {
        fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
        append("{")
        append("\"currentVersion\":\"$currentVersion\",")
        append("\"latestVersion\":${latestVersion?.let { "\"$it\"" } ?: "null"},")
        append("\"releaseUrl\":${releaseUrl?.let { "\"${it.esc()}\"" } ?: "null"},")
        append("\"hasUpdate\":$hasUpdate,")
        append("\"isJpackageInstall\":$isJpackageInstall,")
        append("\"autoUpdate\":${AppConfig.autoUpdate},")
        append("\"lastCheckedMs\":$lastCheckedMs,")
        append("\"lastCheckError\":${lastCheckError?.let { "\"${it.esc()}\"" } ?: "null"},")
        append("\"download\":{")
        append("\"phase\":\"${download.phase.name}\",")
        append("\"bytesReceived\":${download.bytesReceived},")
        append("\"totalBytes\":${download.totalBytes}")
        append("},")
        append("\"pendingJarPath\":${pendingJarPath?.let { "\"${it.esc()}\"" } ?: "null"}")
        append("}")
    }
}
