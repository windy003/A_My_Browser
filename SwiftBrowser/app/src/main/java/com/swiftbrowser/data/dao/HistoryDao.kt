package com.swiftbrowser.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.swiftbrowser.data.entity.History

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    fun getAll(): LiveData<List<History>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    suspend fun getAllList(): List<History>

    @Insert
    suspend fun insert(history: History)

    @Delete
    suspend fun delete(history: History)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("SELECT * FROM history WHERE url = :url ORDER BY visitedAt DESC LIMIT 1")
    suspend fun getByUrl(url: String): History?
}
