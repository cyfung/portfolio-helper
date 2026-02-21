# Portfolio Helper Project - Claude Code Instructions

## Project Overview

**Portfolio Helper** is a stock portfolio viewer web application that displays portfolio data from CSV files.

- **Description**: Web application for visualizing stock portfolios with real-time value calculations, NAV tracking, LETF estimated values, cash/margin balances, FX conversion, and portfolio rebalancing analysis
- **Tech Stack**: Kotlin, Ktor 2.3.7, kotlinx.html, Apache Commons CSV, Gradle
- **Architecture**: Clean architecture with three distinct layers:
  - **Model Layer**: Domain data classes
  - **Service Layer**: Business logic, CSV/cash parsing, market data, NAV fetching
  - **Web Layer**: Ktor routing and HTML rendering with Server-Sent Events (SSE) for live updates

## Code Style & Conventions

### Kotlin Patterns

- **Data classes for models**: Use data classes with computed properties for domain entities
  ```kotlin
  data class Stock(val symbol: String, val shares: Int, val price: Double) {
      val value: Double get() = shares * price
  }
  ```

- **Object singletons for stateless utilities**: Use `object` for stateless services
  ```kotlin
  object CsvStockReader {
      fun readPortfolio(csvPath: String): Portfolio { ... }
  }
  ```

- **Extension functions for configuration**: Use extension functions for Ktor configuration
  ```kotlin
  fun Application.configureRouting() { ... }
  ```

- **Functional programming**: Prefer functional patterns over imperative code
  ```kotlin
  stocks.sumOf { it.value }  // Preferred
  ```

- **Immutability**: Always use `val` in data classes, prefer immutable collections

- **Resource management**: Always use `.use()` for AutoCloseable resources
  ```kotlin
  BufferedReader(InputStreamReader(stream)).use { reader ->
      // Resource automatically closed
  }
  ```

### Naming Conventions

- **Packages**: lowercase, hierarchical (e.g., `com.portfoliohelper.model`)
- **Classes**: PascalCase (e.g., `Stock`, `Portfolio`, `CsvStockReader`)
- **Functions**: camelCase (e.g., `readPortfolio`, `configureRouting`)
- **Properties**: camelCase (e.g., `totalValue`, `label`, `price`)

### File Organization

```
src/main/kotlin/com/portfoliohelper/
├── model/          # Data classes only, with computed properties
├── service/        # Business logic and external integrations
│   ├── nav/        # NAV provider services for funds (CTA, CTAP, etc.)
│   └── yahoo/      # Yahoo Finance market data integration
└── web/            # Ktor routes and HTML rendering
```

**Rules:**
- One public class per file
- File name matches the class name
- Keep layers separate - no cross-contamination

## Architecture

### Layer Separation

**Model Layer** (`/model/`)
- Pure data classes with computed properties
- No external dependencies or I/O operations
- Classes: `Stock`, `Portfolio`, `CashEntry`

**Service Layer** (`/service/`)
- Business logic and external resource handling
- CSV parsing, cash file parsing, market data, NAV data
- Classes: `CsvStockReader`, `CashReader`, `PortfolioState`, `CashState`, `CsvFileWatcher`, `PortfolioUpdateBroadcaster`, `SystemTrayService`
- Sub-package `nav/`: `NavService`, `NavProvider`, `NavData`, `CtaNavProvider`, `CtapNavProvider`, `SimplifyEtfNavProvider`
- Sub-package `yahoo/`: `YahooMarketDataService`, `YahooFinanceClient`, `YahooQuote`

**Web Layer** (`/web/`)
- Ktor routing and request handling
- HTML generation using kotlinx.html DSL
- File: `routes.kt`

### Key Patterns

