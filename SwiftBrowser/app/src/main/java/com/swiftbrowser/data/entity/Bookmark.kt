package com.swiftbrowser.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String? = null,        // 文件夹时为 null
    val isFolder: Boolean = false,
    val parentId: Long? = null,     // null = 根目录
    val position: Int = 0,
    val favicon: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val firebaseId: String? = null
) {
    companion object {
        /** 快速拨号根文件夹的固定名称 */
        const val SPEED_DIAL_FOLDER_NAME = "快速拨号"
    }
}
