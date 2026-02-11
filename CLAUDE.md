# Portfolio Helper Project - Claude Code Instructions

## Project Overview

**Portfolio Helper** is a stock portfolio viewer web application that displays portfolio data from CSV files.

- **Description**: Web application for visualizing stock portfolios with real-time value calculations and portfolio rebalancing analysis
- **Tech Stack**: Kotlin, Ktor 2.3.7, kotlinx.html, Apache Commons CSV, Gradle
- **Architecture**: Clean architecture with three distinct layers:
  - **Model Layer**: Domain data classes
  - **Service Layer**: Business logic and CSV parsing
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
├── service/        # Business logic and external integrations (CSV reading)
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
- Example: `Stock`, `Portfolio`

**Service Layer** (`/service/`)
- Business logic and external resource handling
- CSV parsing, data transformation
- Example: `CsvStockReader`

**Web Layer** (`/web/`)
- Ktor routing and request handling
- HTML generation using kotlinx.html DSL
- Example: `routes.kt`

### Key Patterns

1. **Data Loading**: Load CSV data at application startup, keep in memory via PortfolioState
2. **Hot-Reload**: File watcher detects CSV changes and automatically reloads portfolio data
3. **Server-Side Rendering**: Use type-safe kotlinx.html DSL for all HTML
4. **Live Price Updates**: Server-Sent Events (SSE) stream real-time market data from Yahoo Finance
5. **Dynamic Calculations**: JavaScript recalculates portfolio totals, weights, and daily changes as prices update
6. **Currency Formatting**: Format on server side using `"$%.2f".format(value)`
7. **Percentage Formatting**: Format weights with 1 decimal place using `"%.1f%%".format(weight)`
8. **Error Handling**: Catch at application level, provide context with exceptions

### Design Principles

- **Single Responsibility**: Each class/function has one clear purpose
- **Separation of Concerns**: Web, business, and data layers remain distinct
- **Type Safety**: Leverage Kotlin's type system (kotlinx.html over string templates)
- **Simplicity**: Don't over-engineer - keep solutions straightforward

## Portfolio Features

### Portfolio Rebalancing Analysis

The application displays target allocations and actual allocations to support portfolio rebalancing decisions:

**Portfolio Table Columns** (9 columns total, using professional finance terminology):
1. **Symbol**: Stock ticker (e.g., "AAPL", "VXUS")
2. **Qty**: Number of shares held
3. **Last**: Previous trading day's closing price
4. **Mark**: Current real-time market price from Yahoo Finance
5. **Day Chg**: Daily price change in dollars (Mark - Last)
6. **Day %**: Daily price change as percentage
7. **Mkt Val**: Position market value (shares × Mark price)
8. **Mkt Val Chg**: Position value change in dollars (Day Chg × Qty)
9. **Weight**: Current allocation with deviation from target (e.g., "11.1% (+1.1%)")

**Key Features**:
- **Price Change Indicators**: Day Chg and Day % columns show daily price movements
  - Green (positive) for gains, red (negative) for losses, gray (neutral) for no change
  - Automatically calculated from Mark and Last prices
  - Position value change (Mkt Val Chg) shows dollar impact on portfolio
- **Consolidated Weight Display**: Single column shows both current and target weights
  - Format: "11.1% (+1.1%)" where first value is current, parenthetical is deviation from target
  - Color-coded deviations: green (<1%), amber (1-2%), red (>2%)
  - Target weights loaded from CSV (`targetWeight` column)
- **Portfolio Total with Daily Change**: Footer shows total value and daily gain/loss
  - Format: "$125,450.50 +$1,250.00 (+1.0%)"
  - Daily change calculated from sum of all position changes
  - Updates dynamically as prices change via SSE
- **CSV Hot-Reload**: Automatic page refresh when CSV file is modified
  - File watcher monitors CSV for changes (500ms debounce)
  - SSE broadcasts reload event to all connected clients
  - Clients automatically refresh to show updated portfolio

**Implementation Notes**:
- Stock model includes computed properties: `priceChangeDollars`, `priceChangePercent`, `priceChangeDirection`, `positionChangeDollars`
- Portfolio model includes: `dailyChangeDollars`, `previousTotalValue`, `dailyChangePercent`, `dailyChangeDirection`
- Target weight is optional (`Double?`) - displays only current weight if not provided
- JavaScript functions (`updatePriceInUI`, `updateTotalValue`, `updateCurrentWeights`) handle dynamic updates
- PortfolioState singleton holds current base stocks for thread-safe updates
- CsvFileWatcher uses Java's WatchService API for file monitoring
- PortfolioUpdateBroadcaster uses SharedFlow for SSE reload events

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

