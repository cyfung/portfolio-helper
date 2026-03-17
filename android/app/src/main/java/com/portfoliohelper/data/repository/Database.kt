package com.portfoliohelper.data.repository

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.MarketPrice
import com.portfoliohelper.data.model.Portfolio
import com.portfoliohelper.data.model.PortfolioMarginAlert
import com.portfoliohelper.data.model.Position
import kotlinx.coroutines.flow.Flow

// ── Portfolio DAO ─────────────────────────────────────────────────────────────

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios ORDER BY id")
    fun observeAll(): Flow<List<Portfolio>>

    @Query("SELECT * FROM portfolios ORDER BY id")
    suspend fun getAll(): List<Portfolio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(portfolio: Portfolio)

    @Query("DELETE FROM portfolios WHERE id = :id")
    suspend fun delete(id: String)
}

// ── Portfolio Margin Alert DAO ────────────────────────────────────────────────

@Dao
interface PortfolioMarginAlertDao {
    @Query("SELECT * FROM portfolio_margin_alerts ORDER BY portfolioId")
    fun observeAll(): Flow<List<PortfolioMarginAlert>>

    @Query("SELECT * FROM portfolio_margin_alerts ORDER BY portfolioId")
    suspend fun getAll(): List<PortfolioMarginAlert>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alert: PortfolioMarginAlert)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(alerts: List<PortfolioMarginAlert>)
}

// ── Position DAO ──────────────────────────────────────────────────────────────

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId AND isDeleted = 0 ORDER BY symbol")
    fun observeAll(portfolioId: String): Flow<List<Position>>

    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId AND isDeleted = 0 ORDER BY symbol")
    suspend fun getAll(portfolioId: String): List<Position>

    @Query("SELECT * FROM positions WHERE isDeleted = 0 ORDER BY symbol")
    suspend fun getAllPositions(): List<Position>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: Position)

    @Query("UPDATE positions SET isDeleted = 1 WHERE portfolioId = :portfolioId AND symbol = :symbol")
    suspend fun softDelete(portfolioId: String, symbol: String)

    @Query("DELETE FROM positions WHERE portfolioId = :portfolioId")
    suspend fun hardDeleteAll(portfolioId: String)

    @Query("DELETE FROM positions")
    suspend fun hardDeleteAll()

    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId AND symbol = :symbol LIMIT 1")
    suspend fun get(portfolioId: String, symbol: String): Position?
}

// ── Cash DAO ──────────────────────────────────────────────────────────────────

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_entries WHERE portfolioId = :portfolioId ORDER BY label")
    fun observeAll(portfolioId: String): Flow<List<CashEntry>>

    @Query("SELECT * FROM cash_entries WHERE portfolioId = :portfolioId ORDER BY label")
    suspend fun getAll(portfolioId: String): List<CashEntry>

    @Query("SELECT * FROM cash_entries ORDER BY label")
    suspend fun getAllEntries(): List<CashEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CashEntry)

    @Delete
    suspend fun delete(entry: CashEntry)

    @Query("DELETE FROM cash_entries WHERE portfolioId = :portfolioId")
    suspend fun deleteAll(portfolioId: String)

    @Query("DELETE FROM cash_entries")
    suspend fun deleteAll()
}

// ── Market Price DAO ─────────────────────────────────────────────────────────

@Dao
interface MarketPriceDao {
    @Query("SELECT * FROM market_prices WHERE symbol = :symbol LIMIT 1")
    suspend fun get(symbol: String): MarketPrice?

    @Query("SELECT * FROM market_prices")
    suspend fun getAll(): List<MarketPrice>

    @Query("SELECT * FROM market_prices")
    fun observeAll(): Flow<List<MarketPrice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(price: MarketPrice)

    @Query("DELETE FROM market_prices")
    suspend fun deleteAll()
}

// ── Migration 10 → 11 ─────────────────────────────────────────────────────────

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // New portfolios table
        db.execSQL("CREATE TABLE portfolios (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL)")
        db.execSQL("INSERT INTO portfolios (id, displayName) VALUES ('main', 'Main')")

        // Recreate positions with composite PK (portfolioId + symbol)
        db.execSQL("""
            CREATE TABLE positions_new (
                portfolioId TEXT NOT NULL DEFAULT 'main',
                symbol TEXT NOT NULL,
                quantity REAL NOT NULL,
                targetWeight REAL NOT NULL,
                groups TEXT NOT NULL DEFAULT '',
                isDeleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(portfolioId, symbol)
            )
        """.trimIndent())
        db.execSQL("INSERT INTO positions_new SELECT 'main', symbol, quantity, targetWeight, groups, isDeleted FROM positions")
        db.execSQL("DROP TABLE positions")
        db.execSQL("ALTER TABLE positions_new RENAME TO positions")

        // Add portfolioId to cash_entries
        db.execSQL("ALTER TABLE cash_entries ADD COLUMN portfolioId TEXT NOT NULL DEFAULT 'main'")

        // Per-portfolio margin alert settings
        db.execSQL("""
            CREATE TABLE portfolio_margin_alerts (
                portfolioId TEXT NOT NULL PRIMARY KEY,
                enabled INTEGER NOT NULL DEFAULT 0,
                lowerPct REAL NOT NULL DEFAULT 20.0,
                upperPct REAL NOT NULL DEFAULT 50.0
            )
        """.trimIndent())

        // Seed a default alert row for "main"
        db.execSQL("INSERT INTO portfolio_margin_alerts (portfolioId) VALUES ('main')")
    }
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [
        Portfolio::class,
        Position::class,
        CashEntry::class,
        MarketPrice::class,
        PortfolioMarginAlert::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun portfolioMarginAlertDao(): PortfolioMarginAlertDao
    abstract fun positionDao(): PositionDao
    abstract fun cashDao(): CashDao
    abstract fun marketPriceDao(): MarketPriceDao
}
