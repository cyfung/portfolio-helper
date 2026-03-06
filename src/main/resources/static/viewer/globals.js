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
var rawMarkPrices = {};         // symbol → raw mark price
var rawClosePrices = {};        // symbol → raw close price
var globalIsMarketClosed = true;
var marketCloseTimeMs = null;   // Unix ms of tradingPeriodEnd

// Display state
var currentDisplayCurrency = 'USD';

// Portfolio totals (updated live)
var lastPortfolioVal = 0;
var lastPrevPortfolioVal = 0;
var lastPortfolioDayChangeUsd = 0;
var lastCashTotalUsd = 0;
var lastMarginUsd = 0;

// Data quality flags
var portfolioValueKnown = true;
var cashTotalKnown = true;
var marginKnown = false;

// Rebalancing targets
var rebalTargetUsd = null;
var marginTargetPct = null;

// Allocation modes (server-rendered values take priority, then localStorage)
var allocAddMode = (typeof savedAllocAddMode !== 'undefined' ? savedAllocAddMode : null)
    || localStorage.getItem('ib-viewer-alloc-add-mode') || 'PROPORTIONAL';
var allocReduceMode = (typeof savedAllocReduceMode !== 'undefined' ? savedAllocReduceMode : null)
    || localStorage.getItem('ib-viewer-alloc-reduce-mode') || 'PROPORTIONAL';