# Runtime Configuration
# CSV path: Set CSV_FILE_PATH env var or -Dcsv.file.path=...
# Server port: Set PORT env var or edit application.conf
```

### Testing

- **Access web interface**: http://localhost:8080
- **Modify data**: Edit `data/stocks.csv` - browser will auto-reload within 1-2 seconds
- **Check logs**: Console output shows server startup, request handling, and CSV reload events
- **Test hot-reload**: Change CSV while browser is open - page should refresh automatically

### Configuration

- **Server port**: `src/main/resources/application.conf` (default: 8080)
- **Logging**: `src/main/resources/logback.xml`
- **CSV path**: Runtime-configurable via environment variable or system property
  - Environment variable: `CSV_FILE_PATH`
  - System property: `-Dcsv.file.path=...`
  - Default: `data/stocks.csv` (project root)
  - Falls back to classpath if file doesn't exist

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

1. Open `src/main/kotlin/com/ibviewer/web/routes.kt`
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

When adding new columns to the portfolio table (currently 9 columns):

1. **Add header**: Update `<thead>` section in `routes.kt` with new `<th>` element
2. **Add data cells**: Update `<tbody>` section with new `<td>` elements for each stock row
3. **Update footer colspan**: Adjust `colSpan` attribute in `<tfoot>` (currently "8" for 9-column table)
4. **Update CSS alignment**: Extend `.portfolio-table th:nth-child(N)` rules for right-aligned numeric columns (currently 2-9)
5. **Add JavaScript updates**: If column needs dynamic updates, add logic to appropriate update functions (`updatePriceInUI`, `updateTotalValue`, `updateCurrentWeights`)
6. **Add CSS classes**: Create specific classes for consistent styling

Examples:
- Price change columns use `.price-change` class with color-coded values (positive/negative/neutral)
- Weight column uses `.weight-display` class with `.weight-diff` for deviation indicators
- Value columns use `.value` class with monospace font

### Adding Dependencies

1. Edit `build.gradle.kts`
2. Add dependency to appropriate section (e.g., `implementation("...")`)
3. Sync Gradle to download dependencies

### Changing CSV Path or Format

1. **Path**: Update `csvPath` variable in `Application.kt` (default: `"data/stocks.csv"`)
2. **Format**: Update parsing logic in `CsvStockReader.kt`
3. **Sample data**: Edit `data/stocks.csv` (see `data/README.md` for format)

## Critical Files

- **Application.kt**: Main entry point, server configuration, application startup, CSV file watcher integration
- **routes.kt**: Web routes, HTML rendering logic, SSE endpoint, JavaScript for dynamic updates
- **styles.css**: CSS styling with light/dark theme support, table formatting, responsive design, price change indicators
- **model/Stock.kt**: Stock data class with computed properties for price changes and position values
- **model/Portfolio.kt**: Portfolio data class with computed properties for daily changes
- **service/CsvStockReader.kt**: CSV parsing logic
- **service/PortfolioState.kt**: Thread-safe holder for current portfolio base stocks
- **service/CsvFileWatcher.kt**: File watcher service for automatic CSV reload
- **service/PortfolioUpdateBroadcaster.kt**: SSE reload event broadcaster using SharedFlow
- **service/yahoo/YahooMarketDataService.kt**: Real-time market data integration
- **build.gradle.kts**: Build configuration and dependencies
- **application.conf**: Server configuration
- **data/stocks.csv**: Portfolio data file (editable at project root)
- **data/README.md**: CSV format documentation

## Additional Notes

- **CSV Hot-Reload**: The application automatically detects CSV file changes and reloads portfolio data
  - File watcher monitors `./data/stocks.csv` (project root) with 500ms debounce
  - Edit the CSV in any text editor and save
  - SSE broadcasts reload event to all connected browser clients
  - Clients automatically refresh page to show updated portfolio
  - No manual server restart required
  - Falls back to classpath (`src/main/resources/data/stocks.csv`) if file doesn't exist
- **Professional Finance UI**: Bloomberg Terminal-style interface with industry-standard terminology
  - Daily price changes with color-coded indicators (green/red/gray)
  - Position value changes show dollar impact on portfolio
  - Consolidated weight display with deviation alerts
  - Portfolio total includes daily gain/loss summary
- **HTML Generation**: Server-side using kotlinx.html DSL (type-safe, no string templates)
- **Dynamic Updates**: JavaScript handles real-time updates via SSE
  - Price updates (mark and close prices)
  - Daily change calculations ($ and %)
  - Position value change calculations
  - Total portfolio recalculation
  - Weight recalculation with deviation display
- **Formatting Standards**:
  - Currency values: 2 decimal places (e.g., "$1,234.56")
  - Percentage values: 1 decimal place (e.g., "9.5%")
  - Price changes: +/- sign prefix (e.g., "+$1.25", "-0.5%")
- **Market Data**: Real-time prices fetched from Yahoo Finance API
- **Deployment**:
  - Shadow JAR for cross-platform Java deployments
  - Launch4j EXE wrapper for Windows (requires Java)
  - jpackage native installer with bundled JRE (fully self-contained, ~50-60MB)
