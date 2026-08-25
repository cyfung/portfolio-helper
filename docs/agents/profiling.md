# Frontend Performance Profiling

How to reproduce the React Profiler instrumentation used to diagnose chart
performance (e.g. the Rebalance Strategy page's multi-second render cost on
large datasets). This is a dev-only workflow — the instrumentation is inert
in normal use and only activates when explicitly enabled.

## Why an isolated instance

The app locks itself to a single browser session (see the cookie-gate
intercept in `routes.kt`) and normally runs as your one always-on instance
with your real portfolio data. Profiling needs to bypass that gate, so
**never point it at your live instance** — run a second, isolated copy on
different ports instead. It still reads your real local DB (so saved
portfolios/settings are available for realistic datasets), it just serves
HTTP on ports your live instance isn't using.

## 1. Start an isolated backend with profiling enabled

```bash
PROFILING_MODE=1 PORTFOLIO_HELPER_PORT=9093 PORTFOLIO_HELPER_HTTP_PORT=9090 ./gradlew.bat run
```

(On Linux/macOS use `./gradlew run` and `export` the vars, or prefix as above
under a POSIX shell.) Pick any free ports — 9093/9090 are just what was used
during the original investigation. `PROFILING_MODE=1`:

- Skips the session-cookie gate for every request (`routes.kt`, the
  intercept right before the cookie check) — so a fresh browser tab isn't
  redirected to `/admin`.
- Makes `GET /api/admin/profiling-mode` report `{"enabled":true}`, which is
  what the frontend polls to decide whether to activate its own
  instrumentation (see `frontend/src/lib/profiling.tsx`).

Wait for `Application ready. Access at https://<port>` in the log before
continuing.

## 2. Point the frontend at it

Two options:

- **Fastest iteration (recommended):** temporarily edit the three proxy
  targets in `frontend/vite.config.ts` from `https://localhost:8443` to your
  isolated instance's port (e.g. `9093`), then run
  `NODE_TLS_REJECT_UNAUTHORIZED=0 npm run dev -- --port 5173` from
  `frontend/`. The `NODE_TLS_REJECT_UNAUTHORIZED=0` is needed because the
  backend's TLS cert is self-signed. **Revert `vite.config.ts` back to 8443
  when done** — it's meant to point at your normal dev backend, not the
  profiling instance.
- **Closer to production:** `npm run build` from `frontend/`, then copy
  `build/generated/frontend/static/**` over `build/resources/main/static/**`
  so the already-running isolated JVM picks it up without a restart (Ktor
  serves those files straight off disk).

Note: a browser sandboxed with strict TLS validation (e.g. this repo's
Claude Code browser tool) will refuse to navigate directly to the
self-signed `https://localhost:9093` — go through the plain-HTTP Vite dev
server instead, which proxies to the backend with `secure: false`.

## 3. What the instrumentation reports

With `PROFILING_MODE=1` live, `frontend/src/lib/profiling.tsx` (component) and
`frontend/src/lib/profilingState.ts` (the fetch/flag/subscription logic behind
it, split out so `profiling.tsx` stays a pure-component file for Fast Refresh)
together expose:

- `ProfileBoundary` — wraps a subtree in React's built-in `<Profiler>`,
  logging `[profile] <id> (<phase>) actual=<ms> base=<ms>` to the console on
  every commit. `phase` is `mount`, `update`, or `nested-update` (a commit
  triggered from inside another commit's effects — the signature to watch
  for repeated, expensive `nested-update`s, which is what Recharts'
  `ResponsiveContainer`/`Brush` produce while settling on a large dataset).
  `actual` is real time spent this commit; `base` is the last-measured
  unmemoized cost of that subtree (stays nonzero even when a `memo()`
  boundary correctly bails and `actual` drops to ~0).
