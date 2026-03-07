// ── stats-formatters.js — Shared stat formatters for backtest & monte carlo ───

function pct(v)   { return (v * 100).toFixed(2) + '%'; }
function fmt2(v)  { return v.toFixed(2); }
function money(v) { return '$' + v.toFixed(0); }
