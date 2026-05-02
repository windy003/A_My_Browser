package com.swiftbrowser.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.swiftbrowser.data.entity.Bookmark

@Dao
interface BookmarkDao {

    // ==================== 通用查询 ====================

    @Query("SELECT * FROM bookmarks ORDER BY isFolder DESC, position ASC")
    fun getAll(): LiveData<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY isFolder DESC, position ASC")
    suspend fun getAllList(): List<Bookmark>

    /** 获取某个文件夹下的子项（文件夹排前面） */
    @Query("SELECT * FROM bookmarks WHERE parentId = :parentId ORDER BY isFolder DESC, position ASC")
    fun getChildren(parentId: Long): LiveData<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE parentId = :parentId ORDER BY isFolder DESC, position ASC")
    suspend fun getChildrenList(parentId: Long): List<Bookmark>

    /** 获取根目录的子项 */
    @Query("SELECT * FROM bookmarks WHERE parentId IS NULL ORDER BY isFolder DESC, position ASC")
    fun getRootItems(): LiveData<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE parentId IS NULL ORDER BY isFolder DESC, position ASC")
    suspend fun getRootItemsList(): List<Bookmark>

    // ==================== 特定查询 ====================

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: Long): Bookmark?

    @Query("SELECT * FROM bookmarks WHERE url = :url AND isFolder = 0 LIMIT 1")
    suspend fun getByUrl(url: String): Bookmark?

    /** 按名称找文件夹（用于查找"快速拨号"根文件夹） */
    @Query("SELECT * FROM bookmarks WHERE title = :name AND isFolder = 1 AND parentId IS NULL LIMIT 1")
    suspend fun getRootFolderByName(name: String): Bookmark?

    @Query("SELECT MAX(position) FROM bookmarks WHERE parentId = :parentId")
    suspend fun getMaxPosition(parentId: Long): Int?

    @Query("SELECT MAX(position) FROM bookmarks WHERE parentId IS NULL")
    suspend fun getMaxPositionRoot(): Int?

    // ==================== 增删改 ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 移动到某个文件夹 */
    @Query("UPDATE bookmarks SET parentId = :parentId WHERE id = :id")
    suspend fun moveTo(id: Long, parentId: Long?)

    /** 删除某文件夹下的所有子项（递归删除前先调用） */
    @Query("DELETE FROM bookmarks WHERE parentId = :parentId")
    suspend fun deleteChildrenOf(parentId: Long)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
}
