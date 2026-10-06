package com.carpiano;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * App 內自更新（已驗證流程：查版本資料 → 比 versionCode → 下載 → 核 SHA-256 → 交系統安裝器）。
 *
 * <p>版本資料：{@code https://hesheeat.cc/download/car-version.json}
 * <pre>{"versionCode": 20, "versionName": "2.9", "url": "…apk", "sha256": "…", "size": 3124939}</pre>
 *
 * <p>流程：查資料 → 比 versionCode（唔用 versionName，格式變都唔怕）→ 有新版本就通知 UI 顯示提示
 * → 用戶撳「下載並更新」→ 自己用 HttpURLConnection 下載（唔靠 DownloadManager：車機容器未必有）
 * → 串流計 sha256 核對 → 驗 APK 版本 → 經 {@link ApkProvider}（content://）交系統安裝器。</p>
 *
 * <p>⚠️ 系統一定會彈「要唔要安裝」——Android 唔容許第三方 app 靜默裝 APK（除非係 device owner／root）。
 * 車機 App Lab 容器實測：安裝器彈出後 tap「返回」，唔好 tap「打開」。</p>
 */
final class Update {

    /** UI 回呼（全部喺背景 thread 叫，UI 要自己 runOnUiThread）。 */
    interface Listener {
        /** 進度／狀態文字。 */
        void onStatus(String s);

        /** 有新版本。 */
        void onFound(String versionName, long size);

        /** 已經係最新。 */
        void onUpToDate();

        /** 下載百分比（0–100）。 */
        void onProgress(int pct);

        /** 下載＋驗證完成，已交安裝器（或已另存）。 */
        void onInstalled(boolean handedToInstaller, String savedPath);
    }

    private static final String MANIFEST_URL = "https://hesheeat.cc/download/car-version.json";
    private static final String AUTHORITY = "com.carpiano.apks";

    private final Context ctx;
    private final Listener l;

    private String foundUrl = "";
    private String foundSha = "";
    private String foundName = "";

    Update(Context ctx, Listener l) {
        this.ctx = ctx;
        this.l = l;
    }

    /** 背景查版本；有新版本會叫 {@link Listener#onFound}。 */
    void check() {
        new Thread(() -> {
            try {
                status("檢查更新中…");
                JSONObject m = fetchJson(MANIFEST_URL);
                int remote = m.optInt("versionCode", 0);
                String name = m.optString("versionName", "?");
                String url = m.optString("url", "");
                String sha = m.optString("sha256", "");
                long size = m.optLong("size", 0);
                int cur = currentVersionCode();
                status("本機 v" + versionName() + "（code " + cur + "）／最新 v" + name + "（code " + remote + "）");
                if (remote <= cur) {
                    l.onUpToDate();
                    return;
                }
                if (url.isEmpty()) {
                    status("⚠️ 版本資料冇下載網址");
                    return;
                }
                foundUrl = url;
                foundSha = sha;
                foundName = name;
                l.onFound(name, size);
            } catch (Throwable t) {
                status("⚠️ 查更新失敗：" + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }, "update-check").start();
    }

    /** 下載＋驗證＋交安裝器（必須先 check() 搵到新版）。 */
    void downloadAndInstall() {
        final String url = foundUrl, sha = foundSha;
        if (url.isEmpty()) {
            status("⚠️ 未檢查到新版，請先檢查更新");
            return;
        }
        new Thread(() -> {
            File apk = null;
            try {
                apk = download(url, sha);
            } catch (Throwable t) {
                status("⚠️ 下載失敗：" + t.getClass().getSimpleName() + " " + t.getMessage());
            }
            if (apk == null) return;
            verifyApk(apk);
            boolean ok = tryInstallIntent(apk);
            if (!ok) {
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                            status("⚠️ 未開「安裝未知應用程式」→ 開設定頁，開完再撳一次");
                            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + ctx.getPackageName()));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(i);
                            l.onInstalled(false, apk.getAbsolutePath());
                            return;
                        }
                    } catch (Throwable ignored) {
                        // 容器冇呢個設定頁（車機實測）→ 繼續試落去
                    }
                }
                status("⬆ 新版已下載好，請手動安裝：" + apk.getName());
            }
            l.onInstalled(ok, apk.getAbsolutePath());
        }, "update-dl").start();
    }

    private void status(String s) {
        try {
            l.onStatus(s);
        } catch (Throwable ignored) {
        }
    }

    private int currentVersionCode() {
        try {
            return (int) ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    private String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int rc = c.getResponseCode();
            InputStream in = rc >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (rc >= 400) throw new IllegalStateException("HTTP " + rc);
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    /** 自己下載（邊寫邊計 sha256）；sha 唔對就刪檔當失敗。 */
    private File download(String url, String expectedSha) throws Exception {
        String name = url.substring(url.lastIndexOf('/') + 1);
        final File dst = new File(ctx.getFilesDir(), name);   // 私有目錄：ApkProvider 讀得到
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int rc = c.getResponseCode();
            if (rc >= 400) {
                status("⚠️ 下載 HTTP " + rc);
                return null;
            }
            final long total = c.getContentLength();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            int lastPct = -1;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            l.onProgress(pct);
                        }
                    }
                }
            }
            String hex = hex(md.digest());
            status("下載完成 " + got + " bytes｜sha256 " + (hex.equalsIgnoreCase(expectedSha) ? "✔ 一致" : "✘ 唔一致"));
            if (!expectedSha.isEmpty() && !hex.equalsIgnoreCase(expectedSha)) {
                status("⚠️ 中止：雜湊唔對");
                dst.delete();
                return null;
            }
            return dst;
        } finally {
            c.disconnect();
        }
    }

    /** 用 PackageManager 讀 APK 自己嘅版本，證明下載到嘅真係 APK。 */
    private void verifyApk(File apk) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null) {
                status("⚠️ 讀唔到 APK 資訊（唔似有效 APK）");
                return;
            }
            status("APK: " + pi.packageName + " v" + pi.versionName + " (code " + pi.versionCode + ")");
        } catch (Throwable t) {
            status("⚠️ 驗 APK 失敗：" + t.getClass().getSimpleName());
        }
    }

    private boolean tryInstallIntent(File apk) {
        try {
            Uri uri = Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
            status("✅ 已交系統安裝器 —— tap「返回」，唔好 tap「打開」");
            return true;
        } catch (Throwable t) {
            status("開安裝器失敗：" + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
