package com.swiftbrowser.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.swiftbrowser.data.dao.BookmarkDao
import com.swiftbrowser.data.dao.DownloadRecordDao
import com.swiftbrowser.data.dao.HistoryDao
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.data.entity.DownloadRecord
import com.swiftbrowser.data.entity.History

@Database(
    entities = [Bookmark::class, History::class, DownloadRecord::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadRecordDao(): DownloadRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "swift_browser.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
