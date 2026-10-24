package ru.e90pult.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        SmsEntity::class,
        CommandRunEntity::class,
        ObservationEntity::class,
        BalanceEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun sms(): SmsDao
    abstract fun runs(): RunDao
    abstract fun obs(): ObsDao
    abstract fun balance(): BalanceDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "e90pult.db").build()
    }
}
