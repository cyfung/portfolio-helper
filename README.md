# Stock Portfolio Viewer

A lightweight Kotlin web application for viewing stock portfolio data from CSV files. The application calculates individual stock values and total portfolio value, presenting them in an easy-to-read web interface.

## Features

- View stock holdings with individual and total values
- Automatic calculation of stock values (price × amount)
- Clean, responsive web interface
- Server-side rendering with kotlinx.html
- CSV-based data storage for easy editing

## Tech Stack

- **Kotlin** 1.9.21
- **Ktor** 2.3.7 - Web framework
- **kotlinx.html** - Type-safe HTML DSL
- **Apache Commons CSV** 1.10.0 - CSV parsing
- **Gradle** 8.5 - Build system
- **Netty** - Embedded server

## Prerequisites

- Java 11 or higher
- The project includes Gradle wrapper, so no separate Gradle installation is needed

## Getting Started

### 1. Build the Project

```bash
./gradlew build
```

On Windows:
```bash
gradlew.bat build
```

### 2. Run the Application

```bash
./gradlew run
```

On Windows:
```bash
gradlew.bat run
```

The server will start on port 8080. You should see output like:
```
Successfully loaded 10 stocks from CSV
Total portfolio value: $12345.67
```

### 3. Access the Web Interface

Open your web browser and navigate to:
```
http://localhost:8080
```

You'll see a table displaying:
- Stock ticker symbols
- Price per share
- Number of shares owned
- Total value for each stock (price × amount)
- Total portfolio value

## CSV Data Format

The application reads stock data from `src/main/resources/data/stocks.csv`.

### Required Format

```csv
stock_label,stock_price,amount
AAPL,175.50,10
GOOGL,140.25,5
MSFT,380.00,8
```

### Column Definitions

- `stock_label`: Stock ticker symbol (string)
- `stock_price`: Price per share (decimal number)
- `amount`: Number of shares owned (integer)

### Updating Your Portfolio

1. Stop the application (Ctrl+C)
2. Edit `src/main/resources/data/stocks.csv` with your stock data
3. Restart the application with `./gradlew run`

## Project Structure

```
portfolio-helper/
├── build.gradle.kts              # Build configuration
├── settings.gradle.kts           # Project settings
├── gradlew & gradlew.bat        # Gradle wrapper scripts
├── src/main/
│   ├── kotlin/com/portfoliohelper/
│   │   ├── Application.kt                    # Main entry point
│   │   ├── model/
│   │   │   ├── Stock.kt                     # Stock data class
│   │   │   └── Portfolio.kt                 # Portfolio data class
│   │   ├── service/
│   │   │   └── CsvStockReader.kt           # CSV parsing logic
│   │   └── web/
│   │       └── routes.kt                    # Web routes & HTML
│   └── resources/
│       ├── application.conf                 # Ktor configuration
│       ├── logback.xml                      # Logging configuration
│       ├── static/styles.css                # CSS styling
│       └── data/stocks.csv                  # Stock data
└── README.md
```

## Configuration

### Server Port

The default port is 8080. To change it, edit `src/main/resources/application.conf`:

```hocon
ktor {
    deployment {
        port = 8080
    }
}
```

### CSV File Path

The CSV file path is defined in `Application.kt`:
```kotlin
CsvStockReader.readPortfolio("data/stocks.csv")
```

## Error Handling

The application includes error handling for common issues:

- **Missing CSV file**: Application will fail to start with an error message
- **Invalid CSV format**: Error message indicating the problem row
- **Invalid numbers**: Error message for non-numeric price or amount values
- **Missing columns**: Error message for missing required columns

## Development

### Running in Development Mode

```bash
./gradlew run --continuous
```

This will automatically reload when you make changes (you'll need to restart for CSV changes).

### Building Distributions

#### Shadow JAR (Single Executable)

Create a single fat JAR with all dependencies:
```bash
./gradlew shadowJar
```

Run the JAR:
```bash
java -jar build/libs/portfolio-helper-1.0-SNAPSHOT-all.jar
```

#### Portable Distribution Packages

Create complete distributions with shadow JAR, data, and config:
```bash
./gradlew portableDistZip portableDistTar
```

Distributions created in `build/distributions/`:
- `portfolio-helper-portable-*-complete.zip` (~16MB) - Cross-platform JAR with data and config
- `portfolio-helper-portable-*-complete.tar.gz` (~14MB) - Cross-platform JAR, compressed
- `portfolio-helper-*.zip` (standard Gradle distribution with scripts)
- `portfolio-helper-*.tar` (standard Gradle distribution with scripts)

**Note**: Windows EXE generation via Launch4j is currently disabled in the build configuration due to environment-specific issues. To enable it, uncomment the Launch4j plugin and tasks in `build.gradle.kts`.

#### Runtime Configuration

Configure CSV path and other settings without rebuilding:

**Windows:**
```cmd
set CSV_FILE_PATH=C:\data\portfolio.csv
portfolio-helper.exe
```

**Linux/macOS:**
```bash
export CSV_FILE_PATH=/path/to/portfolio.csv
java -jar portfolio-helper-all.jar
```

**Alternative: System Property**
```bash
java -Dcsv.file.path=/custom/path.csv -jar portfolio-helper-all.jar
```

## Sample Data

The project includes sample data for 10 popular stocks:
- AAPL (Apple)
- GOOGL (Google)
- MSFT (Microsoft)
- TSLA (Tesla)
- AMZN (Amazon)
- NVDA (NVIDIA)
- META (Meta/Facebook)
- NFLX (Netflix)
- AMD (AMD)
- INTC (Intel)

Replace this data with your actual portfolio holdings.

## Troubleshooting

### Port Already in Use

If port 8080 is already in use, you'll see an error. Either:
- Stop the application using that port
- Change the port in `application.conf`

### CSV Parsing Errors

Ensure your CSV file:
- Has a header row with exact column names: `stock_label,stock_price,amount`
- Contains valid decimal numbers for price
- Contains valid integers for amount
- Uses commas as separators
- Is saved in UTF-8 encoding

### Application Won't Start

Check that:
- Java 11 or higher is installed: `java -version`
- The CSV file exists at `src/main/resources/data/stocks.csv`
- The CSV file has the correct format

## Future Enhancements

Potential features for future versions:
- CSV file upload through web UI
- Live reload when CSV file changes
- Sortable table columns
- Export to PDF/Excel
- Multiple portfolio support
- REST API endpoint for JSON data
- Real-time stock price updates

## License

This project is open source and available for personal use.
