// ── display-worker.js — Background worker for portfolio display computation ───
// Depends on: globals.js, utils.js (parsePrice available on main thread)
// Load order: after rebalance.js, before portfolio.js

// ── Section 1: Worker source (pure JS, no DOM) ────────────────────────────────

const _DISPLAY_WORKER_SRC = `
'use strict';

function getStockFxRate(stockCcy, fxRates) {
    if (stockCcy === 'USD') return 1.0;
    if (/^[A-Z]{2}[a-z]$/.test(stockCcy)) {
        const parent = stockCcy.toUpperCase();
        const rate = fxRates[parent];
        return rate != null ? rate / 100 : null;
    }
    const rate = fxRates[stockCcy];
    return rate != null ? rate : null;
}

function formatCurrency(val) {
    const sign = val < 0 ? '-' : '';
    return sign + Math.abs(val).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatSignedCurrency(val) {
    return (val >= 0 ? '+' : '') + formatCurrency(val);
}

function toDisplayCurrency(usdVal, fxRates, cdc) {
    const rate = fxRates[cdc];
    return (rate && rate !== 0) ? usdVal / rate : usdVal;
}

function formatDisplayCurrency(usdVal, fxRates, cdc) {
    const converted = toDisplayCurrency(usdVal, fxRates, cdc);
    const sign = converted < 0 ? '-' : '';
    return sign + Math.abs(converted).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatSignedDisplayCurrency(usdVal, fxRates, cdc) {
    const converted = toDisplayCurrency(usdVal, fxRates, cdc);
    return (converted >= 0 ? '+' : '') + formatDisplayCurrency(usdVal, fxRates, cdc);
}

function toStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc) {
    const toUsd = getStockFxRate(stockCcy, fxRates);
    if (toUsd === null) return null;
    return toDisplayCurrency(nativeVal * toUsd, fxRates, cdc);
}

function formatStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc) {
    const converted = toStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc);
    if (converted === null) return '—';
    const sign = converted < 0 ? '-' : '';
    return sign + Math.abs(converted).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function formatSignedStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc) {
    const converted = toStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc);
    if (converted === null) return '—';
    return (converted >= 0 ? '+' : '') + formatStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc);
}

function blendedIbkrRate(tiers, amount) {
    if (amount <= 0 || !tiers.length) return null;
    const baseCap = tiers[0].upTo;
    if (baseCap === null || amount <= baseCap) return null;
    let remaining = amount, totalInterest = 0, prevUpTo = 0;
    for (const tier of tiers) {
        const capacity = tier.upTo !== null ? tier.upTo - prevUpTo : Infinity;
        const inTier = Math.min(remaining, capacity);
        totalInterest += inTier * tier.rate / 100;
        remaining -= inTier;
        if (remaining <= 0) break;
        prevUpTo = tier.upTo ?? 0;
    }
    return (totalInterest / amount) * 100;
}

function buildDayChangeHTML(changeDollars, changePercent, changeClass, fxRates, cdc) {
    const sign = changeDollars >= 0 ? '+' : '-';
    return '<span class="change-dollars ' + changeClass + '">' + formatSignedDisplayCurrency(changeDollars, fxRates, cdc) + '</span> ' +
        '<span class="change-percent ' + changeClass + '">(' + sign + Math.abs(changePercent).toFixed(2) + '%)</span>';
}

function deriveRebalFromMarginPct(pct, stockVal, marginUsd) {
    const ec = stockVal + marginUsd;
    return (pct / 100) * ec + stockVal + marginUsd;
}

function getRebalTotal(stockVal, marginUsd, rebalTargetUsd, marginTargetPct) {
    if (marginTargetPct !== null) return deriveRebalFromMarginPct(marginTargetPct, stockVal, marginUsd);
    if (rebalTargetUsd !== null && rebalTargetUsd > 0) return rebalTargetUsd;
    return stockVal + Math.max(marginUsd, 0);
}

function deriveMarginPct(rebalTotal, stockVal, marginUsd) {
    const ec = stockVal + marginUsd;
    if (ec <= 0) return 0;
    const marginPct = (marginUsd - (rebalTotal - stockVal)) / ec * 100;
    if (marginPct >= 0) return 0;
    return -marginPct;
}

function applyProportionalSpillover(alloc, eligible, remaining, sign) {
    for (const s of eligible)
        alloc[s.symbol] = (alloc[s.symbol] ?? 0) + (s.targetWeight / 100) * remaining * sign;
}

function computeUndervalueFirst(eligible, totalStockValue, delta) {
    const alloc = {};
    for (const s of eligible) alloc[s.symbol] = 0;
    const finalTotal = totalStockValue + delta;
    const sign = delta >= 0 ? 1 : -1;
    const sorted = [...eligible].sort((a, b) =>
        sign * (
            (a.currentValue / finalTotal - a.targetWeight / 100) -
            (b.currentValue / finalTotal - b.targetWeight / 100)
        )
    );
    let remaining = Math.abs(delta);
    for (const s of sorted) {
        if (remaining <= 0) break;
        const target = finalTotal * (s.targetWeight / 100);
        const amount = Math.min(remaining, Math.max(0, (target - s.currentValue) * sign));
        alloc[s.symbol] = amount * sign;
        remaining -= amount;
    }
    if (remaining > 0) applyProportionalSpillover(alloc, eligible, remaining, sign);
    return alloc;
}

function computeWaterfall(eligible, totalStockValue, delta) {
    const alloc = {};
    for (const s of eligible) alloc[s.symbol] = 0;
    const finalTotal = totalStockValue + delta;
    const sign = delta >= 0 ? 1 : -1;
    const currentDev = {};
    for (const s of eligible) {
        currentDev[s.symbol] = (s.currentValue / finalTotal) - (s.targetWeight / 100);
    }
    const sorted = [...eligible].sort((a, b) => sign * (currentDev[a.symbol] - currentDev[b.symbol]));
    let remaining = Math.abs(delta);
    for (let i = 0; i < sorted.length && remaining > 0; i++) {
        const groupDev = currentDev[sorted[0].symbol];
        const nextDev = i + 1 < sorted.length ? currentDev[sorted[i + 1].symbol] : sign * Infinity;
        const groupSize = i + 1;
        const costToLevel = (nextDev - groupDev) * sign * finalTotal * groupSize;
        if (remaining >= costToLevel) {
            for (let j = 0; j <= i; j++) {
                alloc[sorted[j].symbol] += (nextDev - groupDev) * finalTotal;
                currentDev[sorted[j].symbol] = nextDev;
            }
            remaining -= costToLevel;
        } else {
            const perStock = remaining / groupSize;
            for (let j = 0; j <= i; j++) {
                alloc[sorted[j].symbol] += perStock * sign;
                currentDev[sorted[j].symbol] += (perStock / finalTotal) * sign;
            }
            remaining = 0;
        }
    }
    if (remaining > 0) applyProportionalSpillover(alloc, eligible, remaining, sign);
    return alloc;
}

function computeAllocations(delta, stocks, totalStockValue, mode) {
    const result = {};
    if (mode === 'PROPORTIONAL') {
        for (const s of stocks)
            result[s.symbol] = s.targetWeight !== null ? (s.targetWeight / 100) * delta : null;
    } else if (mode === 'CURRENT_WEIGHT') {
        for (const s of stocks) {
            const w = totalStockValue > 0 ? s.currentValue / totalStockValue : 0;
            result[s.symbol] = w * delta;
        }
    } else if (mode === 'UNDERVALUED_PRIORITY' || mode === 'WATERFALL') {
        const eligible = stocks.filter(s => s.targetWeight !== null);
        const alloc = mode === 'WATERFALL'
            ? computeWaterfall(eligible, totalStockValue, delta)
            : computeUndervalueFirst(eligible, totalStockValue, delta);
        for (const s of eligible) result[s.symbol] = alloc[s.symbol] ?? 0;
    }
    return result;
}

function compute(snap) {
    const {
        stocks, previousMarkTexts, rawMarkPrices, rawClosePrices, fxRates, stockCurrencies,
        symbolMarketClosed, symbolTradingPeriodEndMs, componentDayPercents, navValues,
        currentDisplayCurrency: cdc, showStockDisplayCurrency,
        cashEntries, ibkrRatesData,
        rebalTargetUsd, marginTargetPct, allocAddMode, allocReduceMode,
        nowMs
    } = snap;

    // Compute cash totals from DOM-sourced entries
    let cashTotalUsd = 0, marginUsd = 0, cashTotalKnown = true;
    const perEntryCash = {};
    for (const e of cashEntries) {
        const rate = e.currency === 'USD' ? 1 : (fxRates[e.currency] ?? null);
        if (rate === null) {
            cashTotalKnown = false;
            perEntryCash[e.entryId] = '\\u2014';
            continue;
        }
        const usd = e.amount * rate;
        perEntryCash[e.entryId] = formatDisplayCurrency(usd, fxRates, cdc);
        cashTotalUsd += usd;
        if (e.marginFlag) marginUsd += usd;
    }

    let markTotal = 0;
    let prevTotal = 0;
    let stockGrossValueKnown = true;
    const perStockData = {};

    // Pass 1: per-stock prices, values, day change, est val
    for (const stock of stocks) {
        const { symbol, qty, targetWeight, letfComponents } = stock;
        const stockCcy = stockCurrencies[symbol] ?? 'USD';
        const fxRate = getStockFxRate(stockCcy, fxRates);
        const markPrice = rawMarkPrices[symbol] ?? null;
        const closePrice = rawClosePrices[symbol] ?? null;

        const markLoaded = markPrice !== null;
        const markText = markLoaded ? markPrice.toFixed(2) : '—';
        const markAfterHours = !!symbolMarketClosed[symbol];
        const closeLoaded = closePrice !== null;
        const closeText = closeLoaded ? closePrice.toFixed(2) : '—';

        let dayChangeDollars = null, dayChangeText = '', dayChangeClass = '';
        let dayPctText = '', dayPctClass = '';
        let positionChangeText = '—', positionChangeClass = '';

        if (markPrice !== null && closePrice !== null) {
            dayChangeDollars = markPrice - closePrice;
            dayChangeText = (dayChangeDollars >= 0 ? '+' : '-') + Math.abs(dayChangeDollars).toFixed(2);

            const changePercent = (dayChangeDollars / closePrice) * 100;
            const sign = changePercent >= 0 ? '+' : '\\u2212';
            dayPctText = sign + Math.abs(changePercent).toFixed(2) + '%';
            const isNeutralPct = Math.abs(changePercent) < 0.1;
            const colorClass = isNeutralPct ? 'neutral' : changePercent > 0 ? 'positive' : 'negative';
            dayPctClass = 'mark-day-pct ' + colorClass + (markAfterHours ? ' after-hours' : '');
            dayChangeClass = 'loaded ' + colorClass + (markAfterHours ? ' after-hours' : '');

            if (fxRate !== null) {
                const positionChange = dayChangeDollars * qty;
                const posDir = Math.abs(positionChange) < 0.005 ? 'neutral' : positionChange > 0 ? 'positive' : 'negative';
                if (showStockDisplayCurrency) {
                    positionChangeText = formatSignedStockDisplayCurrency(positionChange, stockCcy, fxRates, cdc);
                    if (positionChangeText === null) positionChangeText = '—';
                } else {
                    positionChangeText = formatSignedCurrency(positionChange);
                }
                positionChangeClass = 'loaded ' + posDir + (markAfterHours ? ' after-hours' : '');
            }
        }

        // Value (in native currency units, matching original updatePriceInUI behavior)
        const price = markPrice !== null ? markPrice : closePrice;
        let valueText = '—', valueLoaded = false, valueUsd = null;
        if (price !== null && fxRate !== null) {
            const nativeVal = price * qty;
            valueUsd = nativeVal * fxRate;
            valueText = showStockDisplayCurrency
                ? formatStockDisplayCurrency(nativeVal, stockCcy, fxRates, cdc)
                : formatCurrency(nativeVal);
            valueLoaded = true;
        } else if (price !== null && fxRate === null) {
            stockGrossValueKnown = false;
        }

        // Portfolio totals (USD)
        if (fxRate === null) {
            stockGrossValueKnown = false;
        } else {
            if (markPrice === null && closePrice === null) stockGrossValueKnown = false;
            if (markPrice !== null) markTotal += markPrice * qty * fxRate;
            if (closePrice !== null) prevTotal += closePrice * qty * fxRate;
        }

        // Flash: triggered only when displayed mark price text changes
        const prevMarkText = previousMarkTexts[symbol] ?? null;
        const markPriceChanged = prevMarkText !== null && markText !== '—' && markText !== prevMarkText;

        // LETF est val
        let estValText = null, estValLoaded = false, estValRaw = null;
        if (letfComponents && letfComponents.length > 0) {
            const marketClosed = symbolMarketClosed[symbol] !== false;
            const closeTimeMs = symbolTradingPeriodEndMs[symbol] ?? null;
            const stale = marketClosed && (closeTimeMs === null || nowMs - closeTimeMs > 12 * 3600 * 1000);
            if (stale) {
                estValText = '—';
            } else {
                const navPrice = navValues[symbol] ?? null;
                const basePrice = navPrice !== null ? navPrice : closePrice;
                if (basePrice !== null) {
                    let sumComponent = 0, allAvailable = true;
                    for (const comp of letfComponents) {
                        const dayPct = componentDayPercents[comp.sym];
                        if (dayPct === undefined) { allAvailable = false; break; }
                        sumComponent += comp.mult * dayPct / 100;
                    }
                    if (allAvailable) {
                        estValRaw = (1 + sumComponent) * basePrice;
                        estValText = estValRaw.toFixed(2);
                        estValLoaded = true;
                    }
                }
            }
        }

        perStockData[symbol] = {
            markText, markLoaded, markAfterHours,
            closeText, closeLoaded,
            dayChangeDollars, dayChangeText, dayChangeClass,
            dayPctText, dayPctClass,
            positionChangeText, positionChangeClass,
            valueText, valueLoaded, valueUsd,
            markPriceChanged,
            estValText, estValLoaded, estValRaw,
            qty, targetWeight, fxRate, stockCcy, markPrice, closePrice
        };
    }

    // Pass 2: weights, rebal, alloc
    const rebalTotal = getRebalTotal(markTotal, marginUsd, rebalTargetUsd, marginTargetPct);
    const allocStocks = [];
    let totalStockValueForAlloc = 0;

    for (const stock of stocks) {
        const { symbol, targetWeight } = stock;
        const d = perStockData[symbol];

        // Weight
        let weightHTML = '', weightLoaded = false;
        if (!stockGrossValueKnown) {
            weightHTML = 'N/A';
        } else if (markTotal > 0 && d.valueUsd !== null) {
            const currentWeight = (d.valueUsd / markTotal) * 100;
            if (targetWeight !== null && !isNaN(targetWeight)) {
                const diff = currentWeight - targetWeight;
                const diffClass = Math.abs(diff) > 1.0 ? (diff > 0 ? 'alert-over' : 'alert-under')
                                : Math.abs(diff) > 0.2 ? 'warning' : 'good';
                const diffSign = diff >= 0 ? '+' : '';
                weightHTML = '<span class="weight-cur">' + currentWeight.toFixed(1) + '%</span>' +
                             '<span class="weight-sep">/</span>' +
                             '<span class="weight-tgt">' + targetWeight.toFixed(1) + '%</span>' +
                             '<span class="weight-diff ' + diffClass + '">' + diffSign + diff.toFixed(1) + '%</span>';
            } else {
                weightHTML = currentWeight.toFixed(1) + '%';
            }
            weightLoaded = true;
        }
        d.weightHTML = weightHTML;
        d.weightLoaded = weightLoaded;

        // Rebal
        let rebalDollarsText = '', rebalDollarsClass = 'action-neutral rebal-column';
        let rebalQtyText = '', rebalQtyClass = 'action-neutral rebal-column col-moreinfo';
        if (!stockGrossValueKnown) {
            rebalDollarsText = 'N/A'; rebalQtyText = 'N/A';
        } else if (rebalTotal > 0 && targetWeight !== null && !isNaN(targetWeight)) {
            const effectivePrice = d.markPrice !== null ? d.markPrice : d.closePrice;
            if (effectivePrice !== null) {
                const nativeValue = effectivePrice * d.qty;
                const targetValue = (targetWeight / 100) * rebalTotal;
                const rebalDollars = targetValue - nativeValue;
                const direction = Math.abs(rebalDollars) > 0.50
                    ? (rebalDollars > 0 ? 'action-positive' : 'action-negative') : 'action-neutral';
                rebalDollarsText = formatSignedCurrency(rebalDollars);
                rebalDollarsClass = 'action-neutral loaded rebal-column ' + direction;
                if (d.markPrice !== null && d.markPrice > 0) {
                    const rebalShares = rebalDollars / d.markPrice;
                    rebalQtyText = (rebalShares >= 0 ? '+' : '-') + Math.abs(rebalShares).toFixed(2);
                    rebalQtyClass = 'action-neutral loaded rebal-column col-moreinfo ' + direction;
                }
            }
        }
        d.rebalDollarsText = rebalDollarsText; d.rebalDollarsClass = rebalDollarsClass;
        d.rebalQtyText = rebalQtyText; d.rebalQtyClass = rebalQtyClass;

        // Alloc stock list
        const effectivePrice = d.markPrice !== null ? d.markPrice : d.closePrice;
        const nativeValue = effectivePrice !== null ? effectivePrice * d.qty : 0;
        allocStocks.push({ symbol, markPrice: d.markPrice, targetWeight, currentValue: nativeValue });
        totalStockValueForAlloc += nativeValue;
    }

    // Alloc columns
    const allocDelta = rebalTotal - markTotal;
    const allocMode = allocDelta >= 0 ? allocAddMode : allocReduceMode;
    const allocations = stockGrossValueKnown
        ? computeAllocations(allocDelta, allocStocks, totalStockValueForAlloc, allocMode)
        : {};

    for (const stock of stocks) {
        const { symbol } = stock;
        const d = perStockData[symbol];
        const s = allocStocks.find(x => x.symbol === symbol);
        let allocDollarsText = '', allocDollarsClass = 'action-neutral alloc-column';
        let allocQtyText = '', allocQtyClass = 'action-neutral alloc-column col-moreinfo';

        if (!stockGrossValueKnown) {
            allocDollarsText = 'N/A'; allocQtyText = 'N/A';
        } else {
            const amt = allocations[symbol]; // in USD
            if (amt != null) {
                const fxRate = d.fxRate;
                const amtNative = (fxRate !== null && fxRate > 0) ? amt / fxRate : amt;
                const dir = amtNative > 0.50 ? 'action-positive' : amtNative < -0.50 ? 'action-negative' : 'action-neutral';
                allocDollarsText = formatSignedCurrency(amtNative);
                allocDollarsClass = 'action-neutral loaded alloc-column ' + dir;
                if (s && s.markPrice > 0) {
                    const qty = amtNative / s.markPrice;
                    allocQtyText = (qty >= 0 ? '+' : '-') + Math.abs(qty).toFixed(2);
                    allocQtyClass = 'action-neutral loaded alloc-column col-moreinfo ' + dir;
                }
            }
        }
        d.allocDollarsText = allocDollarsText; d.allocDollarsClass = allocDollarsClass;
        d.allocQtyText = allocQtyText; d.allocQtyClass = allocQtyClass;
    }

    // Totals
    const dayChangeDollars = markTotal - prevTotal;
    const changePercent = prevTotal > 0 ? (dayChangeDollars / prevTotal) * 100 : 0;
    const isZeroTotalChange = Math.abs(dayChangeDollars) < 0.005;
    const changeClass = isZeroTotalChange ? 'neutral' : dayChangeDollars > 0 ? 'positive' : 'negative';

    const stockGrossTotal = stockGrossValueKnown
        ? formatDisplayCurrency(markTotal, fxRates, cdc) : 'N/A';
    const portfolioDayChangeHTML = !stockGrossValueKnown ? 'N/A'
        : buildDayChangeHTML(dayChangeDollars, changePercent, changeClass, fxRates, cdc);

    const prevGrandTotal = prevTotal + cashTotalUsd;
    const totalChangePercent = prevGrandTotal !== 0 ? (dayChangeDollars / Math.abs(prevGrandTotal)) * 100 : 0;
    const totalDayChangeHTML = !stockGrossValueKnown ? 'N/A'
        : buildDayChangeHTML(dayChangeDollars, totalChangePercent, changeClass, fxRates, cdc);

    const grandTotal = (stockGrossValueKnown && cashTotalKnown)
        ? formatDisplayCurrency(markTotal + cashTotalUsd, fxRates, cdc) : 'N/A';

    // Margin
    const showMarginRow = marginUsd < 0;
    let marginDisplay = '', marginPctText = '';
    if (marginUsd < 0) {
        if (!cashTotalKnown) {
            marginDisplay = 'N/A';
        } else {
            marginDisplay = formatDisplayCurrency(-marginUsd, fxRates, cdc);
            const denominator = markTotal + marginUsd;
            const pct = denominator !== 0 ? Math.abs(marginUsd / denominator) * 100 : 0;
            marginPctText = ' (' + pct.toFixed(1) + '%)';
        }
    }

    // Cash total text
    const cashTotalText = cashTotalKnown ? formatDisplayCurrency(cashTotalUsd, fxRates, cdc) : 'N/A';

    // IBKR interest computation
    let ibkrResult = null;
    if (ibkrRatesData && ibkrRatesData.currencies && ibkrRatesData.currencies.length > 0) {
        const marginCurrencies = new Set(['USD']);
        for (const e of cashEntries) {
            if (e.marginFlag) {
                const ccy = e.currency?.toUpperCase();
                if (ccy && ccy !== 'P') marginCurrencies.add(ccy);
            }
        }
        const filteredCurrencies = ibkrRatesData.currencies.filter(c =>
            marginCurrencies.has(c.currency.toUpperCase())
        );
        if (filteredCurrencies.length > 0) {
            const loanUsd = marginUsd < 0 ? -marginUsd : 0;
            let currentUsd = 0;
            let cheapestUsd = null, cheapestCcy = null;
            const perCurrency = {};
            for (const c of filteredCurrencies) {
                const ccy = c.currency;
                const tiers = c.tiers;
                const baseRate = tiers[0]?.rate;
                if (baseRate === undefined) continue;
                const fxRate = ccy === 'USD' ? 1 : (fxRates[ccy] ?? null);
                if (fxRate === null || fxRate <= 0) continue;
                let nativeLoan = 0;
                for (const e of cashEntries) {
                    if (e.marginFlag && e.currency.toUpperCase() === ccy.toUpperCase() && e.amount < 0) {
                        nativeLoan += -e.amount;
                    }
                }
                const blended = nativeLoan > 0 ? blendedIbkrRate(tiers, nativeLoan) : null;
                const effectiveRate = blended !== null ? blended : baseRate;
                const hypotheticalNative = loanUsd > 0 ? loanUsd / fxRate : nativeLoan;
                const hypotheticalBlended = blendedIbkrRate(tiers, hypotheticalNative);
                perCurrency[ccy] = hypotheticalBlended !== null
                    ? hypotheticalBlended.toFixed(3) + '% (' + baseRate.toFixed(3) + '%)'
                    : baseRate.toFixed(3) + '%';
                const days = c.days;
                const nativeDaily = nativeLoan > 0 ? nativeLoan * effectiveRate / 100.0 / days : 0;
                currentUsd += nativeDaily * fxRate;
                if (loanUsd > 0) {
                    const hypotheticalRate = hypotheticalBlended !== null ? hypotheticalBlended : baseRate;
                    const interest = hypotheticalNative * hypotheticalRate / 100.0 / days * fxRate;
                    if (cheapestUsd === null || interest < cheapestUsd) {
                        cheapestUsd = interest;
                        cheapestCcy = ccy;
                    }
                }
            }
            const diff = (cheapestUsd !== null && currentUsd > 0) ? currentUsd - cheapestUsd : null;
            let label = 'Saving';
            if (cheapestCcy != null && filteredCurrencies.length === 2) {
                if (cheapestCcy === 'USD') {
                    const otherCcy = filteredCurrencies.find(c => c.currency.toUpperCase() !== 'USD')?.currency;
                    if (otherCcy) label = 'Saving (Sell USD.' + otherCcy + ')';
                } else {
                    label = 'Saving (Buy USD.' + cheapestCcy + ')';
                }
            }
            ibkrResult = { perCurrency, currentUsd, cheapestUsd, cheapestCcy, diff, label };
        }
    }

    // Margin target display
    const marginTargetUsd = marginUsd - (rebalTotal - markTotal);
    const marginTargetText = marginTargetUsd < 0 ? formatDisplayCurrency(-marginTargetUsd, fxRates, cdc) : '';

    // Margin input placeholder
    const marginInputPlaceholder = deriveMarginPct(rebalTotal, markTotal, marginUsd).toFixed(1);

    // Rebal input placeholder
    const baseUsd = marginTargetPct !== null ? rebalTotal : markTotal + Math.max(marginUsd, 0);
    const rebalTargetPlaceholder = Math.abs(toDisplayCurrency(baseUsd, fxRates, cdc)).toLocaleString('en-US', {
        minimumFractionDigits: 2, maximumFractionDigits: 2
    });

    // Build clean perStock result
    const perStock = {};
    for (const stock of stocks) {
        const { symbol } = stock;
        const d = perStockData[symbol];
        perStock[symbol] = {
            markText: d.markText, markLoaded: d.markLoaded, markAfterHours: d.markAfterHours,
            closeText: d.closeText, closeLoaded: d.closeLoaded,
            dayChangeDollars: d.dayChangeDollars,
            dayChangeText: d.dayChangeText, dayChangeClass: d.dayChangeClass,
            dayPctText: d.dayPctText, dayPctClass: d.dayPctClass,
            positionChangeText: d.positionChangeText, positionChangeClass: d.positionChangeClass,
            valueText: d.valueText, valueLoaded: d.valueLoaded, markPriceChanged: d.markPriceChanged,
            weightHTML: d.weightHTML, weightLoaded: d.weightLoaded,
            rebalDollarsText: d.rebalDollarsText, rebalDollarsClass: d.rebalDollarsClass,
            rebalQtyText: d.rebalQtyText, rebalQtyClass: d.rebalQtyClass,
            allocDollarsText: d.allocDollarsText, allocDollarsClass: d.allocDollarsClass,
            allocQtyText: d.allocQtyText, allocQtyClass: d.allocQtyClass,
            estValText: d.estValText, estValLoaded: d.estValLoaded, estValRaw: d.estValRaw
        };
    }

    return {
        perStock,
        totals: {
            stockGrossTotal, stockGrossValueKnown,
            stockGrossValRaw: markTotal,
            prevStockGrossValRaw: prevTotal,
            dayChangeDollarsRaw: dayChangeDollars,
            portfolioDayChangeHTML, totalDayChangeHTML,
            grandTotal, grandTotalKnown: stockGrossValueKnown && cashTotalKnown,
            marginDisplay, marginPctText, showMarginRow,
            marginTargetText, marginInputPlaceholder, rebalTargetPlaceholder,
            cashTotalText, cashTotalKnown,
            cashTotalRaw: cashTotalUsd, marginUsdRaw: marginUsd,
            perEntryCash, ibkrResult
        }
    };
}

self.onmessage = function(e) {
    try {
        self.postMessage(compute(e.data));
    } catch (err) {
        console.error('[display-worker] compute error:', err);
        self.postMessage({ error: err.message });
    }
};
`;