1. **Data Loading**: Load CSV and cash.txt at startup; keep in memory via `PortfolioState` and `CashState`
2. **Hot-Reload**: `CsvFileWatcher` (Java WatchService API) monitors both `stocks.csv` and `cash.txt` with 500ms debounce; SSE broadcasts `reload` event to all connected clients
3. **Server-Side Rendering**: Type-safe kotlinx.html DSL for all HTML; no raw string templates
4. **Live Price Updates**: SSE stream pushes Yahoo Finance price quotes, NAV data, and FX rates to the browser
5. **Dynamic Calculations**: JavaScript recalculates totals, weights, rebalancing amounts, and Est Val as SSE data arrives
6. **FX Conversion**: Non-USD cash entries are converted to USD using Yahoo Finance FX pairs (e.g., `HKDUSD=X`); FX symbols are auto-subscribed based on currencies in `cash.txt`
7. **Currency Formatting**: Server side: `"$%.2f".format(value)`; client side: `toLocaleString('en-US', ...)`
8. **Percentage Formatting**: 1 decimal place `"%.1f%%".format(weight)`; price changes 2 decimal places
9. **Error Handling**: Catch at application level with meaningful context; market data failures are non-fatal

### Design Principles

- **Single Responsibility**: Each class/function has one clear purpose
- **Separation of Concerns**: Web, business, and data layers remain distinct
- **Type Safety**: Leverage Kotlin's type system (kotlinx.html over string templates)
- **Simplicity**: Don't over-engineer - keep solutions straightforward

## Portfolio Features

### Data Files

**`data/stocks.csv`** — Portfolio holdings:
```csv
stock_label,amount,target_weight,letf
AVDV,1163,9.28,
CTAP,3760,27.85,1 CTA 1 IVV
SPUU,388,9.5,2 IVV
```
- `stock_label`: Ticker symbol
- `amount`: Integer share count
- `target_weight`: Optional target allocation % (e.g., 9.28 for 9.28%)
- `letf`: Optional LETF formula — space-separated `multiplier symbol` pairs. Used to estimate intraday value from component day changes when the ETF's own price is stale.

**`data/cash.txt`** — Cash and margin balances:
```
# Cash balances — format: Label.CURRENCY[.M]=amount  (append .M to mark as margin balance)
Cash.HKD.M=-2530000
Cash.USD.M=123
Loan.hkd=123
Interest.USD.M=-700
```
- `Label`: Any descriptive name (e.g., Cash, Loan, Interest)
- `CURRENCY`: ISO code (USD, HKD, etc.); non-USD auto-converted via Yahoo FX
- `.M` suffix: Marks as margin/borrowing balance — shown in separate Margin row
- Lines starting with `#` are comments; empty lines are ignored

### Portfolio Table Columns

The main portfolio table has 14 columns (using professional finance terminology):

| # | Column | Class | Description |
|---|--------|-------|-------------|
| 1 | **Symbol** | — | Stock ticker |
| 2 | **Qty** | `amount` | Shares held; editable in edit mode; copy-to-clipboard button |
| 3 | **Last NAV** | `price` | Previous NAV from fund provider (CTA, CTAP, etc.) |
| 4 | **Est Val** | `price` | Estimated intraday value for LETF stocks, calculated from component day % |
| 5 | **Last** | `price` | Previous trading day's closing price from Yahoo Finance |
| 6 | **Mark** | `price` | Current real-time market price from Yahoo Finance |
| 7 | **Day Chg** | `price-change` | Daily price change in dollars (Mark − Last) |
| 8 | **Day %** | `price-change` | Daily price change as percentage |
| 9 | **Mkt Val** | `value` | Position market value (shares × Mark) |
| 10 | **Mkt Val Chg** | `price-change` | Position value change (Day Chg × Qty) |
| 11 | **Weight** | `weight-display rebal-column` | Current weight with deviation from target (toggleable) |
| 12 | **Rebal $** | `price-change rebal-column` | Dollar amount to buy/sell to reach target weight (toggleable) |
| 13 | **Rebal Shares** | `price-change rebal-column` | Shares to buy/sell to reach target weight (toggleable) |
| 14 | **Target %** | `edit-column` | Target weight input — visible only in edit mode |

Columns 11–13 (rebal-column) are hidden by default; toggled via the "Rebalancing" button. Column 14 (edit-column) is visible only when edit mode is active.

### Key Features

