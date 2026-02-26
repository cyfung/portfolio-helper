package com.portfoliohelper.web

import kotlinx.html.DIV
import kotlinx.html.HEAD
import kotlinx.html.button
import kotlinx.html.link
import kotlinx.html.span
import kotlinx.html.unsafe

fun HEAD.renderCommonHeadElements() {
    link(rel = "stylesheet", href = "/static/styles.css")
    link(rel = "icon", type = "image/png", href = "/static/favicon-96x96.png") {
        attributes["sizes"] = "96x96"
    }
    link(rel = "icon", type = "image/svg+xml", href = "/static/favicon.svg")
}

fun DIV.renderThemeToggle() {
    button(classes = "theme-toggle") {
        attributes["aria-label"] = "Toggle theme"
        attributes["id"] = "theme-toggle"
        attributes["type"] = "button"

        span(classes = "icon-sun") {
            unsafe {
                raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>""")
            }
        }

        span(classes = "icon-moon") {
            unsafe {
                raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>""")
            }
        }
    }
}