// ── Section 2: Worker lifecycle ───────────────────────────────────────────────

var _displayWorker = null;
var _workerRunning = false;
var _pendingUpdate = false;

function _getDisplayWorker() {
    if (!_displayWorker) {
        const blob = new Blob([_DISPLAY_WORKER_SRC], { type: 'application/javascript' });
        const url = URL.createObjectURL(blob);
        _displayWorker = new Worker(url);
    }
    return _displayWorker;
}

function scheduleDisplayUpdate() {
    if (_workerRunning) { _pendingUpdate = true; return; }
    _dispatchDisplayUpdate();
}

function _dispatchDisplayUpdate() {
    _workerRunning = true;
    _pendingUpdate = false;
    const snapshot = _buildSnapshot();
    const w = _getDisplayWorker();
    w.onmessage = function(e) {
        _workerRunning = false;
        if (e.data && !e.data.error) _applyDisplayResult(e.data);
        if (_pendingUpdate) { _pendingUpdate = false; _dispatchDisplayUpdate(); }
    };
    w.onerror = function(err) {
        console.error('[display-worker]', err);
        _workerRunning = false;
        if (_pendingUpdate) { _pendingUpdate = false; _dispatchDisplayUpdate(); }
    };
    w.postMessage(snapshot);
}

// ── Section 3: Snapshot builder ───────────────────────────────────────────────

