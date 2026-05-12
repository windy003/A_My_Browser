package com.swiftbrowser.sync

import android.content.Context
import com.swiftbrowser.data.dao.BookmarkDao
import com.swiftbrowser.data.entity.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CloudSyncManager(
    private val bookmarkDao: BookmarkDao,
    private val context: Context
) {
    companion object {
        // 部署后替换为你的 Worker 地址
        const val BASE_URL = "https://bookmark-sync.mybrowser.workers.dev"
        private const val PREF_NAME = "cloud_sync_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    val isLoggedIn: Boolean
        get() = prefs.getString(KEY_TOKEN, null) != null

    val username: String?
        get() = prefs.getString(KEY_USERNAME, null)

    // ==================== 注册/登录 ====================

    suspend fun register(username: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        val response = post("/api/register", body.toString(), auth = false)
        handleAuthResponse(response)
    }

    suspend fun login(username: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        val response = post("/api/login", body.toString(), auth = false)
        handleAuthResponse(response)
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    // ==================== 获取云端书签列表（仅读取，不覆盖本地） ====================

    data class CloudBookmark(
        val id: Int,
        val title: String,
        val url: String?,
        val isFolder: Boolean,
        val parentIndex: Int?,
        val position: Int,
        val favicon: String?,
        val createdAt: Long
    )

    suspend fun fetchCloudBookmarks(): List<CloudBookmark> = withContext(Dispatchers.IO) {
        val response = get("/api/bookmarks/download")
        if (response.code != 200) {
            val error = try { JSONObject(response.body).optString("error", "获取失败") } catch (e: Exception) { "获取失败" }
            throw Exception(error)
        }

        val json = JSONObject(response.body)
        val bookmarksArray = json.getJSONArray("bookmarks")
        val result = mutableListOf<CloudBookmark>()

        for (i in 0 until bookmarksArray.length()) {
            val obj = bookmarksArray.getJSONObject(i)
            result.add(
                CloudBookmark(
                    id = obj.getInt("id"),
                    title = obj.optString("title", ""),
                    url = obj.optStringOrNull("url"),
                    isFolder = obj.optBoolean("isFolder", false),
                    parentIndex = if (obj.isNull("parentId")) null else obj.getInt("parentId"),
                    position = obj.optInt("position", 0),
                    favicon = obj.optStringOrNull("favicon"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        result
    }

    // ==================== 上传单条书签到云端 ====================

    suspend fun uploadSingleBookmark(bookmark: Bookmark, cloudParentId: Int? = null) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("title", bookmark.title)
            put("url", bookmark.url ?: JSONObject.NULL)
            put("isFolder", bookmark.isFolder)
            put("parentId", cloudParentId ?: JSONObject.NULL)
            put("position", bookmark.position)
            put("favicon", bookmark.favicon ?: JSONObject.NULL)
            put("createdAt", bookmark.createdAt)
        }
        val response = post("/api/bookmarks/add", body.toString(), auth = true)
        if (response.code != 200) {
            val error = try { JSONObject(response.body).optString("error", "上传失败") } catch (e: Exception) { "上传失败" }
            throw Exception(error)
        }
    }

    // ==================== 在云端创建文件夹，返回云端ID ====================

    suspend fun createCloudFolder(title: String, parentId: Int? = null): Int = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("title", title)
            put("url", JSONObject.NULL)
            put("isFolder", true)
            put("parentId", parentId ?: JSONObject.NULL)
            put("position", 0)
            put("favicon", JSONObject.NULL)
            put("createdAt", System.currentTimeMillis())
        }
        val response = post("/api/bookmarks/add", body.toString(), auth = true)
        if (response.code != 200) {
            val error = try { JSONObject(response.body).optString("error", "创建文件夹失败") } catch (e: Exception) { "创建文件夹失败" }
            throw Exception(error)
        }
        val json = JSONObject(response.body)
        json.getInt("id")
    }

    // ==================== 删除单条云端书签 ====================

    suspend fun deleteCloudBookmark(cloudId: Int) = withContext(Dispatchers.IO) {
        val response = delete("/api/bookmarks/$cloudId")
        if (response.code != 200) {
            val error = try { JSONObject(response.body).optString("error", "删除失败") } catch (e: Exception) { "删除失败" }
            throw Exception(error)
        }
    }

    // ==================== 网络请求 ====================

    private fun post(path: String, body: String, auth: Boolean): HttpResponse {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (auth) {
                setRequestProperty("Authorization", "Bearer ${getToken()}")
            }
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(conn)
    }

    private fun delete(path: String): HttpResponse {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer ${getToken()}")
            connectTimeout = 10000
            readTimeout = 10000
        }
        return readResponse(conn)
    }

    private fun get(path: String): HttpResponse {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer ${getToken()}")
            connectTimeout = 10000
            readTimeout = 10000
        }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): HttpResponse {
        val code = conn.responseCode
        val body = try {
            if (code in 200..299) conn.inputStream.bufferedReader().readText()
            else conn.errorStream?.bufferedReader()?.readText() ?: ""
        } finally {
            conn.disconnect()
        }
        return HttpResponse(code, body)
    }

    private fun getToken(): String {
        return prefs.getString(KEY_TOKEN, null) ?: throw Exception("未登录")
    }

    private fun handleAuthResponse(response: HttpResponse): AuthResult {
        val json = try { JSONObject(response.body) } catch (e: Exception) {
            return AuthResult.Error("服务器返回异常")
        }
        return if (response.code == 200) {
            val token = json.getString("token")
            val username = json.getString("username")
            prefs.edit().putString(KEY_TOKEN, token).putString(KEY_USERNAME, username).apply()
            AuthResult.Success(username)
        } else {
            AuthResult.Error(json.optString("error", "操作失败"))
        }
    }

    private data class HttpResponse(val code: Int, val body: String)
}

sealed class AuthResult {
    data class Success(val username: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

fun JSONObject.optStringOrNull(key: String): String? {
    return if (isNull(key)) null else optString(key, null)
}
