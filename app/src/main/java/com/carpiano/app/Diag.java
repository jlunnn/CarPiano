package com.carpiano.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 本機診斷記錄。
 *
 * <p>⚠️ 私隱：呢個 app **唔會**將任何資料上傳／聯網。
 * 所有診斷文字只寫落 app 私人目錄（{@code files/diag-*.txt}，最多保留 10 份），
 * 用戶可以自己喺介面睇（🩺 自檢）、自己決定要唔要分享。</p>
 *
 * <p>用途：側載 app 冇 logcat 時，出事可以喺 app 內睇返發生咩事。</p>
 */
public class Diag {

    private static final int KEEP = 10;

    private Diag() {
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    public static String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** 記一段診斷文字（只存本機）。 */
    public static void send(Context ctx, String tag, String body) {
        final Context c = ctx.getApplicationContext();
        final String name = "diag-" + stamp() + "-" + tag + ".txt";
        final String text = header(c, tag) + "\n" + body + "\n";
        try {
            saveLocal(c, name, text);
        } catch (Throwable ignored) {
        }
    }

    private static String header(Context c, String tag) {
        String ver = "?";
        try {
            ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        return "Car Piano 診斷記錄\n"
                + "tag      : " + tag + "\n"
                + "time     : " + nowText() + "\n"
                + "version  : v" + ver + "\n"
                + "device   : " + android.os.Build.MODEL + " / RELEASE " + android.os.Build.VERSION.RELEASE
                + " / SDK " + android.os.Build.VERSION.SDK_INT + "\n"
                + "abi      : " + Arrays.toString(android.os.Build.SUPPORTED_ABIS) + "\n";
    }

    private static void saveLocal(Context c, String name, String text) throws Exception {
        File f = new File(c.getFilesDir(), name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(text.getBytes("UTF-8"));
        }
        File[] all = c.getFilesDir().listFiles((d, n) -> n.startsWith("diag-"));
        if (all != null && all.length > KEEP) {
            Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < all.length - KEEP; i++) all[i].delete();
        }
    }

    /** 上次崩潰（如果 uncaught handler 寫過）。 */
    public static String lastCrash(Context c) {
        try {
            File[] all = c.getFilesDir().listFiles((d, n) -> n.startsWith("crash-"));
            if (all == null || all.length == 0) return null;
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            try (InputStream in = new java.io.FileInputStream(all[0])) {
                return new String(readAll(in), "UTF-8");
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public static void saveCrash(Context c, String trace) {
        try {
            File f = new File(c.getFilesDir(), "crash-" + stamp() + ".txt");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(trace.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