function _buildSnapshot() {
    const stocks = [];
    document.querySelectorAll('#stock-view-table tbody tr[data-symbol]').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        const qty = parseFloat(row.dataset.qty) || 0;
        const tw = parseFloat(row.dataset.weight);
        const targetWeight = isNaN(tw) ? null : tw;
        const letfAttr = row.dataset.letf || null;
        let letfComponents = null;
        if (letfAttr) {
            const tokens = letfAttr.split(',');
            letfComponents = [];
            for (let i = 0; i + 1 < tokens.length; i += 2) {
                letfComponents.push({ mult: parseFloat(tokens[i]), sym: tokens[i + 1] });
            }
        }
        stocks.push({ symbol, qty, targetWeight, letfComponents });
    });

    // Read previously displayed mark price text to detect changes (for row flash).
    // Comparing text strings avoids false positives from high-precision SSE floats vs
    // the 2-decimal text that was previously rendered (e.g. 123.4567 !== 123.46).
    const previousMarkTexts = {};
    for (const s of stocks) {
        const span = document.querySelector('#mark-' + s.symbol + ' .mark-price-value');
        if (span && span.textContent && span.textContent !== '—') {
            previousMarkTexts[s.symbol] = span.textContent.trim();
        }
    }

    // Merge server-rendered DOM prices as fallback for symbols not yet received via SSE.
    // This matches the original updateTotalValue behavior of falling back to cell text.
    const rawMarkPricesSnap = Object.assign({}, rawMarkPrices);
    const rawClosePricesSnap = Object.assign({}, rawClosePrices);
    for (const s of stocks) {
        if (rawMarkPricesSnap[s.symbol] == null) {
            const span = document.querySelector('#mark-' + s.symbol + ' .mark-price-value');
            const p = span ? parsePrice(span.textContent) : null;
            if (p !== null) rawMarkPricesSnap[s.symbol] = p;
        }
        if (rawClosePricesSnap[s.symbol] == null) {
            const cell = document.getElementById('close-' + s.symbol);
            const p = cell ? parsePrice(cell.textContent) : null;
            if (p !== null) rawClosePricesSnap[s.symbol] = p;
        }
    }

    const cashEntries = [...document.querySelectorAll('[data-cash-entry]')].map(r => ({
        entryId: r.dataset.entryId,
        currency: r.dataset.currency,
        amount: parseFloat(r.dataset.amount),
        marginFlag: r.dataset.marginFlag === 'true'
    }));

    return {
        stocks,
        previousMarkTexts,
        rawMarkPrices: rawMarkPricesSnap,
        rawClosePrices: rawClosePricesSnap,
        fxRates: Object.assign({}, fxRates),
        stockCurrencies: Object.assign({}, stockCurrencies),
        symbolMarketClosed: Object.assign({}, symbolMarketClosed),
        symbolTradingPeriodEndMs: Object.assign({}, symbolTradingPeriodEndMs),
        componentDayPercents: Object.assign({}, componentDayPercents),
        navValues: Object.assign({}, navValues),
        currentDisplayCurrency,
        showStockDisplayCurrency,
        cashEntries,
        ibkrRatesData: lastIbkrRatesData,
        rebalTargetUsd,
        marginTargetPct,
        allocAddMode,
        allocReduceMode,
        nowMs: Date.now()
    };
}

