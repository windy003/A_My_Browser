package com.swiftbrowser.data.dao;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.swiftbrowser.data.entity.Bookmark;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class BookmarkDao_Impl implements BookmarkDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Bookmark> __insertionAdapterOfBookmark;

  private final EntityDeletionOrUpdateAdapter<Bookmark> __deletionAdapterOfBookmark;

  private final EntityDeletionOrUpdateAdapter<Bookmark> __updateAdapterOfBookmark;

  private final SharedSQLiteStatement __preparedStmtOfDeleteById;

  private final SharedSQLiteStatement __preparedStmtOfMoveTo;

  private final SharedSQLiteStatement __preparedStmtOfDeleteChildrenOf;

  private final SharedSQLiteStatement __preparedStmtOfDeleteAll;

  public BookmarkDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfBookmark = new EntityInsertionAdapter<Bookmark>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `bookmarks` (`id`,`title`,`url`,`isFolder`,`parentId`,`position`,`favicon`,`createdAt`,`firebaseId`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Bookmark entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getTitle());
        if (entity.getUrl() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getUrl());
        }
        final int _tmp = entity.isFolder() ? 1 : 0;
        statement.bindLong(4, _tmp);
        if (entity.getParentId() == null) {
          statement.bindNull(5);
        } else {
          statement.bindLong(5, entity.getParentId());
        }
        statement.bindLong(6, entity.getPosition());
        if (entity.getFavicon() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getFavicon());
        }
        statement.bindLong(8, entity.getCreatedAt());
        if (entity.getFirebaseId() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getFirebaseId());
        }
      }
    };
    this.__deletionAdapterOfBookmark = new EntityDeletionOrUpdateAdapter<Bookmark>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `bookmarks` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Bookmark entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfBookmark = new EntityDeletionOrUpdateAdapter<Bookmark>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `bookmarks` SET `id` = ?,`title` = ?,`url` = ?,`isFolder` = ?,`parentId` = ?,`position` = ?,`favicon` = ?,`createdAt` = ?,`firebaseId` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Bookmark entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getTitle());
        if (entity.getUrl() == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.getUrl());
        }
        final int _tmp = entity.isFolder() ? 1 : 0;
        statement.bindLong(4, _tmp);
        if (entity.getParentId() == null) {
          statement.bindNull(5);
        } else {
          statement.bindLong(5, entity.getParentId());
        }
        statement.bindLong(6, entity.getPosition());
        if (entity.getFavicon() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getFavicon());
        }
        statement.bindLong(8, entity.getCreatedAt());
        if (entity.getFirebaseId() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getFirebaseId());
        }
        statement.bindLong(10, entity.getId());
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM bookmarks WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfMoveTo = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE bookmarks SET parentId = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteChildrenOf = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM bookmarks WHERE parentId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM bookmarks";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final Bookmark bookmark, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfBookmark.insertAndReturnId(bookmark);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final Bookmark bookmark, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfBookmark.handle(bookmark);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final Bookmark bookmark, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfBookmark.handle(bookmark);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteById(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object moveTo(final long id, final Long parentId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfMoveTo.acquire();
        int _argIndex = 1;
        if (parentId == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindLong(_argIndex, parentId);
        }
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfMoveTo.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteChildrenOf(final long parentId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteChildrenOf.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, parentId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteChildrenOf.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteAll.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public LiveData<List<Bookmark>> getAll() {
    final String _sql = "SELECT * FROM bookmarks ORDER BY isFolder DESC, position ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"bookmarks"}, false, new Callable<List<Bookmark>>() {
      @Override
      @Nullable
      public List<Bookmark> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final List<Bookmark> _result = new ArrayList<Bookmark>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Bookmark _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _item = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getAllList(final Continuation<? super List<Bookmark>> $completion) {
    final String _sql = "SELECT * FROM bookmarks ORDER BY isFolder DESC, position ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<Bookmark>>() {
      @Override
      @NonNull
      public List<Bookmark> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final List<Bookmark> _result = new ArrayList<Bookmark>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Bookmark _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _item = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public LiveData<List<Bookmark>> getChildren(final long parentId) {
    final String _sql = "SELECT * FROM bookmarks WHERE parentId = ? ORDER BY isFolder DESC, position ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, parentId);
    return __db.getInvalidationTracker().createLiveData(new String[] {"bookmarks"}, false, new Callable<List<Bookmark>>() {
      @Override
      @Nullable
      public List<Bookmark> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final List<Bookmark> _result = new ArrayList<Bookmark>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Bookmark _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _item = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getChildrenList(final long parentId,
      final Continuation<? super List<Bookmark>> $completion) {
    final String _sql = "SELECT * FROM bookmarks WHERE parentId = ? ORDER BY isFolder DESC, position ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, parentId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<Bookmark>>() {
      @Override
      @NonNull
      public List<Bookmark> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final List<Bookmark> _result = new ArrayList<Bookmark>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Bookmark _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _item = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public LiveData<List<Bookmark>> getRootItems() {
    final String _sql = "SELECT * FROM bookmarks WHERE parentId IS NULL ORDER BY isFolder DESC, position ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return __db.getInvalidationTracker().createLiveData(new String[] {"bookmarks"}, false, new Callable<List<Bookmark>>() {
      @Override
      @Nullable
      public List<Bookmark> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final List<Bookmark> _result = new ArrayList<Bookmark>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Bookmark _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _item = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getRootItemsList(final Continuation<? super List<Bookmark>> $completion) {
    final String _sql = "SELECT * FROM bookmarks WHERE parentId IS NULL ORDER BY isFolder DESC, position ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<Bookmark>>() {
      @Override
      @NonNull
      public List<Bookmark> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final List<Bookmark> _result = new ArrayList<Bookmark>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Bookmark _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _item = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getById(final long id, final Continuation<? super Bookmark> $completion) {
    final String _sql = "SELECT * FROM bookmarks WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, id);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Bookmark>() {
      @Override
      @Nullable
      public Bookmark call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final Bookmark _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _result = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getByUrl(final String url, final Continuation<? super Bookmark> $completion) {
    final String _sql = "SELECT * FROM bookmarks WHERE url = ? AND isFolder = 0 LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, url);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Bookmark>() {
      @Override
      @Nullable
      public Bookmark call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final Bookmark _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _result = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getRootFolderByName(final String name,
      final Continuation<? super Bookmark> $completion) {
    final String _sql = "SELECT * FROM bookmarks WHERE title = ? AND isFolder = 1 AND parentId IS NULL LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, name);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Bookmark>() {
      @Override
      @Nullable
      public Bookmark call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "url");
          final int _cursorIndexOfIsFolder = CursorUtil.getColumnIndexOrThrow(_cursor, "isFolder");
          final int _cursorIndexOfParentId = CursorUtil.getColumnIndexOrThrow(_cursor, "parentId");
          final int _cursorIndexOfPosition = CursorUtil.getColumnIndexOrThrow(_cursor, "position");
          final int _cursorIndexOfFavicon = CursorUtil.getColumnIndexOrThrow(_cursor, "favicon");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final int _cursorIndexOfFirebaseId = CursorUtil.getColumnIndexOrThrow(_cursor, "firebaseId");
          final Bookmark _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpUrl;
            if (_cursor.isNull(_cursorIndexOfUrl)) {
              _tmpUrl = null;
            } else {
              _tmpUrl = _cursor.getString(_cursorIndexOfUrl);
            }
            final boolean _tmpIsFolder;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsFolder);
            _tmpIsFolder = _tmp != 0;
            final Long _tmpParentId;
            if (_cursor.isNull(_cursorIndexOfParentId)) {
              _tmpParentId = null;
            } else {
              _tmpParentId = _cursor.getLong(_cursorIndexOfParentId);
            }
            final int _tmpPosition;
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition);
            final String _tmpFavicon;
            if (_cursor.isNull(_cursorIndexOfFavicon)) {
              _tmpFavicon = null;
            } else {
              _tmpFavicon = _cursor.getString(_cursorIndexOfFavicon);
            }
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            final String _tmpFirebaseId;
            if (_cursor.isNull(_cursorIndexOfFirebaseId)) {
              _tmpFirebaseId = null;
            } else {
              _tmpFirebaseId = _cursor.getString(_cursorIndexOfFirebaseId);
            }
            _result = new Bookmark(_tmpId,_tmpTitle,_tmpUrl,_tmpIsFolder,_tmpParentId,_tmpPosition,_tmpFavicon,_tmpCreatedAt,_tmpFirebaseId);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getMaxPosition(final long parentId,
      final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT MAX(position) FROM bookmarks WHERE parentId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, parentId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @Nullable
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final Integer _tmp;
            if (_cursor.isNull(0)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getInt(0);
            }
            _result = _tmp;
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getMaxPositionRoot(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT MAX(position) FROM bookmarks WHERE parentId IS NULL";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @Nullable
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final Integer _tmp;
            if (_cursor.isNull(0)) {
              _tmp = null;
            } else {
              _tmp = _cursor.getInt(0);
            }
            _result = _tmp;
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