- **Price Change Indicators**: Day Chg and Day % use color coding — green (positive), red (negative), gray (neutral/zero); after-hours prices shown with `after-hours` CSS class
- **Weight Display**: "11.1% (+1.1%)" format — current weight + deviation from target in parentheses; deviation color: green (<1%), amber (1–2%), red (>2%)
- **Rebalancing Columns**: Rebal $ and Rebal Shares show how much to buy (+) or sell (−) per position to reach target weight; threshold $0.50 to avoid noise
- **Est Val (LETF)**: Estimated intraday value for LETF stocks — base price (NAV preferred, fallback to last close) × (1 + Σ multiplier × component day%) ; updated via SSE as component prices change
- **NAV Service**: Fetches previous-day NAV for supported funds (currently CTA via Simplify, CTAP via its own provider); streamed via SSE `type: "nav"` events; displayed in "Last NAV" column
- **Edit Mode**: Toggle the "Edit" button to make Qty, Target %, and cash amounts editable in-place; "Save" writes back to `stocks.csv` and `cash.txt`; file watcher triggers SSE reload
- **Google Sheets Paste**: In edit mode, pasting a column of values from Google Sheets fills multiple rows sequentially
- **Copy Column**: Clipboard copy button on Qty and Target % headers copies column values as newline-delimited text (for pasting back to Google Sheets)
- **Summary Table**: Above the stock table; shows each cash entry, Total Cash (USD), Margin (if any M-flagged entries exist), Portfolio Value with daily change, Total Value (portfolio + cash) with daily change, Last Updated timestamp
- **Grand Total (Total Value)**: Portfolio market value + total cash (FX-converted to USD)
- **Margin Row**: Sum of all `.M`-flagged cash entries in USD; shown with leverage percentage relative to portfolio value when negative
- **CSV Hot-Reload**: Both `stocks.csv` and `cash.txt` are watched; saves from edit mode are detected automatically; SSE `type: "reload"` triggers `location.reload()` in the browser
- **System Tray**: Desktop tray icon with "Open" and "Exit" menu items; startup notification; graceful shutdown via `SystemTrayService`
- **Theme Toggle**: Light/dark theme persisted in `localStorage` as `ib-viewer-theme`; flash-of-wrong-theme prevented by inline script in `<head>`

**Implementation Notes**:
- Stock model computed properties: `value`, `displayPrice`, `priceChangeDollars`, `priceChangePercent`, `priceChangeDirection`, `positionChangeDollars`, `rebalanceDollars()`, `rebalanceShares()`, `rebalanceDirection()`
- Portfolio model computed properties: `totalValue`, `previousTotalValue`, `dailyChangeDollars`, `dailyChangePercent`, `dailyChangeDirection`
- JavaScript functions: `updatePriceInUI`, `updateTotalValue`, `updateCurrentWeights`, `updateRebalancingColumns`, `updateAllEstVals`, `updateCashTotals`, `updateNavInUI`, `updateMarginDisplay`
- `PortfolioState` and `CashState` use `AtomicReference` for thread-safe reads/writes
- `PortfolioUpdateBroadcaster` uses `SharedFlow` for SSE reload events
- `NavService` polls registered `NavProvider` implementations on a configurable interval

## Development Workflow

### Build & Run

```bash
# Build the project
./gradlew build

# Run locally (starts server on port 8080)
./gradlew run

# Create executable shadow JAR
./gradlew shadowJar

# Create portable distributions (includes JAR, data, config, docs)
./gradlew portableDistZip portableDistTar

# Windows EXE with Launch4j (requires Java on target machine)
./gradlew createExe windowsDistZip

# Native Windows installer with jpackage (self-contained, no Java required)
# Requires WiX Toolset for installer creation
./gradlew jpackageImage          # Create app image only (no WiX needed)
./gradlew jpackageDistribution   # Create native installer (requires WiX)
```

### Testing

- **Access web interface**: http://localhost:8080
- **Modify portfolio**: Edit `data/stocks.csv` — browser auto-reloads within 1-2 seconds
- **Modify cash**: Edit `data/cash.txt` — browser auto-reloads within 1-2 seconds
- **Check logs**: Console shows server startup, price updates, CSV/cash reload events, NAV fetch results
- **Test hot-reload**: Change CSV or cash.txt while browser is open — page refreshes automatically

