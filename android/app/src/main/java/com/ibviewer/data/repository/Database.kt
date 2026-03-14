package com.ibviewer.data.repository

import androidx.room.*
import com.ibviewer.data.model.CashEntry
import com.ibviewer.data.model.MarketPrice
import com.ibviewer.data.model.Position
import kotlinx.coroutines.flow.Flow

// ── Position DAO ──────────────────────────────────────────────────────────────

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE isDeleted = 0 ORDER BY symbol")
    fun observeAll(): Flow<List<Position>>

    @Query("SELECT * FROM positions WHERE isDeleted = 0 ORDER BY symbol")
    suspend fun getAll(): List<Position>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: Position)

    @Query("UPDATE positions SET isDeleted = 1 WHERE symbol = :symbol")
    suspend fun softDelete(symbol: String)

    @Query("DELETE FROM positions WHERE symbol = :symbol")
    suspend fun hardDelete(symbol: String)

    @Query("DELETE FROM positions")
    suspend fun hardDeleteAll()

    @Query("SELECT * FROM positions WHERE symbol = :symbol LIMIT 1")
    suspend fun get(symbol: String): Position?
}

// ── Cash DAO ──────────────────────────────────────────────────────────────────

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_entries ORDER BY label")
    fun observeAll(): Flow<List<CashEntry>>

    @Query("SELECT * FROM cash_entries ORDER BY label")
    suspend fun getAll(): List<CashEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CashEntry)

    @Delete
    suspend fun delete(entry: CashEntry)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(price: MarketPrice)

    @Query("DELETE FROM market_prices")
    suspend fun deleteAll()
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [Position::class, CashEntry::class, MarketPrice::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun positionDao(): PositionDao
    abstract fun cashDao(): CashDao
    abstract fun marketPriceDao(): MarketPriceDao
}
