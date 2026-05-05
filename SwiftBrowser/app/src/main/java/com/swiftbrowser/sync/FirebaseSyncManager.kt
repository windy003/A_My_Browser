package com.swiftbrowser.sync

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.swiftbrowser.SwiftBrowserApp
import com.swiftbrowser.data.dao.BookmarkDao
import com.swiftbrowser.data.entity.Bookmark
import kotlinx.coroutines.tasks.await

class FirebaseSyncManager(
    private val bookmarkDao: BookmarkDao
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val userId: String?
        get() = auth.currentUser?.uid

    private fun bookmarksCollection() =
        firestore.collection("users").document(userId!!).collection("bookmarks")

    val isLoggedIn: Boolean
        get() = auth.currentUser != null


    // ==================== 上传（本地覆盖云端） ====================

    suspend fun uploadBookmarks() {
        val uid = userId ?: return
        val bookmarks = bookmarkDao.getAllList()

        // 先确保所有书签都有 firebaseId
        for (bookmark in bookmarks) {
            if (bookmark.firebaseId == null) {
                val docRef = bookmarksCollection().document()
                bookmarkDao.update(bookmark.copy(firebaseId = docRef.id))
            }
        }

        // 重新读取（现在所有记录都有 firebaseId 了）
        val allBookmarks = bookmarkDao.getAllList()
        val localIdToFirebaseId = allBookmarks.associate { it.id to it.firebaseId }
        val localFirebaseIds = allBookmarks.mapNotNull { it.firebaseId }.toSet()

        // 删除云端多余的文档（本地已删除的）
        val cloudSnapshot = bookmarksCollection().get().await()
        for (doc in cloudSnapshot.documents) {
            if (doc.id !in localFirebaseIds) {
                bookmarksCollection().document(doc.id).delete().await()
            }
        }

        // 批量写入本地书签到云端
        val chunks = allBookmarks.chunked(500)
        for (chunk in chunks) {
            val batch = firestore.batch()
            for (bookmark in chunk) {
                val parentFirebaseId = bookmark.parentId?.let { localIdToFirebaseId[it] }
                val data = hashMapOf(
                    "title" to bookmark.title,
                    "url" to bookmark.url,
                    "isFolder" to bookmark.isFolder,
                    "parentFirebaseId" to parentFirebaseId,
                    "position" to bookmark.position,
                    "favicon" to bookmark.favicon,
                    "createdAt" to bookmark.createdAt
                )
                batch.set(bookmarksCollection().document(bookmark.firebaseId!!), data)
            }
            batch.commit().await()
        }
    }

    // ==================== 下载（云端覆盖本地） ====================

    suspend fun downloadBookmarks() {
        val uid = userId ?: return
        val snapshot = bookmarksCollection().get().await()

        // 清空本地所有书签
        bookmarkDao.deleteAll()

        // 第一轮：插入所有云端书签（先不处理 parentId）
        val firebaseIdToLocalId = mutableMapOf<String, Long>()

        for (doc in snapshot.documents) {
            val newId = bookmarkDao.insert(
                Bookmark(
                    title = doc.getString("title") ?: "",
                    url = doc.getString("url"),
                    isFolder = doc.getBoolean("isFolder") ?: false,
                    position = doc.getLong("position")?.toInt() ?: 0,
                    favicon = doc.getString("favicon"),
                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                    firebaseId = doc.id
                )
            )
            firebaseIdToLocalId[doc.id] = newId
        }

        // 第二轮：修正 parentId（通过 parentFirebaseId 映射回本地 ID）
        for (doc in snapshot.documents) {
            val parentFirebaseId = doc.getString("parentFirebaseId")
            val localId = firebaseIdToLocalId[doc.id] ?: continue
            val localParentId = parentFirebaseId?.let { firebaseIdToLocalId[it] }
            bookmarkDao.moveTo(localId, localParentId)
        }

        // 确保有快速拨号文件夹
        SwiftBrowserApp.instance.ensureSpeedDialFolder()
    }


    // ==================== 完整同步 ====================

    suspend fun syncAll(): SyncResult {
        if (userId == null) return SyncResult.NotLoggedIn

        return try {
            uploadBookmarks()
            downloadBookmarks()
            // 同步后刷新快速拨号文件夹 ID
            SwiftBrowserApp.instance.ensureSpeedDialFolder()
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "同步失败")
        }
    }

    suspend fun deleteFromCloud(firebaseId: String) {
        if (firebaseId.isBlank()) return
        val uid = userId ?: return
        try {
            bookmarksCollection().document(firebaseId).delete().await()
        } catch (_: Exception) { }
    }
}

sealed class SyncResult {
    data object Success : SyncResult()
    data object NotLoggedIn : SyncResult()
    data class Error(val message: String) : SyncResult()
}