### Configuration

- **Server port**: Environment variable `PORTFOLIO_HELPER_PORT` (default: 8080)
- **Price update interval**: Environment variable `PRICE_UPDATE_INTERVAL` in seconds (default: 60)
- **NAV update interval**: Environment variable `NAV_UPDATE_INTERVAL` in seconds (default: 300)
- **CSV path**: Hard-coded to `data/stocks.csv` at project root; classpath fallback on first run
- **Cash path**: Hard-coded to `data/cash.txt` at project root
- **Logging**: `src/main/resources/logback.xml`

## Important Guidelines

### Do's ✓

- Follow single responsibility principle
- Keep web layer separate from business logic
- Use type-safe kotlinx.html for HTML generation
- Handle errors at appropriate layers with meaningful context
- Use Kotlin's functional programming features (`sumOf`, `map`, etc.)
- Use `.use()` for resource cleanup
- Keep data classes immutable

### Don'ts ✗

- Don't add business logic to model classes (keep them pure data)
- Don't mix web routing with data parsing
- Don't use raw HTML strings - use kotlinx.html DSL
- Don't ignore resource cleanup
- Don't over-engineer simple features
- Don't create mutable state in data classes

## Common Operations

### Adding a New Route

1. Open `src/main/kotlin/com/portfoliohelper/web/routes.kt`
2. Add route to `configureRouting()` function:
   ```kotlin
   get("/new-route") {
       call.respondHtml {
           head { title("New Page") }
           body {
               h1 { +"New Content" }
           }
       }
   }
   ```

### Modifying Data Model

1. Update data class in `/model/` package
2. Update CSV parsing in `CsvStockReader.kt` if CSV format changes
3. Update HTML rendering in `routes.kt` to display new fields
4. Update CSS in `styles.css` if new fields need custom styling

### Adding Table Columns

When adding new columns to the portfolio table:

1. **Add header**: Update `<thead>` section in `routes.kt` with new `<th>` element (use `rebal-column` class for toggleable rebalancing columns, `edit-column` for edit-mode-only)
2. **Add data cells**: Update `<tbody>` section with new `<td>` elements for each stock row
3. **Update CSS alignment**: Extend `.portfolio-table th:nth-child(N)` rules for right-aligned numeric columns
4. **Add JavaScript updates**: If the column needs dynamic updates, add logic to the appropriate JS function (`updatePriceInUI`, `updateTotalValue`, `updateCurrentWeights`, `updateRebalancingColumns`, `updateAllEstVals`)
5. **Add CSS classes**: Use existing classes for consistency — `.price-change` for color-coded changes, `.weight-display` + `.weight-diff` for weight columns, `.value` for market value columns

### Adding a NAV Provider

1. Create a new class implementing `NavProvider` in `service/nav/`
2. Register it in `NavService.providers` list
3. The `NavService` will automatically poll it and push updates via SSE

### Adding Dependencies

1. Edit `build.gradle.kts`
2. Add dependency to appropriate section (e.g., `implementation("...")`)
3. Sync Gradle to download dependencies

### Changing CSV Path or Format

1. **Path**: Update `csvPath` in `Application.kt` (default: `"data/stocks.csv"`)
2. **Format**: Update parsing logic in `CsvStockReader.kt`
3. **Sample data**: Edit `data/stocks.csv` and `src/main/resources/data/stocks.csv` (see `data/README.md` for format)

## Critical Files

