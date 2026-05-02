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

    // ==================== 上传 ====================

    suspend fun uploadBookmarks() {
        val uid = userId ?: return
        val bookmarks = bookmarkDao.getAllList()

        // 建立 本地ID → firebaseId 的映射，用于转换 parentId
        // 先确保所有书签都有 firebaseId
        for (bookmark in bookmarks) {
            if (bookmark.firebaseId == null) {
                val docRef = bookmarksCollection().add(hashMapOf<String, Any?>()).await()
                bookmarkDao.update(bookmark.copy(firebaseId = docRef.id))
            }
        }

        // 重新读取（现在所有记录都有 firebaseId 了）
        val allBookmarks = bookmarkDao.getAllList()
        val localIdToFirebaseId = allBookmarks.associate { it.id to it.firebaseId }

        for (bookmark in allBookmarks) {
            // parentId 转换为 firebase 端的 ID
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

            bookmarksCollection().document(bookmark.firebaseId!!).set(data).await()
        }
    }

    // ==================== 下载 ====================

    suspend fun downloadBookmarks() {
        val uid = userId ?: return
        val snapshot = bookmarksCollection().get().await()

        val localBookmarks = bookmarkDao.getAllList()
        val localFirebaseIds = localBookmarks.mapNotNull { it.firebaseId }.toSet()

        // 第一轮：插入或更新（先不处理 parentId）
        val firebaseIdToLocalId = mutableMapOf<String, Long>()

        // 记录已有的映射
        for (local in localBookmarks) {
            if (local.firebaseId != null) {
                firebaseIdToLocalId[local.firebaseId] = local.id
            }
        }

        for (doc in snapshot.documents) {
            if (doc.id in localFirebaseIds) {
                val local = localBookmarks.first { it.firebaseId == doc.id }
                bookmarkDao.update(
                    local.copy(
                        title = doc.getString("title") ?: local.title,
                        url = doc.getString("url"),
                        isFolder = doc.getBoolean("isFolder") ?: local.isFolder,
                        position = doc.getLong("position")?.toInt() ?: local.position,
                        favicon = doc.getString("favicon")
                    )
                )
                firebaseIdToLocalId[doc.id] = local.id
            } else {
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
        }

        // 第二轮：修正 parentId（通过 parentFirebaseId 映射回本地 ID）
        for (doc in snapshot.documents) {
            val parentFirebaseId = doc.getString("parentFirebaseId")
            val localId = firebaseIdToLocalId[doc.id] ?: continue
            val localParentId = parentFirebaseId?.let { firebaseIdToLocalId[it] }
            bookmarkDao.moveTo(localId, localParentId)
        }

        // 去重：确保只有一个"快速拨号"根文件夹
        deduplicateSpeedDialFolder()
    }

    /** 确保只有一个"快速拨号"根文件夹，多余的合并 */
    private suspend fun deduplicateSpeedDialFolder() {
        val all = bookmarkDao.getRootItemsList()
        val sdFolders = all.filter {
            it.isFolder && it.title == Bookmark.SPEED_DIAL_FOLDER_NAME
        }
        if (sdFolders.size <= 1) return

        // 保留第一个，其余的子项移入第一个，然后删除多余的
        val keep = sdFolders.first()
        for (i in 1 until sdFolders.size) {
            val dup = sdFolders[i]
            val children = bookmarkDao.getChildrenList(dup.id)
            for (child in children) {
                bookmarkDao.moveTo(child.id, keep.id)
            }
            bookmarkDao.delete(dup)
        }
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
        val uid = userId ?: return
        try {
            bookmarksCollection().document(firebaseId).delete().await()
        } catch (_: Exception) {
        }
    }
}

sealed class SyncResult {
    data object Success : SyncResult()
    data object NotLoggedIn : SyncResult()
    data class Error(val message: String) : SyncResult()
}
