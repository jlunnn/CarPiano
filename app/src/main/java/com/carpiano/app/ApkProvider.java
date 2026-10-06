package com.carpiano.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只服務 app 私有目錄入面嘅 .apk，俾系統安裝器讀（自更新用）。
 *
 * <p>唔用 androidx FileProvider：呢個專案冇 androidx 依賴（加咗就要落 AAR）。
 * 唔用 DownloadManager 嘅 content URI：車機係 App Lab 容器，未必有 downloads provider。</p>
 */
public class ApkProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.endsWith(".apk") || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad name: " + name);
        }
        File f = new File(getContext().getFilesDir(), name);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