// ── Section 4: DOM applier ────────────────────────────────────────────────────

function _applyDisplayResult(result) {
    const { perStock, totals } = result;

    // Write back globals so other code (groups, rebal inputs) sees fresh values
    lastStockGrossVal = totals.stockGrossValRaw;
    lastPrevPortfolioVal = totals.prevStockGrossValRaw;
    lastPortfolioDayChangeUsd = totals.dayChangeDollarsRaw;
    stockGrossValueKnown = totals.stockGrossValueKnown;
    lastCashTotalUsd = totals.cashTotalRaw;
    lastMarginUsd = totals.marginUsdRaw;
    cashTotalKnown = totals.cashTotalKnown;
    marginKnown = totals.cashTotalKnown;

    // Per-stock cells
    for (const [symbol, d] of Object.entries(perStock)) {
        // Mark price
        const markCell = document.getElementById('mark-' + symbol);
        if (markCell) {
            const markValSpan = markCell.querySelector('.mark-price-value');
            if (markValSpan) {
                markValSpan.textContent = d.markText;
                markValSpan.classList.toggle('loaded', d.markLoaded);
            }
            markCell.classList.toggle('loaded', d.markLoaded);
            markCell.classList.toggle('after-hours', d.markAfterHours);
        }

        // Day percent (inside mark cell)
        if (d.dayPctText) {
            const pctSpan = document.getElementById('day-percent-' + symbol);
            if (pctSpan) { pctSpan.textContent = d.dayPctText; pctSpan.className = d.dayPctClass; }
        }

        // Close price
        const closeCell = document.getElementById('close-' + symbol);
        if (closeCell) {
            closeCell.textContent = d.closeText;
            closeCell.classList.toggle('loaded', d.closeLoaded);
            closeCell.classList.toggle('after-hours', d.markAfterHours);
        }

        // Day change
        const changeCell = document.getElementById('day-change-' + symbol);
        if (changeCell && d.dayChangeText) {
            changeCell.textContent = d.dayChangeText;
            changeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
            changeCell.classList.add(...d.dayChangeClass.split(' ').filter(Boolean));
        }

        // Position change
        const posChangeCell = document.getElementById('position-change-' + symbol);
        if (posChangeCell) {
            posChangeCell.textContent = d.positionChangeText;
            if (d.positionChangeClass) {
                posChangeCell.classList.remove('positive', 'negative', 'neutral', 'after-hours');
                posChangeCell.classList.add(...d.positionChangeClass.split(' ').filter(Boolean));
            }
        }

        // Mkt Val (value)
        const valueCell = document.getElementById('value-' + symbol);
        if (valueCell) {
            valueCell.textContent = d.valueText;
            valueCell.classList.toggle('loaded', d.valueLoaded);
        }

        // Row flash — only when mark price itself changed
        if (d.markPriceChanged) {
            const row = valueCell ? valueCell.closest('tr') : null;
            if (row && !row.classList.contains('recently-updated')) {
                row.classList.add('recently-updated');
                setTimeout(() => row.classList.remove('recently-updated'), 10000);
            }
        }

        // Weight
        const weightCell = document.getElementById('current-weight-' + symbol);
        if (weightCell) {
            weightCell.innerHTML = d.weightHTML;
            weightCell.classList.toggle('loaded', d.weightLoaded);
        }

        // Rebal
        const rebalDollarsCell = document.getElementById('rebal-dollars-' + symbol);
        if (rebalDollarsCell) {
            rebalDollarsCell.textContent = d.rebalDollarsText;
            rebalDollarsCell.className = d.rebalDollarsClass;
        }
        const rebalQtyCell = document.getElementById('rebal-qty-' + symbol);
        if (rebalQtyCell) {
            rebalQtyCell.textContent = d.rebalQtyText;
            rebalQtyCell.className = d.rebalQtyClass;
        }

        // Alloc
        const allocDollarsCell = document.getElementById('alloc-dollars-' + symbol);
        if (allocDollarsCell) {
            allocDollarsCell.textContent = d.allocDollarsText;
            allocDollarsCell.className = d.allocDollarsClass;
        }
        const allocQtyCell = document.getElementById('alloc-qty-' + symbol);
        if (allocQtyCell) {
            allocQtyCell.textContent = d.allocQtyText;
            allocQtyCell.className = d.allocQtyClass;
        }

        // Est val (LETF)
        const estValCell = document.getElementById('est-val-' + symbol);
        if (estValCell && d.estValText !== null) {
            estValCell.textContent = d.estValText;
            estValCell.classList.toggle('loaded', d.estValLoaded);
            estValCell.classList.toggle('after-hours', d.markAfterHours);
            if (d.estValRaw !== null) estValCell.dataset.estVal = d.estValRaw;
        }
    }

    // Totals
    const totalCell = document.getElementById('stock-gross-total');
    if (totalCell) totalCell.textContent = totals.stockGrossTotal;

    const portfolioChangeCell = document.getElementById('portfolio-day-change');
    if (portfolioChangeCell) portfolioChangeCell.innerHTML = totals.portfolioDayChangeHTML;

    const totalChangeCell = document.getElementById('total-day-change');
    if (totalChangeCell) totalChangeCell.innerHTML = totals.totalDayChangeHTML;

    const grandEl = document.getElementById('portfolio-total');
    if (grandEl) grandEl.textContent = totals.grandTotal;

    // Margin
    const marginRow = document.querySelector('[data-margin-row]');
    if (marginRow) marginRow.style.display = totals.showMarginRow ? '' : 'none';
    const marginEl = document.getElementById('margin-total-usd');
    if (marginEl) marginEl.textContent = totals.marginDisplay;
    const marginPctEl = document.getElementById('margin-percent');
    if (marginPctEl) {
        marginPctEl.textContent = totals.marginPctText;
        marginPctEl.style.display = totals.marginPctText ? '' : 'none';
    }

    // Margin target
    const marginTargetEl = document.getElementById('margin-target-usd');
    if (marginTargetEl) marginTargetEl.textContent = totals.marginTargetText;

    // Margin input placeholder (only when user hasn't typed a value)
    const marginInput = document.getElementById('margin-target-input');
    if (marginInput && marginInput.value.trim() === '') {
        marginInput.placeholder = totals.marginInputPlaceholder;
    }

    // Rebal input placeholder (not .value)
    const rebalInput = document.getElementById('rebal-target-input');
    if (rebalInput) rebalInput.placeholder = totals.rebalTargetPlaceholder;

    // Per-entry cash USD cells
    for (const [entryId, text] of Object.entries(totals.perEntryCash)) {
        const el = document.getElementById('cash-usd-' + entryId);
        if (el) el.textContent = text;
    }

    // Cash total
    const cashTotalEl = document.getElementById('cash-total-usd');
    if (cashTotalEl) cashTotalEl.textContent = totals.cashTotalText;

    // IBKR interest
    if (totals.ibkrResult) _applyIbkrInterest(totals.ibkrResult);

    // Group table (runs on main thread, reads updated globals)
    if (groupViewActive && typeof updateGroupTable === 'function') {
        updateGroupTable();
    }
}

