package com.portfoliohelper.web

import com.portfoliohelper.service.PortfolioState
import com.portfoliohelper.service.PortfolioUpdateBroadcaster
import com.portfoliohelper.service.nav.NavService
import com.portfoliohelper.service.yahoo.YahooMarketDataService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.html.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import java.io.BufferedReader
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths

fun Application.configureRouting() {
    routing {
        // Main portfolio page
        get("/") {
            // Get current portfolio state with latest prices from Yahoo Finance
            val portfolio = YahooMarketDataService.getCurrentPortfolio(PortfolioState.getStocks())

            call.respondHtml(HttpStatusCode.OK) {
                head {
                    title { +"Stock Portfolio Viewer" }
                    meta(charset = "UTF-8")
                    meta(name = "viewport", content = "width=device-width, initial-scale=1.0")

                    // Inline script to prevent flash of wrong theme
                    script {
                        unsafe {
                            raw("""
                                (function(){
                                    const t=localStorage.getItem('ib-viewer-theme')||
                                            (window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light');
                                    document.documentElement.setAttribute('data-theme',t);
                                })();
                            """.trimIndent())
                        }
                    }

                    link(rel = "stylesheet", href = "/static/styles.css")

                    // JavaScript for SSE updates and theme switching
                    script {
                        unsafe {
                            raw("""
                                // Global store for Day % of all symbols (portfolio + LETF components)
                                const componentDayPercents = {};

                                // Connect to SSE for live price updates
                                const eventSource = new EventSource('/api/prices/stream');

                                eventSource.onmessage = (event) => {
                                    try {
                                        const data = JSON.parse(event.data);

                                        if (data.type === 'reload') {
                                            // Portfolio structure changed, reload page
                                            console.log('Portfolio reloaded, refreshing page...');
                                            location.reload();
                                        } else if (data.type === 'nav') {
                                            // NAV update
                                            updateNavInUI(data.symbol, data.nav);
                                        } else {
                                            // Update global timestamp
                                            updateGlobalTimestamp(data.timestamp);

                                            // Update price in UI
                                            updatePriceInUI(data.symbol, data.markPrice, data.lastClosePrice, data.isMarketClosed || false);
                                        }
                                    } catch (e) {
                                        console.error('Failed to parse SSE data:', e);
                                    }
                                };

                                eventSource.onerror = (error) => {
                                    console.error('SSE connection error:', error);
                                };

                                function parsePrice(priceText) {
                                    if (!priceText || priceText === '—') return null;
                                    const cleaned = priceText.replace(/[$,]/g, '');
                                    const parsed = parseFloat(cleaned);
                                    return isNaN(parsed) ? null : parsed;
                                }

                                function formatTimestamp(timestamp) {
                                    if (!timestamp) return 'Never';
                                    const date = new Date(timestamp);
                                    return date.toLocaleTimeString('en-US', {
                                        hour: '2-digit',
                                        minute: '2-digit',
                                        second: '2-digit',
                                        hour12: true
                                    });
                                }

                                function updateGlobalTimestamp(timestamp) {
                                    const timeCell = document.getElementById('last-update-time');
                                    if (timeCell && timestamp) {
                                        timeCell.textContent = formatTimestamp(timestamp);
                                        timeCell.classList.add('loaded');
                                    }
                                }

                                function updateNavInUI(symbol, nav) {
                                    const navCell = document.getElementById('nav-' + symbol);
                                    if (navCell) {
                                        navCell.textContent = nav !== null ? '$' + nav.toFixed(2) : '—';
                                        if (nav !== null) navCell.classList.add('loaded');
                                    }
                                    // Recalculate Est Val (NAV is preferred base price)
                                    updateAllEstVals();
                                }

                                function updatePriceInUI(symbol, markPrice, lastClosePrice, isMarketClosed) {
                                    // Store Day % for LETF Est Val calculations
                                    if (markPrice !== null && lastClosePrice !== null && lastClosePrice !== 0) {
                                        componentDayPercents[symbol] = ((markPrice - lastClosePrice) / lastClosePrice) * 100;
                                    }

                                    // Store previous value for comparison (to detect if row should be highlighted)
                                    const valueCell = document.getElementById('value-' + symbol);
                                    const amountCell = document.getElementById('amount-' + symbol);
                                    let previousValue = null;
                                    let valueChanged = false;

                                    if (valueCell) {
                                        const previousValueText = valueCell.textContent;
                                        previousValue = parsePrice(previousValueText);
                                    }

                                    // Update mark price
                                    const markCell = document.getElementById('mark-' + symbol);
                                    if (markCell) {
                                        markCell.textContent = markPrice !== null ? '$' + markPrice.toFixed(2) : '—';
                                        if (markPrice !== null) markCell.classList.add('loaded');
                                    }

                                    // Update last close price
                                    const closeCell = document.getElementById('close-' + symbol);
                                    if (closeCell) {
                                        closeCell.textContent = lastClosePrice !== null ? '$' + lastClosePrice.toFixed(2) : '—';
                                        if (lastClosePrice !== null) closeCell.classList.add('loaded');
                                    }

                                    // Calculate and update day change
                                    if (markPrice !== null && lastClosePrice !== null) {
                                        const changeDollars = markPrice - lastClosePrice;
                                        const changePercent = (changeDollars / lastClosePrice) * 100;

                                        // Check if change is effectively zero (within 0.001 tolerance for floating point)
                                        const isZeroChange = Math.abs(changeDollars) < 0.001;

                                        // Determine after-hours class
                                        const afterHoursClass = isMarketClosed ? ' after-hours' : '';

                                        // Update day change $ cell
                                        const changeCell = document.getElementById('day-change-' + symbol);
                                        if (changeCell) {
                                            if (isZeroChange) {
                                                changeCell.textContent = '—';
                                                changeCell.className = 'price-change loaded neutral' + afterHoursClass;
                                            } else {
                                                const sign = changeDollars >= 0 ? '+' : '-';
                                                changeCell.textContent = sign + '$' + Math.abs(changeDollars).toFixed(2);
                                                const direction = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';
                                                changeCell.className = 'price-change loaded ' + direction + afterHoursClass;
                                            }
                                        }

                                        // Update day change % cell
                                        const changePercentCell = document.getElementById('day-percent-' + symbol);
                                        if (changePercentCell) {
                                            if (isZeroChange) {
                                                changePercentCell.textContent = '—';
                                                changePercentCell.className = 'price-change loaded neutral' + afterHoursClass;
                                            } else {
                                                const sign = changePercent >= 0 ? '+' : '-';
                                                changePercentCell.textContent = sign + Math.abs(changePercent).toFixed(2) + '%';
                                                const direction = changePercent > 0 ? 'positive' : changePercent < 0 ? 'negative' : 'neutral';
                                                changePercentCell.className = 'price-change loaded ' + direction + afterHoursClass;
                                            }
                                        }

                                        // Update position value change (Mkt Val Chg)
                                        // IMPORTANT: Always calculate from changeDollars * amount to avoid floating point errors
                                        if (amountCell) {
                                            const amount = parseInt(amountCell.textContent);
                                            const positionChange = changeDollars * amount;

                                            const positionChangeCell = document.getElementById('position-change-' + symbol);
                                            if (positionChangeCell) {
                                                if (isZeroChange) {
                                                    positionChangeCell.textContent = '—';
                                                    positionChangeCell.className = 'price-change loaded neutral' + afterHoursClass;
                                                } else {
                                                    const sign = positionChange >= 0 ? '+' : '-';
                                                    positionChangeCell.textContent = sign + '$' + Math.abs(positionChange).toFixed(2);
                                                    const direction = positionChange > 0 ? 'positive' : positionChange < 0 ? 'negative' : 'neutral';
                                                    positionChangeCell.className = 'price-change loaded ' + direction + afterHoursClass;
                                                }
                                            }
                                        }
                                    }

                                    // Calculate and update value (prefer mark price)
                                    if (valueCell && amountCell) {
                                        const amount = parseInt(amountCell.textContent);
                                        const price = markPrice !== null ? markPrice : lastClosePrice;

                                        if (price !== null) {
                                            const newValue = price * amount;
                                            valueCell.textContent = '$' + newValue.toFixed(2);
                                            valueCell.classList.add('loaded');

                                            // Check if value actually changed (with 1 cent tolerance)
                                            if (previousValue !== null && Math.abs(newValue - previousValue) > 0.01) {
                                                valueChanged = true;
                                            }

                                            updateTotalValue();
                                        }
                                    }

                                    // Highlight row ONLY if value changed
                                    if (valueChanged && amountCell) {
                                        const row = amountCell.closest('tr');
                                        if (row) {
                                            row.classList.add('recently-updated');

                                            // Remove highlight after 10 seconds
                                            setTimeout(() => {
                                                row.classList.remove('recently-updated');
                                            }, 10000);
                                        }
                                    }

                                    // Recalculate Est Val for all LETF stocks
                                    updateAllEstVals();
                                }

                                function updateTotalValue() {
                                    let total = 0;
                                    let previousTotal = 0;

                                    // Calculate current total and previous day's total
                                    document.querySelectorAll('tbody tr').forEach(row => {
                                        const symbol = row.querySelector('td:first-child').textContent.trim();
                                        const amountCell = document.getElementById('amount-' + symbol);
                                        const markCell = document.getElementById('mark-' + symbol);
                                        const closeCell = document.getElementById('close-' + symbol);

                                        if (amountCell && markCell && closeCell) {
                                            const amount = parseInt(amountCell.textContent);
                                            const markPrice = parsePrice(markCell.textContent);
                                            const closePrice = parsePrice(closeCell.textContent);

                                            if (markPrice !== null) total += markPrice * amount;
                                            if (closePrice !== null) previousTotal += closePrice * amount;
                                        }
                                    });

                                    // Update portfolio total
                                    const totalCell = document.getElementById('portfolio-total');
                                    if (totalCell) {
                                        totalCell.textContent = '$' + total.toLocaleString('en-US', {
                                            minimumFractionDigits: 2, maximumFractionDigits: 2
                                        });
                                    }

                                    // Update portfolio daily change
                                    const changeDollars = total - previousTotal;
                                    const changePercent = previousTotal > 0 ? (changeDollars / previousTotal) * 100 : 0;
                                    const changeClass = changeDollars > 0 ? 'positive' : changeDollars < 0 ? 'negative' : 'neutral';

                                    const portfolioChangeCell = document.getElementById('portfolio-day-change');
                                    if (portfolioChangeCell) {
                                        const sign = changeDollars >= 0 ? '+' : '-';
                                        portfolioChangeCell.innerHTML =
                                            '<span class="change-dollars ' + changeClass + '">' + sign + '$' + Math.abs(changeDollars).toFixed(2) + '</span> ' +
                                            '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
                                    }

                                    updateCurrentWeights(total);
                                    updateRebalancingColumns(total);
                                }

                                function updateCurrentWeights(portfolioTotal) {
                                    if (portfolioTotal <= 0) return;

                                    document.querySelectorAll('.value.loaded').forEach(valueCell => {
                                        const value = parsePrice(valueCell.textContent);
                                        if (value === null) return;

                                        const symbol = valueCell.id.replace('value-', '');
                                        const weightCell = document.getElementById('current-weight-' + symbol);

                                        if (weightCell) {
                                            const currentWeight = (value / portfolioTotal) * 100;

                                            // Find target weight (hidden span)
                                            const targetWeightSpan = weightCell.querySelector('.target-weight-hidden');
                                            const targetWeight = targetWeightSpan ? parseFloat(targetWeightSpan.textContent) : null;

                                            if (targetWeight !== null) {
                                                const diff = currentWeight - targetWeight;
                                                const sign = diff >= 0 ? '+' : '-';
                                                const diffClass = Math.abs(diff) > 2.0 ? 'alert' :
                                                                  Math.abs(diff) > 1.0 ? 'warning' : 'good';

                                                weightCell.innerHTML =
                                                    currentWeight.toFixed(1) + '% ' +
                                                    '<span class="weight-diff ' + diffClass + '">(' + sign + Math.abs(diff).toFixed(1) + '%)</span>' +
                                                    '<span class="target-weight-hidden" style="display:none;">' + targetWeight + '</span>';
                                            } else {
                                                weightCell.textContent = currentWeight.toFixed(1) + '%';
                                            }

                                            weightCell.classList.add('loaded');
                                        }
                                    });
                                }

                                function updateRebalancingColumns(portfolioTotal) {
                                    if (portfolioTotal <= 0) return;

                                    document.querySelectorAll('.value.loaded').forEach(valueCell => {
                                        const value = parsePrice(valueCell.textContent);
                                        if (value === null) return;

                                        const symbol = valueCell.id.replace('value-', '');
                                        const markCell = document.getElementById('mark-' + symbol);
                                        const markPrice = parsePrice(markCell ? markCell.textContent : null);

                                        // Get target weight from hidden span
                                        const weightCell = document.getElementById('current-weight-' + symbol);
                                        const targetWeightSpan = weightCell ? weightCell.querySelector('.target-weight-hidden') : null;
                                        const targetWeight = targetWeightSpan ? parseFloat(targetWeightSpan.textContent) : null;

                                        if (targetWeight !== null) {
                                            // Calculate rebalancing dollar amount
                                            const targetValue = (targetWeight / 100) * portfolioTotal;
                                            const rebalDollars = targetValue - value;

                                            const rebalDollarsCell = document.getElementById('rebal-dollars-' + symbol);
                                            if (rebalDollarsCell) {
                                                const sign = rebalDollars >= 0 ? '+' : '-';
                                                rebalDollarsCell.textContent = sign + '$' + Math.abs(rebalDollars).toFixed(2);

                                                // Update color class
                                                const direction = Math.abs(rebalDollars) > 0.50 ?
                                                    (rebalDollars > 0 ? 'positive' : 'negative') : 'neutral';
                                                rebalDollarsCell.className = 'price-change loaded rebal-column ' + direction;
                                            }

                                            // Calculate rebalancing share amount
                                            if (markPrice !== null && markPrice > 0) {
                                                const rebalShares = rebalDollars / markPrice;

                                                const rebalSharesCell = document.getElementById('rebal-shares-' + symbol);
                                                if (rebalSharesCell) {
                                                    const sign = rebalShares >= 0 ? '+' : '-';
                                                    rebalSharesCell.textContent = sign + Math.abs(rebalShares).toFixed(2);

                                                    // Update color class (same direction as dollars)
                                                    const direction = Math.abs(rebalDollars) > 0.50 ?
                                                        (rebalDollars > 0 ? 'positive' : 'negative') : 'neutral';
                                                    rebalSharesCell.className = 'price-change loaded rebal-column ' + direction;
                                                }
                                            }
                                        }
                                    });
                                }

                                function updateAllEstVals() {
                                    document.querySelectorAll('tbody tr[data-letf]').forEach(row => {
                                        const symbol = row.querySelector('td:first-child').textContent.trim();
                                        const letfAttr = row.getAttribute('data-letf');
                                        if (!letfAttr) return;

                                        // Parse components: "1,CTA,1,IVV" → [{mult: 1, sym: "CTA"}, {mult: 1, sym: "IVV"}]
                                        const tokens = letfAttr.split(',');
                                        const components = [];
                                        for (let i = 0; i + 1 < tokens.length; i += 2) {
                                            components.push({ mult: parseFloat(tokens[i]), sym: tokens[i + 1] });
                                        }

                                        // Get base price: prefer NAV, fallback to close
                                        const navCell = document.getElementById('nav-' + symbol);
                                        const closeCell = document.getElementById('close-' + symbol);
                                        const navPrice = navCell ? parsePrice(navCell.textContent) : null;
                                        const closePrice = closeCell ? parsePrice(closeCell.textContent) : null;
                                        const basePrice = navPrice !== null ? navPrice : closePrice;

                                        if (basePrice === null) return;

                                        // Calculate sum of (multiplier * dayPercent / 100)
                                        let sumComponent = 0;
                                        let allAvailable = true;
                                        for (const comp of components) {
                                            const dayPct = componentDayPercents[comp.sym];
                                            if (dayPct === undefined) {
                                                allAvailable = false;
                                                break;
                                            }
                                            sumComponent += comp.mult * dayPct / 100;
                                        }

                                        const estValCell = document.getElementById('est-val-' + symbol);
                                        if (estValCell && allAvailable) {
                                            const estVal = (1 + sumComponent) * basePrice;
                                            estValCell.textContent = '$' + estVal.toFixed(2);
                                            estValCell.classList.add('loaded');
                                        }
                                    });
                                }

                                // Rebalancing columns toggle
                                document.addEventListener('DOMContentLoaded', () => {
                                    const rebalToggle = document.getElementById('rebal-toggle');
                                    const body = document.body;

                                    // Load saved state from localStorage
                                    const rebalVisible = localStorage.getItem('ib-viewer-rebal-visible') === 'true';
                                    if (rebalVisible) {
                                        body.classList.add('rebalancing-visible');
                                        rebalToggle.classList.add('active');
                                    }

                                    // Toggle on click
                                    rebalToggle.addEventListener('click', () => {
                                        const isVisible = body.classList.toggle('rebalancing-visible');
                                        rebalToggle.classList.toggle('active');
                                        localStorage.setItem('ib-viewer-rebal-visible', isVisible);
                                    });

                                    // Edit mode toggle
                                    const editToggle = document.getElementById('edit-toggle');
                                    const saveBtn = document.getElementById('save-btn');

                                    editToggle.addEventListener('click', () => {
                                        const isEditing = body.classList.toggle('editing-active');
                                        editToggle.classList.toggle('active');

                                        if (isEditing) {
                                            // Populate inputs from current display values
                                            document.querySelectorAll('.edit-qty').forEach(input => {
                                                const sym = input.getAttribute('data-symbol');
                                                const amountCell = document.getElementById('amount-' + sym);
                                                const displaySpan = amountCell ? amountCell.querySelector('.display-value') : null;
                                                if (displaySpan) input.value = displaySpan.textContent.trim();
                                            });
                                        }
                                    });

                                    // Save button
                                    saveBtn.addEventListener('click', () => {
                                        const updates = [];
                                        document.querySelectorAll('.edit-qty').forEach(input => {
                                            const sym = input.getAttribute('data-symbol');
                                            const weightInput = document.querySelector('.edit-weight[data-symbol="' + sym + '"]');
                                            updates.push({
                                                symbol: sym,
                                                amount: parseInt(input.value) || 0,
                                                targetWeight: weightInput ? parseFloat(weightInput.value) || 0 : 0
                                            });
                                        });

                                        saveBtn.disabled = true;
                                        saveBtn.querySelector('.toggle-label').textContent = 'Saving...';

                                        fetch('/api/portfolio/update', {
                                            method: 'POST',
                                            headers: { 'Content-Type': 'application/json' },
                                            body: JSON.stringify(updates)
                                        }).then(res => {
                                            if (!res.ok) throw new Error('Save failed');
                                            // CSV file watcher will detect changes and trigger SSE reload
                                            // Exit edit mode visually while waiting for reload
                                            body.classList.remove('editing-active');
                                            editToggle.classList.remove('active');
                                        }).catch(err => {
                                            alert('Failed to save: ' + err.message);
                                            saveBtn.disabled = false;
                                            saveBtn.querySelector('.toggle-label').textContent = 'Save';
                                        });
                                    });

                                    // Paste handler for Google Sheets column paste
                                    document.addEventListener('paste', (e) => {
                                        if (!body.classList.contains('editing-active')) return;

                                        const activeEl = document.activeElement;
                                        if (!activeEl || !activeEl.classList.contains('edit-input')) return;

                                        const clipText = (e.clipboardData || window.clipboardData).getData('text');
                                        const lines = clipText.split(/[\r\n]+/).filter(l => l.trim() !== '');

                                        // Only intercept if multi-line (Google Sheets column paste)
                                        if (lines.length <= 1) return;

                                        e.preventDefault();

                                        const column = activeEl.getAttribute('data-column');
                                        const allInputs = Array.from(document.querySelectorAll('.edit-input[data-column="' + column + '"]'));
                                        const startIdx = allInputs.indexOf(activeEl);

                                        for (let i = 0; i < lines.length && (startIdx + i) < allInputs.length; i++) {
                                            const val = lines[i].trim().replace(/,/g, '');
                                            const num = parseFloat(val);
                                            if (!isNaN(num)) {
                                                allInputs[startIdx + i].value = column === 'qty' ? Math.round(num).toString() : num.toString();
                                            }
                                        }
                                    });

                                    // Copy column button handler
                                    document.querySelectorAll('.copy-col-btn').forEach(btn => {
                                        btn.addEventListener('click', () => {
                                            const col = btn.getAttribute('data-column');
                                            const inputs = Array.from(
                                                document.querySelectorAll('.edit-input[data-column="' + col + '"]')
                                            );
                                            const text = inputs.map(i => i.value).join('\n');
                                            navigator.clipboard.writeText(text).then(() => {
                                                const orig = btn.innerHTML;
                                                btn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>';
                                                btn.classList.add('copied');
                                                setTimeout(() => {
                                                    btn.innerHTML = orig;
                                                    btn.classList.remove('copied');
                                                }, 1500);
                                            });
                                        });
                                    });
                                });
                            """.trimIndent())
                        }
                    }

                    script {
                        src = "/static/theme-switcher.js"
                        defer = true
                    }
                }
                body {
                    div(classes = "container") {
                        div(classes = "portfolio-header") {
                            h1 { +"Stock Portfolio" }

                            // Button group for theme and rebalancing toggle
                            div(classes = "header-buttons") {
                                // Edit mode toggle button
                                button(classes = "edit-toggle") {
                                    attributes["aria-label"] = "Toggle edit mode"
                                    attributes["id"] = "edit-toggle"
                                    attributes["type"] = "button"
                                    attributes["title"] = "Edit Qty and Target Weight"

                                    span(classes = "toggle-label") { +"Edit" }
                                }

                                // Save button (visible only in edit mode)
                                button(classes = "save-btn") {
                                    attributes["id"] = "save-btn"
                                    attributes["type"] = "button"
                                    attributes["title"] = "Save changes to CSV"

                                    span(classes = "toggle-label") { +"Save" }
                                }

                                // Rebalancing toggle button
                                button(classes = "rebal-toggle") {
                                    attributes["aria-label"] = "Toggle rebalancing columns"
                                    attributes["id"] = "rebal-toggle"
                                    attributes["type"] = "button"
                                    attributes["title"] = "Show/Hide Weight and Rebalancing columns"

                                    span(classes = "toggle-label") { +"Rebalancing" }
                                }

                                // Theme toggle
                                button(classes = "theme-toggle") {
                                    attributes["aria-label"] = "Toggle theme"
                                    attributes["id"] = "theme-toggle"
                                    attributes["type"] = "button"

                                    // Sun icon (shown in dark mode)
                                    span(classes = "icon-sun") {
                                        unsafe {
                                            raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>""")
                                        }
                                    }

                                    // Moon icon (shown in light mode)
                                    span(classes = "icon-moon") {
                                        unsafe {
                                            raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>""")
                                        }
                                    }
                                }
                            }
                        }

                        if (portfolio.stocks.isEmpty()) {
                            p(classes = "error") {
                                +"No stocks found in the portfolio. Please add stocks to the CSV file."
                            }
                        } else {
                            div(classes = "portfolio-tables-wrapper") {
                                table(classes = "portfolio-table") {
                                    thead {
                                    tr {
                                        th { +"Symbol" }
                                        th {
                                            +"Qty"
                                            button(classes = "copy-col-btn") {
                                                attributes["data-column"] = "qty"
                                                attributes["type"] = "button"
                                                attributes["title"] = "Copy Qty column to clipboard"
                                                unsafe { raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>""") }
                                            }
                                        }
                                        th { +"Last NAV" }
                                        th { +"Est Val" }
                                        th { +"Last" }
                                        th { +"Mark" }
                                        th { +"Day Chg" }
                                        th { +"Day %" }
                                        th { +"Mkt Val" }
                                        th { +"Mkt Val Chg" }
                                        th(classes = "rebal-column") { +"Weight" }
                                        th(classes = "rebal-column") { +"Rebal $" }
                                        th(classes = "rebal-column") { +"Rebal Shares" }
                                        th(classes = "edit-column") {
                                            +"Target %"
                                            button(classes = "copy-col-btn") {
                                                attributes["data-column"] = "weight"
                                                attributes["type"] = "button"
                                                attributes["title"] = "Copy Target % column to clipboard"
                                                unsafe { raw("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>""") }
                                            }
                                        }
                                    }
                                }
                                tbody {
                                    for (stock in portfolio.stocks) {
                                        tr {
                                            // Add LETF data attribute if stock has components
                                            if (stock.letfComponents != null) {
                                                attributes["data-letf"] = stock.letfComponents.joinToString(",") { "${it.first},${it.second}" }
                                            }

                                            // Symbol
                                            td { +stock.label }

                                            // Qty (Amount)
                                            td(classes = "amount") {
                                                id = "amount-${stock.label}"
                                                span(classes = "display-value") { +stock.amount.toString() }
                                                input(type = InputType.number, classes = "edit-input edit-qty") {
                                                    attributes["data-symbol"] = stock.label
                                                    attributes["data-column"] = "qty"
                                                    value = stock.amount.toString()
                                                    attributes["min"] = "0"
                                                    attributes["step"] = "1"
                                                }
                                            }

                                            // Last NAV
                                            td(classes = if (stock.lastNav != null) "price loaded" else "price") {
                                                id = "nav-${stock.label}"
                                                if (stock.lastNav != null) {
                                                    +"${'$'}%.2f".format(stock.lastNav)
                                                } else {
                                                    +"—"
                                                }
                                            }

                                            // Est Val (Estimated Value from LETF components)
                                            td(classes = "price") {
                                                id = "est-val-${stock.label}"
                                                if (stock.letfComponents != null) {
                                                    // Compute initial Est Val server-side if data is available
                                                    val basePrice = stock.lastNav ?: stock.lastClosePrice
                                                    if (basePrice != null) {
                                                        val sumComponent = stock.letfComponents.sumOf { (mult, sym) ->
                                                            val quote = YahooMarketDataService.getQuote(sym)
                                                            if (quote?.regularMarketPrice != null && quote.previousClose != null && quote.previousClose != 0.0) {
                                                                mult * ((quote.regularMarketPrice - quote.previousClose) / quote.previousClose) * 100.0
                                                            } else {
                                                                0.0
                                                            }
                                                        }
                                                        val estVal = (1.0 + sumComponent / 100.0) * basePrice
                                                        +"${'$'}%.2f".format(estVal)
                                                    } else {
                                                        +"—"
                                                    }
                                                } else {
                                                    +"—"
                                                }
                                            }

                                            // Last Close Price
                                            td(classes = if (stock.lastClosePrice != null) "price loaded" else "price") {
                                                id = "close-${stock.label}"
                                                if (stock.lastClosePrice != null) {
                                                    +"${'$'}%.2f".format(stock.lastClosePrice)
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Mark Price
                                            td(classes = if (stock.markPrice != null) "price loaded" else "price") {
                                                id = "mark-${stock.label}"
                                                if (stock.markPrice != null) {
                                                    +"${'$'}%.2f".format(stock.markPrice)
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Day Chg ($ change)
                                            val isZeroChange = stock.priceChangeDollars?.let { Math.abs(it) < 0.001 } ?: false
                                            val changeDirection = if (isZeroChange) "neutral" else stock.priceChangeDirection
                                            val afterHoursClass = if (stock.isMarketClosed) "after-hours" else ""

                                            td(classes = "price-change $changeDirection $afterHoursClass") {
                                                id = "day-change-${stock.label}"
                                                if (stock.priceChangeDollars != null) {
                                                    // Hide if change is effectively zero (within 0.001 tolerance)
                                                    if (isZeroChange) {
                                                        +"—"
                                                    } else {
                                                        val sign = if (stock.priceChangeDollars!! >= 0) "+" else "-"
                                                        +"$sign${'$'}%.2f".format(Math.abs(stock.priceChangeDollars!!))
                                                    }
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Day % (% change)
                                            td(classes = "price-change $changeDirection $afterHoursClass") {
                                                id = "day-percent-${stock.label}"
                                                if (stock.priceChangePercent != null) {
                                                    if (isZeroChange) {
                                                        +"—"
                                                    } else {
                                                        val sign = if (stock.priceChangePercent!! >= 0) "+" else "-"
                                                        +"$sign%.2f%%".format(Math.abs(stock.priceChangePercent!!))
                                                    }
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Mkt Val (Total Value)
                                            td(classes = if (stock.value != null) "value loaded" else "value") {
                                                id = "value-${stock.label}"
                                                if (stock.value != null) {
                                                    +"${'$'}%.2f".format(stock.value)
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Mkt Val Chg (Position value change)
                                            // IMPORTANT: positionChangeDollars is calculated as priceChangeDollars * amount
                                            // This ensures it's always zero when price change is zero (no floating point errors)
                                            td(classes = "price-change $changeDirection $afterHoursClass") {
                                                id = "position-change-${stock.label}"
                                                if (stock.positionChangeDollars != null) {
                                                    // Hide if change is effectively zero (within 0.001 tolerance)
                                                    if (isZeroChange) {
                                                        +"—"
                                                    } else {
                                                        val sign = if (stock.positionChangeDollars!! >= 0) "+" else "-"
                                                        +"$sign${'$'}%.2f".format(Math.abs(stock.positionChangeDollars!!))
                                                    }
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Weight (Current vs Target)
                                            td(classes = "weight-display rebal-column") {
                                                id = "current-weight-${stock.label}"
                                                val stockValue = stock.value
                                                if (stockValue != null && portfolio.totalValue > 0) {
                                                    val currentWeight = (stockValue / portfolio.totalValue) * 100

                                                    if (stock.targetWeight != null) {
                                                        val diff = currentWeight - stock.targetWeight!!
                                                        val sign = if (diff >= 0) "+" else "-"
                                                        val diffClass = when {
                                                            Math.abs(diff) > 2.0 -> "alert"
                                                            Math.abs(diff) > 1.0 -> "warning"
                                                            else -> "good"
                                                        }
                                                        +"%.1f%% ".format(currentWeight)
                                                        span(classes = "weight-diff $diffClass") {
                                                            +"($sign%.1f%%)".format(Math.abs(diff))
                                                        }
                                                        // Hidden span for JavaScript access
                                                        span(classes = "target-weight-hidden") {
                                                            style = "display:none;"
                                                            +stock.targetWeight.toString()
                                                        }
                                                    } else {
                                                        +"%.1f%%".format(currentWeight)
                                                    }
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Rebalance $ (dollar amount to add/reduce)
                                            td(classes = "price-change ${stock.rebalanceDirection(portfolio.totalValue)} rebal-column") {
                                                id = "rebal-dollars-${stock.label}"
                                                if (stock.targetWeight != null) {
                                                    val rebalDollars = stock.rebalanceDollars(portfolio.totalValue)
                                                    if (rebalDollars != null) {
                                                        val sign = if (rebalDollars >= 0) "+" else "-"
                                                        +"$sign${'$'}%.2f".format(Math.abs(rebalDollars))
                                                    } else {
                                                        span(classes = "loading") { +"—" }
                                                    }
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Rebalance Shares (number of shares to buy/sell)
                                            td(classes = "price-change ${stock.rebalanceDirection(portfolio.totalValue)} rebal-column") {
                                                id = "rebal-shares-${stock.label}"
                                                if (stock.targetWeight != null) {
                                                    val rebalShares = stock.rebalanceShares(portfolio.totalValue)
                                                    if (rebalShares != null) {
                                                        val sign = if (rebalShares >= 0) "+" else "-"
                                                        +"$sign%.2f".format(Math.abs(rebalShares))
                                                    } else {
                                                        span(classes = "loading") { +"—" }
                                                    }
                                                } else {
                                                    span(classes = "loading") { +"—" }
                                                }
                                            }

                                            // Target % (edit-only column)
                                            td(classes = "edit-column") {
                                                input(type = InputType.number, classes = "edit-input edit-weight") {
                                                    attributes["data-symbol"] = stock.label
                                                    attributes["data-column"] = "weight"
                                                    value = (stock.targetWeight ?: 0.0).toString()
                                                    attributes["min"] = "0"
                                                    attributes["max"] = "100"
                                                    attributes["step"] = "0.1"
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Separate total table to avoid affecting column widths
                            table(classes = "portfolio-table portfolio-total-table") {
                                tbody {
                                    tr(classes = "total-row") {
                                        td {
                                            +"Total Portfolio Value:"
                                        }
                                        td(classes = "portfolio-footer") {
                                            div(classes = "total-main") {
                                                id = "portfolio-total"
                                                +"${'$'}%.2f".format(portfolio.totalValue)
                                            }
                                            div(classes = "total-change") {
                                                id = "portfolio-day-change"
                                                val sign = if (portfolio.dailyChangeDollars >= 0) "+" else "-"
                                                span(classes = "change-dollars ${portfolio.dailyChangeDirection}") {
                                                    +"$sign${'$'}%.2f".format(Math.abs(portfolio.dailyChangeDollars))
                                                }
                                                +" "
                                                span(classes = "change-percent ${portfolio.dailyChangeDirection}") {
                                                    +"($sign%.2f%%)".format(Math.abs(portfolio.dailyChangePercent))
                                                }
                                            }
                                        }
                                    }
                                    tr(classes = "timestamp-row") {
                                        td {
                                            +"Last Updated:"
                                        }
                                        td(classes = "timestamp-value") {
                                            id = "last-update-time"
                                            +"Loading..."
                                        }
                                    }
                                }
                            }
                            }

                            p(classes = "info") {
                                +"Showing ${portfolio.stocks.size} stock(s)"
                            }
                        }
                    }
                }
            }
        }

        // Update portfolio CSV with edited qty/weight values
        post("/api/portfolio/update") {
            try {
                val body = call.receiveText()
                val updates = Json.parseToJsonElement(body).jsonArray

                val csvPath = PortfolioState.csvPath
                val path = Paths.get(csvPath)

                // Read existing CSV to preserve row order and letf column
                val existingRows = mutableListOf<Map<String, String>>()
                val headers = mutableListOf<String>()

                if (Files.exists(path)) {
                    BufferedReader(FileReader(path.toFile())).use { reader ->
                        val csvFormat = CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .build()
                        CSVParser(reader, csvFormat).use { parser ->
                            headers.addAll(parser.headerNames)
                            for (record in parser) {
                                val row = mutableMapOf<String, String>()
                                for ((i, h) in headers.withIndex()) {
                                    row[h] = if (i < record.size()) record.get(i) else ""
                                }
                                existingRows.add(row)
                            }
                        }
                    }
                }

                // Build update map: symbol -> {amount, targetWeight}
                val updateMap = mutableMapOf<String, Pair<Int, Double>>()
                for (element in updates) {
                    val obj = element.jsonObject
                    val symbol = obj["symbol"]?.jsonPrimitive?.content ?: continue
                    val amount = obj["amount"]?.jsonPrimitive?.int ?: continue
                    val targetWeight = obj["targetWeight"]?.jsonPrimitive?.double ?: 0.0
                    updateMap[symbol] = amount to targetWeight
                }

                // Write updated CSV
                FileWriter(path.toFile()).use { writer ->
                    val csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader(*headers.toTypedArray())
                        .build()
                    CSVPrinter(writer, csvFormat).use { printer ->
                        for (row in existingRows) {
                            val symbol = row["stock_label"] ?: ""
                            val update = updateMap[symbol]
                            val values = headers.map { header ->
                                when {
                                    header == "amount" && update != null -> update.first.toString()
                                    header == "target_weight" && update != null -> update.second.toString()
                                    else -> row[header] ?: ""
                                }
                            }
                            printer.printRecord(values)
                        }
                    }
                }

                call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondText(
                    "{\"status\":\"error\",\"message\":\"${e.message?.replace("\"", "\\\"")}\"}",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        }

        // Server-Sent Events (SSE) endpoint for streaming price updates
        get("/api/prices/stream") {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.headers.append(HttpHeaders.ContentType, "text/event-stream")
            call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
            call.response.headers.append(HttpHeaders.Connection, "keep-alive")

            // Create a channel for price updates
            val channel = Channel<String>(Channel.BUFFERED)

            // Register callback for price updates
            val callback: (String, com.portfoliohelper.service.yahoo.YahooQuote) -> Unit = { symbol, quote ->
                val json = buildString {
                    append("{")
                    append("\"symbol\":\"$symbol\",")
                    append("\"markPrice\":${quote.regularMarketPrice},")
                    append("\"lastClosePrice\":${quote.previousClose},")
                    append("\"isMarketClosed\":${quote.isMarketClosed},")
                    append("\"timestamp\":${quote.lastUpdateTime}")
                    append("}")
                }
                channel.trySend("data: $json\n\n")
            }

            YahooMarketDataService.onPriceUpdate(callback)

            // Register callback for NAV updates
            val navCallback: (String, com.portfoliohelper.service.nav.NavData) -> Unit = { symbol, navData ->
                val json = buildString {
                    append("{")
                    append("\"type\":\"nav\",")
                    append("\"symbol\":\"$symbol\",")
                    append("\"nav\":${navData.nav},")
                    append("\"timestamp\":${navData.lastFetchTime}")
                    append("}")
                }
                channel.trySend("data: $json\n\n")
            }

            NavService.onNavUpdate(navCallback)

            // Listen for portfolio reload events
            launch {
                PortfolioUpdateBroadcaster.reloadEvents.collect {
                    val json = "{\"type\":\"reload\",\"timestamp\":${it.timestamp}}"
                    channel.send("data: $json\n\n")
                }
            }

            // Stream updates to client
            try {
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    // Send initial keepalive
                    write(":keepalive\n\n")
                    flush()

                    // Stream price updates
                    for (message in channel) {
                        write(message)
                        flush()
                    }
                }
            } catch (e: Exception) {
                // Client disconnected
                channel.close()
            }
        }

        // Serve static files (CSS, JS)
        staticResources("/static", "static")
    }
}
