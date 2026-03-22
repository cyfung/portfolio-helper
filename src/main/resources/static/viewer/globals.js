// ── globals.js — Mutable global state shared across modules ──────────────────
// This should be inlined in your HTML <script> block (or loaded first),
// with server-rendered values like portfolioId, fxRates, savedRebalTargetUsd,
// savedMarginTargetPct, and savedAllocAddMode injected by the server.
//
// Example server-rendered inline script preceding these modules:
//   var portfolioId = "my-portfolio";
//   var fxRates = { AUD: 0.64, ... };
//   var savedRebalTargetUsd = 0;
//   var savedMarginTargetPct = 0;
//   var savedAllocAddMode = "PROPORTIONAL";
//   var savedAllocReduceMode = "PROPORTIONAL";
//
// NOTE: var is used throughout (instead of const/let) so that all variables
// are attached to window and visible across multiple script tags.

// Price/market state
var componentDayPercents = {};  // symbol → intraday % (for LETF est val)
var navValues = {};             // symbol → last NAV value (for LETF est val)
var rawMarkPrices = {};         // symbol → raw mark price
var rawClosePrices = {};        // symbol → raw close price
var symbolMarketClosed = {};        // symbol → boolean (isMarketClosed per ticker)
var symbolTradingPeriodEndMs = {};  // symbol → Unix ms of tradingPeriodEnd
var symbolLocalDate = {};           // symbol → "YYYY-MM-DD" (trading day local date)

// Per-stock currency (server-rendered + updated via SSE)
var stockCurrencies = {};          // symbol → currency code (e.g. 'USD', 'HKD')

// Display state
var currentDisplayCurrency = 'USD';
var showStockDisplayCurrency = (typeof savedShowStockDisplayCurrency !== 'undefined') ? savedShowStockDisplayCurrency : false;
var afterHoursGray = (typeof savedAfterHoursGray !== 'undefined') ? savedAfterHoursGray : true;

// Portfolio totals (updated live)
var lastStockGrossVal = 0;
var lastPrevPortfolioVal = 0;
var lastPortfolioDayChangeUsd = 0;
var lastCashTotalUsd = 0;
var lastMarginUsd = 0;

// Data quality flags
var stockGrossValueKnown = true;
var cashTotalKnown = true;
var marginKnown = false;

// Rebalancing targets
var rebalTargetUsd = null;
var marginTargetPct = null;

// Allocation modes (server-rendered values take priority, then localStorage)
var allocAddMode = (typeof savedAllocAddMode !== 'undefined' ? savedAllocAddMode : null)
    || localStorage.getItem('portfolio-helper-alloc-add-mode')
    || localStorage.getItem('ib-viewer-alloc-add-mode')
    || 'PROPORTIONAL';
var allocReduceMode = (typeof savedAllocReduceMode !== 'undefined' ? savedAllocReduceMode : null)
    || localStorage.getItem('portfolio-helper-alloc-reduce-mode')
    || localStorage.getItem('ib-viewer-alloc-reduce-mode')
    || 'PROPORTIONAL';

// Group view state
var groupViewActive = false;

// IBKR rates data (set on ibkr-rates SSE, read by display-worker)
var lastIbkrRatesData = null;

// Server-computed per-stock display data (set by applyStockDisplay, read by display-worker)
// symbol → { markPrice, closePrice, positionValueUsd, currency }
var lastServerStocks = {};

// Last received SSE snapshots (cached for display currency re-apply)
var lastStockDisplayData = null;
var lastCashDisplayData = null;
var lastPortfolioTotalsData = null;
