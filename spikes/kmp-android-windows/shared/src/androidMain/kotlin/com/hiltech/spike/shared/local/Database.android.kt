package com.hiltech.spike.shared.local

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

fun getAndroidDatabaseBuilder(
    context: Context,
    databaseName: String = "hiltech-local.db",
): RoomDatabase.Builder<HiltechLocalDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(databaseName)

    return Room.databaseBuilder<HiltechLocalDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
    )
}
