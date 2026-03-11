// ── rebalance-ga.js — Stepwise GA allocation ────────────────────────────────
// Depends on: globals.js, utils.js
//
// Entry point: computeGAAllocations(delta, stocksForAlloc, totalStockValue, callback)
//   delta           : signed cash (positive = buy, negative = sell)
//   stocksForAlloc  : [{ symbol, markPrice, targetWeight, currentValue }]
//                     targetWeight in percent (0–100)
//   totalStockValue : sum of currentValue across all stocks
//   callback        : function({ [symbol]: dollarAlloc }) — called when done
//
// Runs GA in a Web Worker so the main thread stays responsive.
// If a run is already in progress, the new call is queued (one pending slot).
// When the worker finishes it picks up the pending call automatically.

// ── Worker source (no DOM access) ───────────────────────────────────────────

const _GA_WORKER_SRC = `
function _normalizeAllocs(raw, caps, cash) {
    const n = raw.length;
    const clamped = raw.map((v, i) => Math.max(0, Math.min(v, caps[i])));
    const sum = clamped.reduce((a, v) => a + v, 0);
    let result = sum < 0.01
        ? clamped.map((_, i) => Math.min(cash / n, caps[i]))
        : clamped.map((v, i) => Math.min(v * cash / sum, caps[i]));
    let rem = cash - result.reduce((a, v) => a + v, 0);
    for (let iter = 0; iter < 10 && rem > 0.01; iter++) {
        const free = result.map((v, i) => v < caps[i] - 0.01 ? i : -1).filter(i => i >= 0);
        if (!free.length) break;
        const add = rem / free.length;
        free.forEach(i => { const d = Math.min(add, caps[i] - result[i]); result[i] += d; rem -= d; });
    }
    return result;
}

function _solveGATier(tierGroups, stocks, currentVals, eligibleIdxs, cash, newTotal, isLast, prevElites, tierSize, isSell) {
    const POP = 80, GENS = 2000, ELITE = 8, MUTRATE = 0.3, MUTSCALE = 0.25;
    const m = eligibleIdxs.length;
    if (m === 0 || cash < 0.01) {
        const r = Object.fromEntries(eligibleIdxs.map(i => [i, 0]));
        r._elites = []; r._gensRan = 0; r._bestFitness = 0;
        return r;
    }
    const tierCaps = eligibleIdxs.map(() => cash);

    function projDev(g, allocs) {
        const mv = g.members.reduce((a, t) => {
            const si = stocks.findIndex(s => s.symbol === t);
            const li = eligibleIdxs.indexOf(si);
            const contribution = li >= 0 ? allocs[li] : 0;
            return a + (currentVals[t] + (isSell ? -contribution : contribution)) * (g.weights?.[t] ?? 1);
        }, 0);
        return mv / newTotal - g.targetWeight;
    }

    const HUBER_DELTA = 0.005;
    const huber = x => { const ax = Math.abs(x); return ax <= HUBER_DELTA ? x * x : 2 * HUBER_DELTA * ax - HUBER_DELTA * HUBER_DELTA; };
    const median = arr => { const s = [...arr].sort((a, b) => a - b), mid = Math.floor(s.length / 2); return s.length % 2 === 0 ? (s[mid - 1] + s[mid]) / 2 : s[mid]; };

    function fitness(allocs) {
        const groupDevs = tierGroups.map(g => projDev(g, allocs));
        const groupVariance = groupDevs.reduce((a, v) => a + huber(v - median(groupDevs)), 0);
        let stockVariance = 0;
        for (const g of tierGroups) {
            const memberDevs = g.members.map(t => {
                const si = stocks.findIndex(s => s.symbol === t);
                const li = eligibleIdxs.indexOf(si);
                const contribution = li >= 0 ? allocs[li] : 0;
                const mult = g.weights?.[t] ?? 1;
                return (currentVals[t] + (isSell ? -contribution : contribution)) * mult / newTotal - stocks[si].targetWeight * mult;
            });
            if (memberDevs.length < 2) continue;
            stockVariance += memberDevs.reduce((a, v) => a + huber(v - median(memberDevs)), 0);
        }
        return groupVariance + stockVariance * 0.5;
    }

    const norm   = raw => _normalizeAllocs(raw, tierCaps, cash);
    const seed   = ()  => norm(tierCaps.map(() => cash / m));
    const rnd    = ()  => norm(tierCaps.map(() => Math.random()));
    const mutate = ind => norm(ind.map(v => Math.random() < MUTRATE ? (v / cash + (Math.random() * 2 - 1) * MUTSCALE) * cash : v));
    const cross  = (a, b) => norm(a.map((v, i) => Math.random() < 0.5 ? v : b[i]));
    const avg    = (a, b) => norm(a.map((v, i) => (v + b[i]) / 2));

    function runGA(extraGens) {
        const eliteW = Math.max(tierSize - 1, 0) / tierSize;
        const rndW   = 1 / tierSize;
        const warmSeeds = prevElites.flatMap(elite => {
            const base = eligibleIdxs.map((_, li) => elite[li] !== undefined ? elite[li] : cash / m);
            return [0, 1].map(() => {
                const rndGene = eligibleIdxs.map(() => Math.random());
                return norm(base.map((v, i) => (eliteW * v / cash + rndW * rndGene[i]) * cash));
            });
        });
        let pop = [seed(), ...warmSeeds, ...Array.from({ length: Math.max(0, POP - 1 - warmSeeds.length) }, rnd)].slice(0, POP);
        let scores = pop.map(fitness);
        let gensRan = 0;
        for (let gen = 0; gen < GENS; gen++) {
            const ranked = scores.map((s, i) => [s, i]).sort((a, b) => a[0] - b[0]);
            if (ranked[0][0] < 1e-6) break;
            const elites = ranked.slice(0, ELITE).map(([, i]) => pop[i]);
            const np = [...elites];
            while (np.length < POP) {
                const pa = elites[Math.floor(Math.random() * ELITE)];
                const pb = elites[Math.floor(Math.random() * ELITE)];
                const r = Math.random();
                np.push(r < 0.33 ? mutate(pa) : r < 0.66 ? cross(pa, pb) : avg(pa, pb));
            }
            pop = np; scores = pop.map(fitness); gensRan++;
        }
        for (let gen = 0; gen < extraGens; gen++) {
            const ranked = scores.map((s, i) => [s, i]).sort((a, b) => a[0] - b[0]);
            if (ranked[0][0] < 1e-10) break;
            const elites = ranked.slice(0, ELITE).map(([, i]) => pop[i]);
            const np = [...elites];
            while (np.length < POP) {
                const pa = elites[Math.floor(Math.random() * ELITE)];
                const pb = elites[Math.floor(Math.random() * ELITE)];
                const r = Math.random();
                np.push(r < 0.33 ? mutate(pa) : r < 0.66 ? cross(pa, pb) : avg(pa, pb));
            }
            pop = np; scores = pop.map(fitness); gensRan++;
        }
        const ranked = scores.map((s, i) => [s, i]).sort((a, b) => a[0] - b[0]);
        return { best: pop[ranked[0][1]], elites: ranked.slice(0, ELITE).map(([, i]) => pop[i]), gensRan, bestFitness: ranked[0][0] };
    }

    const { best, elites, gensRan, bestFitness } = runGA(isLast ? 1000 : 0);
    const result = Object.fromEntries(eligibleIdxs.map((si, li) => [si, best[li]]));
    result._elites = elites;
    result._gensRan = gensRan;
    result._bestFitness = bestFitness;
    return result;
}

function _runStepwiseGA(stocks, groups, currentVals, cash, newTotal, isSell) {
    const totalPortfolio = Object.values(currentVals).reduce((a, v) => a + v, 0);

    const gDev = (g, vals, denom) =>
        g.members.reduce((a, t) => a + (vals[t] || 0) * (g.weights?.[t] ?? 1), 0) / (denom || newTotal) - g.targetWeight;

    const applyAllocs = (base, idxs, allocs) => {
        const out = { ...base };
        idxs.forEach(si => { out[stocks[si].symbol] = base[stocks[si].symbol] + (isSell ? -(allocs[si] || 0) : (allocs[si] || 0)); });
        return out;
    };

    const stockIdxsFor = gs => {
        const tickers = new Set(gs.flatMap(g => g.members));
        return [...tickers].map(t => stocks.findIndex(s => s.symbol === t)).filter(i => i >= 0);
    };

    const sorted = [...groups].sort((a, b) => {
        const dev = g => gDev(g, currentVals, totalPortfolio);
        return isSell ? dev(b) - dev(a) : dev(a) - dev(b);
    });

    let finalAllocs = null, finalIdxs = [], finalTargetGroups = [], prevElites = [];

    for (let size = 1; size <= sorted.length; size++) {
        const targetGroups = sorted.slice(0, size);
        const idxs = stockIdxsFor(targetGroups);
        const allocs = _solveGATier(targetGroups, stocks, currentVals, idxs, cash, newTotal, false, prevElites, size, isSell);
        prevElites = allocs._elites || [];

        const projVals = applyAllocs(currentVals, idxs, allocs);
        const devDenom = isSell ? totalPortfolio : newTotal;
        const targetDevs = targetGroups.map(g => gDev(g, projVals, devDenom));
        const sd = [...targetDevs].sort((a, b) => a - b);
        const mid = Math.floor(sd.length / 2);
        const targetMedian = sd.length % 2 === 0 ? (sd[mid - 1] + sd[mid]) / 2 : sd[mid];

        finalAllocs = allocs; finalIdxs = idxs; finalTargetGroups = targetGroups;

        const nonTarget = sorted.slice(size);
        if (nonTarget.length === 0) break;

        const lowestNonTargetDev = gDev(nonTarget[0], isSell ? currentVals : projVals, devDenom);
        if (isSell ? targetMedian >= lowestNonTargetDev : targetMedian <= lowestNonTargetDev) break;
    }

    const finalAllocsRefined = _solveGATier(finalTargetGroups, stocks, currentVals, finalIdxs, cash, newTotal, true, prevElites, finalTargetGroups.length, isSell);
    finalIdxs.forEach(si => { finalAllocs[si] = finalAllocsRefined[si] || 0; });

    const result = new Array(stocks.length).fill(0);
    finalIdxs.forEach(si => { result[si] = finalAllocs[si] || 0; });
    return result;
}

// Worker message handler
self.onmessage = function(e) {
    const { delta, stocks, groups, currentVals, totalStockValue } = e.data;
    const isSell = delta < 0;
    const cash = Math.abs(delta);
    const newTotal = totalStockValue + (isSell ? -cash : cash);

    const exactAllocs = stocks.map(s => {
        const ideal = newTotal * s.targetWeight - s.currentVal;
        return isSell ? Math.max(0, -ideal) : Math.max(0, ideal);
    });
    const totalNeeded = exactAllocs.reduce((a, v) => a + v, 0);

    let allocArr;
    if (cash >= totalNeeded) {
        const remaining = cash - totalNeeded;
        allocArr = exactAllocs.map((d, i) => d + remaining * stocks[i].targetWeight);
    } else {
        allocArr = _runStepwiseGA(stocks, groups, currentVals, cash, newTotal, isSell);
    }

    const result = Object.fromEntries(stocks.map((s, i) => [s.symbol, (isSell ? -1 : 1) * (allocArr[i] || 0)]));
    self.postMessage(result);
};
`;

