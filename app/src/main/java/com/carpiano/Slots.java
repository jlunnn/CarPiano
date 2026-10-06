package com.carpiano;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Preset 1／2／3 手動指派位。
 *
 * <p>用戶要求：playlist 要顯示 1 2 3 嘅位置，可以將已錄音效手動放去 1／2／3。</p>
 *
 * <p>儲存：prefs {@code piano} 嘅 {@code slot_1/2/3} = 檔名（例：{@code rec-hello.wav}）。
 * 冇指派嘅位就係「未設定」，播放次序會自動跳去下一個錄音。</p>
 */
final class Slots {

    static final int COUNT = 3;
    private static final String PREF = "piano";
    private static final String KEY = "slot_";

    private Slots() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 位 n（1..3）嘅檔名；檔案唔見咗就自動清走，回 null。 */
    static String get(Context c, int n) {
        String v = sp(c).getString(KEY + n, null);
        if (v == null || v.isEmpty()) {
            return null;
        }
        File f = new File(c.getApplicationContext().getFilesDir(), v);
        if (!f.isFile()) {
            set(c, n, null);
            return null;
        }
        return v;
    }

    static void set(Context c, int n, String name) {
        SharedPreferences.Editor e = sp(c).edit();
        if (name == null || name.isEmpty()) {
            e.remove(KEY + n);
        } else {
            e.putString(KEY + n, name);
        }
        e.apply();
    }

    /** 放檔名入位 n；同一個檔唔會同時佔兩個位。 */
    static void put(Context c, int n, String name) {
        if (name == null) {
            set(c, n, null);
            return;
        }
        for (int i = 1; i <= COUNT; i++) {
            if (i != n && name.equals(get(c, i))) {
                set(c, i, null);
            }
        }
        set(c, n, name);
    }

    /** 位 n 嘅檔案；冇就 null。 */
    static File file(Context c, int n) {
        String v = get(c, n);
        return v == null ? null : new File(c.getApplicationContext().getFilesDir(), v);
    }

    /** 呢個檔放咗喺邊個位？冇就 0。 */
    static int slotOf(Context c, String name) {
        for (int i = 1; i <= COUNT; i++) {
            if (name != null && name.equals(get(c, i))) {
                return i;
            }
        }
        return 0;
    }

    /** 顯示用短名（去 rec- 同副檔名）。 */
    static String bare(String name) {
        return name.replace("rec-", "").replace(".wav", "")
                .replace(".m4a", "").replace(".mp3", "");
    }

    /** 全部錄音（新 → 舊）。 */
    static List<File> recs(Context c) {
        File[] fs = c.getFilesDir().listFiles((d, n) -> n.startsWith("rec-")
                && (n.endsWith(".wav") || n.endsWith(".m4a") || n.endsWith(".mp3")));
        List<File> out = new ArrayList<>();
        if (fs != null) {
            out.addAll(Arrays.asList(fs));
        }
        out.sort(Comparator.comparingLong(File::lastModified).reversed());
        return out;
    }

    /** 播放／格仔次序：位 1→2→3（有指派嘅）先，跟住其餘錄音（新 → 舊）。 */
    static List<File> ordered(Context c) {
        List<File> out = new ArrayList<>();
        for (int i = 1; i <= COUNT; i++) {
            File f = file(c, i);
            if (f != null) {
                out.add(f);
            }
        }
        for (File f : recs(c)) {
            if (!out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }
}
