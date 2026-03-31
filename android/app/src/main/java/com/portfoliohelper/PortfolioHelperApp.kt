package com.portfoliohelper

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.portfoliohelper.data.repository.AppDatabase
import com.portfoliohelper.data.repository.SettingsRepository
import com.portfoliohelper.data.repository.SyncRepository

class PortfolioHelperApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "portfoliohelper.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO portfolios (serialId, displayName, slug) VALUES (1, 'Main', '')")
                    db.execSQL("INSERT INTO portfolio_margin_alerts (portfolioId, lowerPct, upperPct) VALUES (1, -1.0, -1.0)")
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("INSERT INTO portfolios (serialId, displayName, slug) SELECT 1, 'Main', '' WHERE NOT EXISTS (SELECT 1 FROM portfolios)")
                    db.execSQL("INSERT INTO portfolio_margin_alerts (portfolioId, lowerPct, upperPct) SELECT 1, -1.0, -1.0 WHERE NOT EXISTS (SELECT 1 FROM portfolio_margin_alerts)")
                }
            })
            .build()
    }

    val settingsRepo: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    val syncRepo: SyncRepository by lazy {
        SyncRepository(this, database, settingsRepo)
    }
}