// ── Group definitions (DOM, main thread only) ────────────────────────────────

function buildGAGroups(stocksForAlloc) {
    const groupMap = new Map();

    document.querySelectorAll('#stock-view-table tbody tr').forEach(row => {
        if (row.dataset.deleted) return;
        const symbol = row.dataset.symbol;
        if (!symbol || !row.dataset.groups) return;
        const targetWeight = parseFloat(row.dataset.weight) || 0;

        (row.dataset.groups || '').trim().split(';').forEach(e => {
            const t = e.trim(), sp = t.indexOf(' ');
            if (sp < 0) return;
            const mult = parseFloat(t.substring(0, sp));
            const name = t.substring(sp + 1).trim();
            if (isNaN(mult) || !name) return;
            if (!groupMap.has(name)) groupMap.set(name, new Map());
            const g = groupMap.get(name);
            g.set(symbol, (g.get(symbol) || 0) + targetWeight * mult);
        });
    });

    return Array.from(groupMap.entries()).map(([name, memberMap]) => {
        const weights = Object.fromEntries(
            Array.from(memberMap.entries()).map(([sym, wtw]) => {
                const s = stocksForAlloc.find(x => x.symbol === sym);
                const tw = s ? (s.targetWeight || 0) : 0;
                return [sym, tw > 0 ? wtw / tw : 1];
            })
        );
        return {
            name,
            members: Array.from(memberMap.keys()),
            weights,
            targetWeight: Array.from(memberMap.values()).reduce((a, v) => a + v, 0) / 100,
        };
    });
}

