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

    /** 获取某个文件夹下的子项，纯按 position 排序（文件夹/站点可混排，供快速拨号使用） */
    @Query("SELECT * FROM bookmarks WHERE parentId = :parentId ORDER BY position ASC")
    fun getChildrenByPosition(parentId: Long): LiveData<List<Bookmark>>

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

    /** 同一文件夹内，相同标题+相同URL的书签是否已存在 */
    @Query("SELECT * FROM bookmarks WHERE title = :title AND url = :url AND parentId = :parentId AND isFolder = 0 LIMIT 1")
    suspend fun findDuplicate(title: String, url: String, parentId: Long): Bookmark?

    /** 根目录下，相同标题+相同URL的书签是否已存在 */
    @Query("SELECT * FROM bookmarks WHERE title = :title AND url = :url AND parentId IS NULL AND isFolder = 0 LIMIT 1")
    suspend fun findDuplicateInRoot(title: String, url: String): Bookmark?

    /** 按名称找文件夹（用于查找"快速拨号"根文件夹） */
    @Query("SELECT * FROM bookmarks WHERE title = :name AND isFolder = 1 AND parentId IS NULL LIMIT 1")
    suspend fun getRootFolderByName(name: String): Bookmark?

    @Query("SELECT MAX(position) FROM bookmarks WHERE parentId = :parentId")
    suspend fun getMaxPosition(parentId: Long): Int?

    @Query("SELECT MAX(position) FROM bookmarks WHERE parentId IS NULL")
    suspend fun getMaxPositionRoot(): Int?

    /** 获取所有文件夹 */
    @Query("SELECT * FROM bookmarks WHERE isFolder = 1 ORDER BY parentId IS NOT NULL, title ASC")
    suspend fun getAllFolders(): List<Bookmark>

    // ==================== 搜索 ====================

    /** 在指定文件夹及其所有子文件夹中搜索书签（递归） */
    @Query("""
        WITH RECURSIVE folder_tree(id) AS (
            SELECT id FROM bookmarks WHERE id = :folderId
            UNION ALL
            SELECT b.id FROM bookmarks b JOIN folder_tree ft ON b.parentId = ft.id WHERE b.isFolder = 1
        )
        SELECT * FROM bookmarks
        WHERE (parentId IN (SELECT id FROM folder_tree) OR parentId = :folderId)
          AND isFolder = 0
          AND (title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    suspend fun searchInFolder(folderId: Long, query: String): List<Bookmark>

    /** 在根目录及其所有子文件夹中搜索书签（递归） */
    @Query("""
        WITH RECURSIVE folder_tree(id) AS (
            SELECT id FROM bookmarks WHERE parentId IS NULL AND isFolder = 1
            UNION ALL
            SELECT b.id FROM bookmarks b JOIN folder_tree ft ON b.parentId = ft.id WHERE b.isFolder = 1
        )
        SELECT * FROM bookmarks
        WHERE isFolder = 0
          AND (title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    suspend fun searchAll(query: String): List<Bookmark>

    // ==================== 增删改 ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark): Long

    @Update
    suspend fun update(bookmark: Bookmark)

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 批量更新位置（拖拽排序后一次性写入） */
    @Query("UPDATE bookmarks SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    /** 移动到某个文件夹 */
    @Query("UPDATE bookmarks SET parentId = :parentId WHERE id = :id")
    suspend fun moveTo(id: Long, parentId: Long?)

    /** 删除某文件夹下的所有子项（递归删除前先调用） */
    @Query("DELETE FROM bookmarks WHERE parentId = :parentId")
    suspend fun deleteChildrenOf(parentId: Long)

    @Query("DELETE FROM bookmarks")
    suspend fun deleteAll()
}
