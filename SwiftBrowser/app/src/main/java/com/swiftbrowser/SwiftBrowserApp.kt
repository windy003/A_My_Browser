package com.swiftbrowser

import android.app.Application
import com.swiftbrowser.data.database.AppDatabase
import com.swiftbrowser.data.entity.Bookmark
import com.swiftbrowser.sync.FirebaseSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SwiftBrowserApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var syncManager: FirebaseSyncManager
        private set

    /** 快速拨号根文件夹的 ID，启动时自动创建 */
    var speedDialFolderId: Long = -1L
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        syncManager = FirebaseSyncManager(database.bookmarkDao())

        // 确保"快速拨号"根文件夹存在
        CoroutineScope(Dispatchers.IO).launch {
            ensureSpeedDialFolder()
        }
    }

    suspend fun ensureSpeedDialFolder() {
        val dao = database.bookmarkDao()
        val existing = dao.getRootFolderByName(Bookmark.SPEED_DIAL_FOLDER_NAME)
        if (existing != null) {
            speedDialFolderId = existing.id
        } else {
            speedDialFolderId = dao.insert(
                Bookmark(
                    title = Bookmark.SPEED_DIAL_FOLDER_NAME,
                    isFolder = true,
                    parentId = null,
                    position = 0
                )
            )
        }
    }

    companion object {
        lateinit var instance: SwiftBrowserApp
            private set
    }
}
