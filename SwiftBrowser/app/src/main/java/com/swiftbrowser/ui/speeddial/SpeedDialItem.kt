package com.swiftbrowser.ui.speeddial

import com.swiftbrowser.data.entity.Bookmark

/**
 * 快速拨号网格中的项：书签（网址）或文件夹
 * 数据来源于书签表中"快速拨号"文件夹下的子项
 */
sealed class SpeedDialItem {

    data class Site(val bookmark: Bookmark) : SpeedDialItem()

    data class Folder(
        val folder: Bookmark,          // isFolder = true 的书签
        val children: List<Bookmark>   // 文件夹内的子书签
    ) : SpeedDialItem()
}