// ── Worker pool (single worker, one pending slot) ────────────────────────────

let _gaWorker = null;
let _gaRunning = false;
let _gaPending = null; // { delta, stocksForAlloc, totalStockValue, callback }

function _getWorker() {
    if (_gaWorker) return _gaWorker;
    const blob = new Blob([_GA_WORKER_SRC], { type: 'application/javascript' });
    _gaWorker = new Worker(URL.createObjectURL(blob));
    return _gaWorker;
}

function _dispatch(payload, callback) {
    _gaRunning = true;
    const worker = _getWorker();
    worker.onmessage = function(e) {
        _gaRunning = false;
        callback(e.data);
        if (_gaPending) {
            const { payload: p, callback: cb } = _gaPending;
            _gaPending = null;
            _dispatch(p, cb);
        }
    };
    worker.onerror = function(err) {
        console.error('[GA worker]', err);
        _gaRunning = false;
        callback({});
        if (_gaPending) {
            const { payload: p, callback: cb } = _gaPending;
            _gaPending = null;
            _dispatch(p, cb);
        }
    };
    worker.postMessage(payload);
}

// ── Public entry point ───────────────────────────────────────────────────────

function computeGAAllocations(delta, stocksForAlloc, totalStockValue, callback) {
    if (!delta || Math.abs(delta) < 0.01) { callback({}); return; }

    const twSum = stocksForAlloc.reduce((a, s) => a + (s.targetWeight || 0), 0);
    if (twSum <= 0) { callback({}); return; }

    const stocks = stocksForAlloc.map(s => ({
        symbol: s.symbol,
        targetWeight: (s.targetWeight || 0) / twSum,
        currentVal: s.currentValue || 0,
    }));
    const currentVals = Object.fromEntries(stocks.map(s => [s.symbol, s.currentVal]));
    const groups = buildGAGroups(stocksForAlloc);
    const payload = { delta, stocks, groups, currentVals, totalStockValue };

    if (_gaRunning) {
        _gaPending = { payload, callback };
        return;
    }
    _dispatch(payload, callback);
}