- **Application.kt**: Main entry point; startup sequence (CSV → cash → Yahoo → NAV → file watchers → server); shutdown hook
- **routes.kt**: All web routes (`GET /`, `POST /api/portfolio/update`, `POST /api/cash/update`, `GET /api/prices/stream`); HTML rendering; SSE streaming; inline JavaScript
- **styles.css**: Light/dark theme, table formatting, price change colors, rebal-column visibility toggle, edit mode, after-hours styling
- **theme-switcher.js**: Theme toggle logic and persistence
- **model/Stock.kt**: Stock data class; computed properties for price changes, position value, rebalancing
- **model/Portfolio.kt**: Portfolio data class; computed properties for daily changes and totals
- **model/CashEntry.kt**: Cash entry data class with `marginFlag`; key formatting
- **service/CsvStockReader.kt**: CSV parsing; `stock_label`, `amount`, `target_weight`, `letf` columns; backward-compatible optional columns
- **service/CashReader.kt**: `cash.txt` parsing; `Label.CURRENCY[.M]=amount` format; margin flag detection
- **service/PortfolioState.kt**: Thread-safe `AtomicReference` holder for current stocks; exposes `csvPath`
- **service/CashState.kt**: Thread-safe `AtomicReference` holder for current cash entries
- **service/CsvFileWatcher.kt**: Java `WatchService`-based file watcher with 500ms debounce; shared by CSV and cash watchers
- **service/PortfolioUpdateBroadcaster.kt**: `SharedFlow`-based SSE reload event broadcaster
- **service/SystemTrayService.kt**: System tray icon, menu, and notification management
- **service/nav/NavService.kt**: Polls `NavProvider` implementations on configurable interval; caches results; dispatches SSE `nav` events
- **service/nav/NavProvider.kt**: Interface for NAV data sources
- **service/nav/NavData.kt**: Data class holding NAV value and fetch timestamp
- **service/nav/CtaNavProvider.kt**: NAV provider for CTA (via Simplify ETF website)
- **service/nav/CtapNavProvider.kt**: NAV provider for CTAP
- **service/nav/SimplifyEtfNavProvider.kt**: Shared HTTP client for Simplify ETF NAV pages
- **service/yahoo/YahooMarketDataService.kt**: Yahoo Finance polling; manages symbol subscriptions; dispatches price SSE events; also fetches FX pairs for cash currencies
- **service/yahoo/YahooFinanceClient.kt**: Low-level HTTP client for Yahoo Finance v7/v8 API
- **service/yahoo/YahooQuote.kt**: Data class for Yahoo Finance quote fields
- **build.gradle.kts**: Build configuration and dependencies
- **application.conf**: Ktor server configuration (port default, engine settings)
- **data/stocks.csv**: Live portfolio data file (editable; hot-reloaded)
- **data/cash.txt**: Live cash and margin balances (editable; hot-reloaded)
- **data/README.md**: Data file format documentation

## Additional Notes

- **Hot-Reload**: Both `stocks.csv` and `cash.txt` are watched; edit in any text editor, save, and browser refreshes within 1-2 seconds; no server restart needed; file watcher disabled if file doesn't exist at startup
- **First Run**: If `data/stocks.csv` is missing at startup, the bundled template (`src/main/resources/data/stocks.csv`) is copied there automatically
- **Professional Finance UI**: Bloomberg Terminal-style interface — color-coded daily changes, consolidated weight display with deviation alerts, portfolio total with daily gain/loss, margin leverage display
- **HTML Generation**: Server-side via kotlinx.html DSL (type-safe, no raw strings); JavaScript handles all dynamic updates client-side
- **SSE Event Types**:
  - Default (no `type`): Price update — `{ symbol, markPrice, lastClosePrice, isMarketClosed, timestamp }`
  - `"nav"`: NAV update — `{ type, symbol, nav, timestamp }`
  - `"reload"`: Portfolio structure changed — `{ type, timestamp }` → triggers `location.reload()`
  - FX pair update (symbol ends with `USD=X`): `{ symbol, markPrice }` → updates `fxRates` map and recalculates cash totals
- **Formatting Standards**:
  - Currency values: 2 decimal places (`$1,234.56`)
  - Percentage values: 1 decimal place for weights (`9.5%`), 2 decimal places for price changes (`+1.23%`)
  - Price changes: explicit +/- sign prefix (`+$1.25`, `-0.50%`)
  - Zero/negligible changes (< $0.001): displayed as `—` with `neutral` CSS class
- **Deployment**:
  - Shadow JAR for cross-platform Java deployments
  - Launch4j EXE wrapper for Windows (requires Java on target machine)
  - jpackage native installer with bundled JRE (fully self-contained, ~50–60 MB)
