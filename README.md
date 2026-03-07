# Portfolio Helper

A self-hosted stock portfolio dashboard with backtesting, Monte Carlo simulations, and real-time price updates.

## Features

- Real-time price updates via Yahoo Finance (configurable interval)
- Multi-portfolio support (auto-discovered from `data/` subfolders)
- Backtesting engine with 34+ years of historical data
- Monte Carlo simulation (thousands of trials)
- Portfolio rebalancing with target weight tracking
- LETF (leveraged ETF) component tracking
- Multi-currency cash balances with live FX conversion
- Margin tracking
- Loan calculator
- Hot-reload: edit CSV/cash files while app is running
- Interactive Brokers TWS integration (optional)
- System tray icon (Windows/Linux)
- IBKR margin rate display
- NAV tracking for select mutual funds (CTA, CTap)

## Tech Stack

| Component | Library/Version |
|---|---|
| Language | Kotlin 2.3.0 |
| Web Framework | Ktor 3.4.0 |
| HTML DSL | kotlinx.html 0.11.0 |
| Serialization | kotlinx-serialization 1.8.0 |
| Coroutines | kotlinx-coroutines 1.9.0 |
| CSV Parsing | Apache Commons CSV 1.10.0 |
| HTTP Client | Ktor CIO + OkHttp 4.12.0 |
| HTML Scraping | Jsoup 1.17.2 |
| Logging | Logback 1.5.32 |
| Runtime | Java 17 |
| Build | Gradle (wrapper included) |

## Getting Started

### Prerequisites

- Java 17+

### Run from JAR (quickest)

```bash
java -jar portfolio-helper-0.4.0-all.jar
```

Opens http://localhost:8080 automatically.

### Run from source

```bash
./gradlew run
```

### Build a fat JAR

```bash
./gradlew shadowJar
# Output: build/libs/portfolio-helper-0.4.0-all.jar
```

For more detailed running instructions, see [docs/RUNNING.md](docs/RUNNING.md).

## Configuration

Environment variables (all optional):

| Variable | Default | Description |
|---|---|---|
| `PORTFOLIO_HELPER_PORT` | `8080` | HTTP port |
| `PRICE_UPDATE_INTERVAL` | `60` | Yahoo price poll interval (seconds) |
| `NAV_UPDATE_INTERVAL` | `300` | NAV poll interval (seconds) |

## Data Files

### Portfolio: `data/stocks.csv`

4 columns, no header:

```
stock_label,amount,target_weight,letf
AAPL,10,0.25,false
UPRO,5,0.10,true
```

- `letf=true` enables intraday LETF calculation
- Hot-reloaded within ~1 second of saving

### Cash: `data/cash.txt`

```
USD Cash.USD=10000
Margin Loan.USD.M=-5000
EUR Savings.EUR=2000
Mom Portfolio.P=mom
```

- `.M` suffix = margin entry (included in margin denominator)
- `.P=<id>` = reference another portfolio's total value
- Supports any currency with live FX conversion from Yahoo

### Multi-portfolio

Place additional portfolios in subfolders:

```
data/
  stocks.csv        <- Main portfolio
  cash.txt
  mom/
    stocks.csv      <- "Mom" portfolio (auto-discovered)
    cash.txt
```

A tab bar appears automatically when multiple portfolios exist. Access via `/portfolio/{id}`.

## Pages / Routes

| Path | Description |
|---|---|
| `/` | Main portfolio dashboard |
| `/portfolio/{id}` | Named portfolio |
| `/backtest` | Historical backtesting engine |
| `/montecarlo` | Monte Carlo simulation |
| `/loan` | Loan payoff calculator |

## Distribution / Packaging

```bash
./gradlew portableDistZip      # Cross-platform ZIP (JAR + config + notices)
./gradlew portableDistTar      # Same as TAR.GZ
./gradlew windowsDistZip       # Windows EXE launcher
./gradlew jpackageDistribution # Self-contained bundle (no JVM required)
./gradlew generateLicenseReport # Generate THIRD_PARTY_NOTICES.txt
```

## License

PolyForm Noncommercial License 1.0.0 — free for personal, educational, and non-commercial use. See [LICENSE](LICENSE).
