package com.hiltech.spike.shared.local

import androidx.room3.Room
import androidx.room3.RoomDatabase

fun getDesktopDatabaseBuilder(
    databasePath: String,
): RoomDatabase.Builder<HiltechLocalDatabase> =
    Room.databaseBuilder<HiltechLocalDatabase>(
        name = databasePath,
    )
