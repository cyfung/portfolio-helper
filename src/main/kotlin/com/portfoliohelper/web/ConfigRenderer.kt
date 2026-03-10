package com.portfoliohelper.web

import com.portfoliohelper.AppConfig
import com.portfoliohelper.AppDirs
import com.portfoliohelper.APP_VERSION
import com.portfoliohelper.service.ManagedPortfolio
import com.portfoliohelper.service.PortfolioRegistry
import com.portfoliohelper.service.UpdateService
import com.portfoliohelper.service.UpdateService.DownloadPhase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import kotlinx.html.*

internal suspend fun ApplicationCall.renderConfigPage() {
    respondHtml(HttpStatusCode.OK) {
        head {
            title { +"App Settings" }
            meta(charset = "UTF-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1.0")
            renderCommonHeadElements()
            script(src = "/static/common/theme.js") {}
        }
        body {
            div(classes = "container") {
                div(classes = "portfolio-header") {
                    div(classes = "header-title-group") {
                        renderPageNavTabs(AppPage.CONFIG)
                    }
                    renderHeaderRight {
                        renderThemeToggle()
                    }
                }

                main(classes = "config-page") {
                    h1 { +"App Settings" }

                    // IB Connection
                    renderConfigSection("IB Connection") {
                        // Per-portfolio table
                        table(classes = "portfolio-config-table") {
                            thead {
                                tr {
                                    th { +"Portfolio" }
                                    th { +"TWS Account" }
                                    th { +"Virtual Balance" }
                                }
                            }
                            tbody {
                                for (entry in PortfolioRegistry.entries) {
                                    val virtualBalance =
                                        getPortfolioConfValue(entry, "virtualBalance") == "true"
                                    tr {
                                        td { +entry.name }
                                        td {
                                            input(type = InputType.text) {
                                                placeholder = "e.g. U1234567"
                                                value = entry.getTwsAccount() ?: ""
                                                attributes["data-config-key"] = "twsAccount"
                                                attributes["data-portfolio-id"] = entry.id
                                            }
                                        }
                                        td(classes = "portfolio-config-table-checkbox-col") {
                                            input(type = InputType.checkBox) {
                                                checked = virtualBalance
                                                attributes["data-config-key"] = "virtualBalance"
                                                attributes["data-portfolio-id"] = entry.id
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val twsHostEnvOverridden = AppConfig.isEnvOverridden(AppConfig.KEY_TWS_HOST)
                        renderConfigField(
                            label = "TWS Host",
                            description = "Hostname or IP address of the TWS / IB Gateway.",
                            inputId = "tws-host",
                            badge = null
                        ) {
                            input(type = InputType.text) {
                                id = "tws-host"
                                placeholder = "127.0.0.1"
                                value = AppConfig.get(AppConfig.KEY_TWS_HOST)
                                disabled = twsHostEnvOverridden
                                attributes["data-config-key"] = AppConfig.KEY_TWS_HOST
                            }
                            if (twsHostEnvOverridden) {
                                span(classes = "config-env-override-note") {
                                    +"Set by TWS_HOST env var"
                                }
                            }
                        }

                        val twsPortEnvOverridden = AppConfig.isEnvOverridden(AppConfig.KEY_TWS_PORT)
                        renderConfigField(
                            label = "TWS Port",
                            description = "Port of the TWS / IB Gateway. Default: 7496 (live), 7497 (paper), 4001 (IB Gateway live).",
                            inputId = "tws-port",
                            badge = null
                        ) {
                            input(type = InputType.number) {
                                id = "tws-port"
                                placeholder = "7496"
                                value = AppConfig.get(AppConfig.KEY_TWS_PORT)
                                disabled = twsPortEnvOverridden
                                attributes["data-config-key"] = AppConfig.KEY_TWS_PORT
                                attributes["min"] = "1"
                                attributes["max"] = "65535"
                            }
                            if (twsPortEnvOverridden) {
                                span(classes = "config-env-override-note") {
                                    +"Set by TWS_PORT env var"
                                }
                            }
                        }
                    }

                    // Server
                    renderConfigSection("Server") {
                        val bindHostEnvOverridden =
                            AppConfig.isEnvOverridden(AppConfig.KEY_BIND_HOST)
                        renderConfigField(
                            label = "Bind Host",
                            description = "Network interface to listen on. Use 0.0.0.0 for LAN access.",
                            inputId = "bind-host",
                            badge = "restart"
                        ) {
                            input(type = InputType.text) {
                                id = "bind-host"
                                placeholder = "localhost"
                                value = AppConfig.get(AppConfig.KEY_BIND_HOST)
                                disabled = bindHostEnvOverridden
                                attributes["data-config-key"] = AppConfig.KEY_BIND_HOST
                            }
                            if (bindHostEnvOverridden) {
                                span(classes = "config-env-override-note") {
                                    +"Set by PORTFOLIO_HELPER_BIND_HOST env var"
                                }
                            }
                        }

                        renderReadOnlyField(
                            label = "Active Data Directory",
                            description = "Currently active data directory (read-only — change below, restart to apply)",
                            value = AppDirs.dataDir.toAbsolutePath().toString()
                        )

                        renderConfigField(
                            label = "Data Directory",
                            description = "Path to the data directory. Leave blank to use the OS default. Takes effect on restart.",
                            inputId = "data-dir",
                            badge = "restart"
                        ) {
                            input(type = InputType.text) {
                                id = "data-dir"
                                placeholder = AppDirs.osDefaultDataDir.toAbsolutePath().toString()
                                value = AppConfig.getRaw(AppConfig.KEY_DATA_DIR) ?: ""
                                attributes["data-config-key"] = AppConfig.KEY_DATA_DIR
                            }
                        }

                        renderConfigField(
                            label = "Open Browser on Start",
                            description = "Automatically open the browser when the app starts.",
                            inputId = "open-browser",
                            badge = "next-launch"
                        ) {
                            input(type = InputType.checkBox) {
                                id = "open-browser"
                                checked = AppConfig.openBrowser
                                attributes["data-config-key"] = AppConfig.KEY_OPEN_BROWSER
                            }
                        }
                    }

                    // Updates & Restart
                    renderUpdatesSection()

                    // Market Data
                    renderConfigSection("Market Data") {
                        renderConfigField(
                            label = "Exchange Suffixes",
                            description = "Comma-separated EXCHANGE=.SUFFIX mappings for TWS snapshot symbol resolution (e.g. SBF=.PA,LSEETF=.L).",
                            inputId = "exchange-suffixes",
                            badge = "live"
                        ) {
                            input(type = InputType.text) {
                                id = "exchange-suffixes"
                                placeholder = "SBF=.PA,LSEETF=.L"
                                value = AppConfig.get(AppConfig.KEY_EXCHANGE_SUFFIXES)
                                attributes["data-config-key"] = AppConfig.KEY_EXCHANGE_SUFFIXES
                            }
                        }

                        renderConfigField(
                            label = "GitHub Repository",
                            description = "GitHub repo for update checks (owner/repo format).",
                            inputId = "github-repo",
                            badge = null
                        ) {
                            input(type = InputType.text) {
                                id = "github-repo"
                                placeholder = "cyfung/portfolio-helper"
                                value = AppConfig.get(AppConfig.KEY_GITHUB_REPO)
                                attributes["data-config-key"] = AppConfig.KEY_GITHUB_REPO
                            }
                        }

                        val navEnvOverridden =
                            AppConfig.isEnvOverridden(AppConfig.KEY_NAV_UPDATE_INTERVAL)
                        renderConfigField(
                            label = "NAV Update Interval (seconds)",
                            description = "How often to fetch NAV data. Leave blank to use the trading-day schedule.",
                            inputId = "nav-update-interval",
                            badge = "restart"
                        ) {
                            input(type = InputType.number) {
                                id = "nav-update-interval"
                                placeholder = "trading-day schedule"
                                value = AppConfig.getRaw(AppConfig.KEY_NAV_UPDATE_INTERVAL) ?: ""
                                disabled = navEnvOverridden
                                attributes["data-config-key"] = AppConfig.KEY_NAV_UPDATE_INTERVAL
                                attributes["min"] = "10"
                            }
                            if (navEnvOverridden) {
                                span(classes = "config-env-override-note") {
                                    +"Set by NAV_UPDATE_INTERVAL env var"
                                }
                            }
                        }

                        renderConfigField(
                            label = "IBKR Margin Rate Interval (seconds)",
                            description = "How often to refresh IB margin rates. Default: 3600 (1 hour). Takes effect on next fetch cycle.",
                            inputId = "ibkr-rate-interval",
                            badge = null
                        ) {
                            input(type = InputType.number) {
                                id = "ibkr-rate-interval"
                                placeholder = "3600"
                                value = AppConfig.getRaw(AppConfig.KEY_IBKR_RATE_INTERVAL) ?: ""
                                attributes["data-config-key"] = AppConfig.KEY_IBKR_RATE_INTERVAL
                                attributes["min"] = "60"
                            }
                        }
                    }

                    // Actions
                    div(classes = "config-actions") {
                        button(classes = "config-save-btn") {
                            id = "config-save-btn"
                            attributes["type"] = "button"
                            +"Save All"
                        }
                        button(classes = "config-restore-btn") {
                            id = "config-restore-btn"
                            attributes["type"] = "button"
                            +"Restore Defaults"
                        }
                        div(classes = "config-status") {
                            id = "config-status"
                        }
                    }
                }
            }
            script(src = "/static/config/config.js") { defer = true }
        }
    }
}

private fun getPortfolioConfValue(entry: ManagedPortfolio, key: String): String? = runCatching {
    val f = java.io.File(entry.portfolioConfigPath)
    if (!f.exists()) return@runCatching null
    f.readLines().asSequence()
        .filter { '=' in it && !it.startsWith('#') }.firstNotNullOfOrNull {
            val k = it.substringBefore('=').trim()
            val v = it.substringAfter('=').trim()
            if (k == key && v.isNotEmpty()) v else null
        }
}.getOrNull()

private fun FlowContent.renderUpdatesSection() {
    val info = UpdateService.getInfo()
    val phase = info.download.phase
    renderConfigSection("Updates & Restart") {
        // Current version
        renderReadOnlyField(
            label = "Current Version",
            description = "The version of Portfolio Helper currently running.",
            value = "v$APP_VERSION"
        )

        // Latest version
        div(classes = "config-field") {
            div(classes = "config-field-label-row") {
                span { +"Latest Version" }
                span(classes = "config-badge config-badge-update") {
                    id = "latest-version-badge"
                    if (!info.hasUpdate) attributes["hidden"] = "hidden"
                    +"update available"
                }
            }
            span(classes = "config-field-description") { +"Latest release from GitHub." }
            div(classes = "config-field-input-col") {
                id = "latest-version-value"
                if (info.latestVersion != null) {
                    val releaseHref = info.releaseUrl ?: "#"
                    a(href = releaseHref) {
                        attributes["target"] = "_blank"
                        attributes["rel"] = "noopener"
                        +"v${info.latestVersion}"
                    }
                } else if (info.lastCheckError != null) {
                    span(classes = "config-env-override-note") { +"Check failed: ${info.lastCheckError}" }
                } else {
                    span(classes = "config-env-override-note") { +"Not checked yet" }
                }
            }
        }

        // Download progress bar (shown via JS when downloading)
        div(classes = "update-progress-row") {
            id = "update-progress-row"
            div(classes = "config-field-label-row") {
                span { +"Download Progress" }
            }
            div(classes = "update-progress-bar-container") {
                div(classes = "update-progress-bar") { id = "update-progress-bar" }
            }
            span(classes = "config-field-description") {
                id = "update-progress-label"
                +"0 / 0 MB"
            }
        }

        if (!info.isJpackageInstall) {
            div(classes = "config-field") {
                span(classes = "config-env-override-note") {
                    +"Running as portable JAR — download updates manually from GitHub."
                }
            }
        }

        // Auto Update checkbox (jpackage only)
        if (info.isJpackageInstall) {
            renderConfigField(
                label = "Auto Update",
                description = "Automatically download updates in the background when a new version is found (jpackage install only). Requires manual apply & restart.",
                inputId = "auto-update",
                badge = null
            ) {
                input(type = InputType.checkBox) {
                    id = "auto-update"
                    checked = AppConfig.autoUpdate
                    attributes["data-config-key"] = AppConfig.KEY_AUTO_UPDATE
                }
            }
        }

        // Action buttons
        div(classes = "update-action-buttons") {
            button(classes = "config-restore-btn") {
                id = "check-update-btn"
                attributes["type"] = "button"
                +"Check for Updates"
            }
            if (info.isJpackageInstall) {
                button(classes = "config-restore-btn") {
                    id = "download-update-btn"
                    attributes["type"] = "button"
                    if (!info.hasUpdate || phase != DownloadPhase.IDLE) {
                        disabled = true
                    }
                    +"Download Update"
                }
                button(classes = "config-save-btn") {
                    id = "apply-update-btn"
                    attributes["type"] = "button"
                    if (phase != DownloadPhase.READY) {
                        attributes["hidden"] = "hidden"
                    }
                    +"Apply Update & Restart"
                }
            }
            button(classes = "config-restore-btn") {
                id = "restart-btn"
                attributes["type"] = "button"
                +"Restart App"
            }
        }

        div(classes = "config-status") {
            id = "update-status"
        }
    }
}

private fun FlowContent.renderConfigSection(title: String, block: DIV.() -> Unit) {
    div(classes = "config-section") {
        div(classes = "config-section-header") {
            h2 { +title }
        }
        div(classes = "config-section-body") {
            block()
        }
    }
}

private fun FlowContent.renderConfigField(
    label: String,
    description: String,
    inputId: String,
    badge: String?,
    block: DIV.() -> Unit
) {
    div(classes = "config-field") {
        div(classes = "config-field-label-row") {
            label {
                attributes["for"] = inputId
                +label
            }
            if (badge != null) {
                span(classes = "config-badge config-badge-$badge") {
                    +when (badge) {
                        "restart" -> "restart required"
                        "live" -> "live"
                        "readonly" -> "read-only"
                        "next-launch" -> "next launch"
                        else -> badge
                    }
                }
            }
        }
        span(classes = "config-field-description") { +description }
        div(classes = "config-field-input-col") { block() }
    }
}

private fun FlowContent.renderReadOnlyField(label: String, description: String, value: String) {
    div(classes = "config-field") {
        div(classes = "config-field-label-row") {
            span { +label }
            span(classes = "config-badge config-badge-readonly") { +"read-only" }
        }
        span(classes = "config-field-description") { +description }
        div(classes = "config-field-input-col") {
            input(type = InputType.text) {
                this.value = value
                disabled = true
            }
        }
    }
}
