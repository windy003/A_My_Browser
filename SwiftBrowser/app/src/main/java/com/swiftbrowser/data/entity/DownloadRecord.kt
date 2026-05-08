package com.swiftbrowser.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_records")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val url: String,
    val mimeType: String?,
    val createdAt: Long = System.currentTimeMillis()
)