function _applyIbkrInterest(r) {
    for (const [ccy, text] of Object.entries(r.perCurrency)) {
        const row = [...document.querySelectorAll('.ibkr-rates-table tbody tr')].find(
            tr => tr.querySelector('.ibkr-rate-currency')?.textContent?.trim() === ccy
        );
        if (row) {
            const cell = row.querySelector('.ibkr-rate-value');
            if (cell) cell.textContent = text;
        }
    }
    const savingLabelEl = document.getElementById('ibkr-saving-label');
    if (savingLabelEl) savingLabelEl.textContent = r.label;
    const currentEl = document.getElementById('ibkr-current-interest');
    if (currentEl) currentEl.textContent = r.currentUsd > 0 ? formatDisplayCurrency(r.currentUsd) : '\u2014';
    const cheapestEl = document.getElementById('ibkr-cheapest-interest');
    if (cheapestEl) cheapestEl.textContent = r.cheapestUsd !== null ? formatDisplayCurrency(r.cheapestUsd) : '\u2014';
    const cheapestCcyEl = document.getElementById('ibkr-cheapest-ccy');
    if (cheapestCcyEl) cheapestCcyEl.textContent = r.cheapestCcy ? '(' + r.cheapestCcy + ')' : '';
    const diffEl = document.getElementById('ibkr-interest-diff');
    if (diffEl) {
        diffEl.textContent = (r.diff !== null && r.diff >= 0.005) ? formatDisplayCurrency(r.diff) : '\u2014';
        diffEl.className = (r.diff !== null && r.diff >= 0.005) ? 'ibkr-rate-diff' : '';
    }
}
