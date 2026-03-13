package com.ibviewer

import android.app.Application
import androidx.room.Room
import com.ibviewer.data.repository.AppDatabase
import com.ibviewer.data.repository.SettingsRepository
import com.ibviewer.data.repository.SyncRepository

class IbViewerApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ibviewer.db")
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
