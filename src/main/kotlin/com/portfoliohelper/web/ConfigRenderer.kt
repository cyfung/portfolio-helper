package com.portfoliohelper.web

import com.portfoliohelper.AppConfig
import com.portfoliohelper.AppDirs
import com.portfoliohelper.service.PortfolioRegistry
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
                    div(classes = "header-buttons") {
                        renderThemeToggle()
                    }
                }

                main(classes = "config-page") {
                    h1 { +"App Settings" }

                    // IB Connection
                    renderConfigSection("IB Connection") {
                        for (entry in PortfolioRegistry.entries) {
                            renderConfigField(
                                label = "TWS Account (${entry.name})",
                                description = "Interactive Brokers account ID for the ${entry.name} portfolio",
                                inputId = "tws-account-${entry.id}",
                                badge = null
                            ) {
                                input(type = InputType.text) {
                                    id = "tws-account-${entry.id}"
                                    name = "twsAccount"
                                    placeholder = "e.g. U1234567"
                                    value = entry.getTwsAccount() ?: ""
                                    attributes["data-config-key"] = "twsAccount"
                                    attributes["data-portfolio-id"] = entry.id
                                }
                            }
                        }
                    }

                    // Server
                    renderConfigSection("Server") {
                        val bindHostEnvOverridden = AppConfig.isEnvOverridden(AppConfig.KEY_BIND_HOST)
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

                        val navEnvOverridden = AppConfig.isEnvOverridden(AppConfig.KEY_NAV_UPDATE_INTERVAL)
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
                    }

                    // Actions
                    div(classes = "config-actions") {
                        button(classes = "config-save-btn") {
                            id = "config-save-btn"
                            attributes["type"] = "button"
                            +"Save All"
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
                        "live"    -> "live"
                        "readonly" -> "read-only"
                        "next-launch" -> "next launch"
                        else -> badge
                    }
                }
            }
        }
        block()
        span(classes = "config-field-description") { +description }
    }
}

private fun FlowContent.renderReadOnlyField(label: String, description: String, value: String) {
    div(classes = "config-field") {
        div(classes = "config-field-label-row") {
            span { +label }
            span(classes = "config-badge config-badge-readonly") { +"read-only" }
        }
        input(type = InputType.text) {
            this.value = value
            disabled = true
        }
        span(classes = "config-field-description") { +description }
    }
}
