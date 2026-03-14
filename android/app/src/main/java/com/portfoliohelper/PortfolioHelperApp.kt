package com.portfoliohelper

import android.app.Application
import androidx.room.Room
import com.portfoliohelper.data.repository.AppDatabase
import com.portfoliohelper.data.repository.SettingsRepository
import com.portfoliohelper.data.repository.SyncRepository

class PortfolioHelperApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "portfoliohelper.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val settingsRepo: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    val syncRepo: SyncRepository by lazy {
        SyncRepository(this, database, settingsRepo)
    }
}
