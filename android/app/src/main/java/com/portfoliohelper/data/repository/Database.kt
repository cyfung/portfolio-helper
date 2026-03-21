package com.portfoliohelper.data.repository

import androidx.room.*
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.MarketPrice
import com.portfoliohelper.data.model.Portfolio
import com.portfoliohelper.data.model.PortfolioMarginAlert
import com.portfoliohelper.data.model.Position
import kotlinx.coroutines.flow.Flow

// ── Portfolio DAO ─────────────────────────────────────────────────────────────

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios ORDER BY serialId")
    fun observeAll(): Flow<List<Portfolio>>

    @Query("SELECT * FROM portfolios ORDER BY serialId")
    suspend fun getAll(): List<Portfolio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(portfolio: Portfolio)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(portfolio: Portfolio): Long

    @Query("DELETE FROM portfolios WHERE serialId = :serialId")
    suspend fun delete(serialId: Int)

    @Query("DELETE FROM portfolios")
    suspend fun deleteAll()
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

    @Query("DELETE FROM portfolio_margin_alerts WHERE portfolioId = :portfolioId")
    suspend fun delete(portfolioId: Int)

    @Query("DELETE FROM portfolio_margin_alerts WHERE portfolioId NOT IN (SELECT serialId FROM portfolios)")
    suspend fun deleteOrphans()
}

// ── Position DAO ──────────────────────────────────────────────────────────────

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId AND isDeleted = 0 ORDER BY symbol")
    fun observeAll(portfolioId: Int): Flow<List<Position>>

    @Query("SELECT * FROM positions WHERE isDeleted = 0 ORDER BY symbol")
    fun observeAll(): Flow<List<Position>>

    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId AND isDeleted = 0 ORDER BY symbol")
    suspend fun getAll(portfolioId: Int): List<Position>

    @Query("SELECT * FROM positions WHERE isDeleted = 0 ORDER BY symbol")
    suspend fun getAllPositions(): List<Position>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: Position)

    @Query("UPDATE positions SET isDeleted = 1 WHERE portfolioId = :portfolioId AND symbol = :symbol")
    suspend fun softDelete(portfolioId: Int, symbol: String)

    @Query("DELETE FROM positions WHERE portfolioId = :portfolioId")
    suspend fun hardDeleteAll(portfolioId: Int)

    @Query("DELETE FROM positions")
    suspend fun hardDeleteAll()

    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId AND symbol = :symbol LIMIT 1")
    suspend fun get(portfolioId: Int, symbol: String): Position?
}

// ── Cash DAO ──────────────────────────────────────────────────────────────────

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_entries WHERE portfolioId = :portfolioId ORDER BY label")
    fun observeAll(portfolioId: Int): Flow<List<CashEntry>>

    @Query("SELECT * FROM cash_entries ORDER BY label")
    fun observeAll(): Flow<List<CashEntry>>

    @Query("SELECT * FROM cash_entries WHERE portfolioId = :portfolioId ORDER BY label")
    suspend fun getAll(portfolioId: Int): List<CashEntry>

    @Query("SELECT * FROM cash_entries ORDER BY label")
    suspend fun getAllEntries(): List<CashEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CashEntry)

    @Delete
    suspend fun delete(entry: CashEntry)

    @Query("DELETE FROM cash_entries WHERE portfolioId = :portfolioId")
    suspend fun deleteAll(portfolioId: Int)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(prices: List<MarketPrice>)

    @Query("DELETE FROM market_prices")
    suspend fun deleteAll()
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
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun portfolioMarginAlertDao(): PortfolioMarginAlertDao
    abstract fun positionDao(): PositionDao
    abstract fun cashDao(): CashDao
    abstract fun marketPriceDao(): MarketPriceDao
}
