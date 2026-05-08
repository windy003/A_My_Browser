package com.swiftbrowser.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.swiftbrowser.data.entity.DownloadRecord

@Dao
interface DownloadRecordDao {

    @Query("SELECT * FROM download_records ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<DownloadRecord>>

    @Insert
    suspend fun insert(record: DownloadRecord)

    @Delete
    suspend fun delete(record: DownloadRecord)

    @Query("DELETE FROM download_records")
    suspend fun deleteAll()
}