- A `[profile] useRebalanceChartData compute=<ms> curves=<n> points=<n>` log
  from `frontend/src/lib/rebalanceStrategyResults.ts`, timing the actual JS
  data-build (separate from React's render/commit cost).

Currently wired into `RebalanceStrategyResults.tsx` around the stats/
diagnostics tables and each chart (main/risk/margin/VM-timing). To
instrument another component, wrap it in `<ProfileBoundary id="...">` — it's
a transparent passthrough (no `<Profiler>`, no fetch cost beyond the one
startup check) whenever `PROFILING_MODE` isn't set.

### Reading the log reliably

`console.clear()` does **not** reliably clear a persistent capture buffer in
some tooling (e.g. this repo's Claude Code browser tool) — old entries can
resurface on a later read and look like they came from a just-performed
action. Before attributing a log line to a specific interaction, emit a
unique marker first:

```js
console.log('===MARKER===')
// ...then perform the interaction...
```

and only trust lines that appear *after* the marker in the read-back.

## 4. Example findings (2026-08-25 investigation)

On a real saved config (5 curves × 6,846 daily points):

| Scenario | Before fix | After fix |
|---|---|---|
| First chart paint | ~12.3s blocked main thread | ~1.5s |
| Toggling a curve (data-affecting edit) | ~1.76s | ~512ms |
| Editing an unrelated field (e.g. Starting Balance) | up to several hundred ms (unstable callback busting `memo()`) | ~0–2ms (noise) |
| Scrolling | 0 React re-renders (confirmed both before and after) | unchanged (React work was never the cost here) |

Scrolling was never a React re-render problem — it produced zero `[profile]`
log lines both before and after, confirmed with the marker technique above.
Whether it also *feels* smoother after downsampling is not verified: that
would be a browser paint/compositing cost (smaller SVG DOM = less to paint),
which is real in principle but wasn't and couldn't be measured here — the
sandboxed browser tool used for this investigation doesn't composite frames,
so `requestAnimationFrame`-based frame-timing checks return zero samples.
Treat any scroll-smoothness improvement as a plausible but unverified
side effect, not a measured fix.

Fixes: `downsampleLabels()` in `frontend/src/lib/chartData.ts` (caps the
shared date-label axis at 1,500 points — a no-op below that, since the array
is returned unchanged) and wrapping `setInflationAdjusted` in `useCallback`
(`frontend/src/hooks/useInflationAdjustedPreference.ts`) so it stops
defeating `RebalanceStrategyResults`'s `memo()` boundary on every unrelated
ambient re-render.

**Side effect of downsampling — action markers snap to the nearest kept
date.** `visibleActionPointGroups()` in `rebalanceStrategyResults.ts` used to
require an exact date match between an action point and a chart row; once
`labels` can be a decimated subset, that would silently drop almost every
marker. It now snaps each action point to the nearest surviving label
(`nearestLabelIndex`) instead, so markers stay visible but can shift by up to
half the local label spacing, and multiple nearby action points can
legitimately collapse onto the same visual dot (deduped per type). This is a
deliberate approximation for the decimated view, not a bug — full-resolution
per-day accuracy is only meaningful at unresampled zoom levels this page
doesn't currently offer.

## 4a. Rollout to BacktestPage.tsx (2026-08-25)

The same `downsampleLabels()` fix was applied to `BacktestPage.tsx`'s chart
data, and its previously-duplicated `visibleActionPointGroups`/
`nearestLabelIndex` were consolidated with the Rebalance versions into a
shared `frontend/src/lib/actionPointMarkers.ts` (both pages now import from
there instead of keeping separate copies).

**Note on a small behavior change from that consolidation.** BacktestPage's
old local copy dropped an action point outright if its date wasn't in
`labels` (exact match only); the shared version snaps to the nearest label
instead (see the note above on Rebalance's action markers). In practice this
only matters at all for BacktestPage when a curve's action point falls on a
date outside the *intersection* of trading dates across every curve/portfolio
(`buildCommonLabels` computes that intersection) — an edge case that existed
independent of downsampling and previously silently dropped the marker. It
now snaps to the nearest in-range date instead of disappearing, which is a
strict improvement, not a fabrication risk (unlike the real-portfolio-overlay
case, the snap target is still one of that curve's own nearby dates). Called
out here because it's a real (if minor) semantic change riding along with
what's otherwise a pure de-duplication, not because it needed reverting.

**MonteCarloPage was deliberately left unchanged.** Its chart's row count is
`simulatedYears + 1` (user input, typically ≤30) — nothing like the
thousands-of-daily-points case downsampling targets — so there was no
downsampling to add. Its `dot={{ r: 3 }}` (vs. `dot={false}` elsewhere) was
flagged early in the original investigation as a possible cheap win, but was
never profiled or confirmed as a real cost, and at ≤30 points per line the
dots plausibly aid readability rather than hurt performance. Left as-is
rather than changed on an unverified guess — profile it first if it's
suspected to matter.

**BacktestPage's real-portfolio overlay (IBKR account comparison) needed a
narrower fix than Rebalance's action markers.** That feature matches real
account data to chart rows by *exact* date across a date range that may only
*partially* overlap the backtest's range (e.g. a 30-year backtest vs. an
account with 2 years of history) — nearest-date snapping there, unlike for
action-point markers, risks fabricating overlay values by pulling in a real
data point from a real date that isn't actually anywhere near the backtest
row it got attached to. Rather than build and hand-verify a
distance-bounded snapping scheme with no live IBKR-synced portfolio to test
it against, downsampling is skipped entirely whenever a real-portfolio
overlay is active (`realData` truthy in `BacktestPage.tsx`'s `chartData`
memo) — such sessions keep the full-resolution label axis and the original
exact-match behavior, unchanged. Only backtests with no real overlay
selected get the point-count reduction. Revisit if overlay + very long
date ranges turns out to need it too.

## 5. Cleanup

Stop the isolated backend and Vite dev server when done, and confirm the
ports are free:

```bash
# find PIDs
netstat -ano | findstr "9093 9090 5173"   # Windows
lsof -i :9093 -i :9090 -i :5173           # Linux/macOS
# then stop them (Windows PowerShell example)
Stop-Process -Id <pid> -Force
```

Double-check `git diff frontend/vite.config.ts` is empty before committing —
it should never carry the profiling proxy target.
