package com.carpiano;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * 偏好值安全層。
 *
 * <p>根因（v6.8 車機實測）：`in_chan` 一向用 {@code putInt(0/3)}，
 * 但藍牙動作「🔀 播放路線切換」誤用 {@code putBoolean}，
 * 之後任何 {@code getInt("in_chan")} 就拋
 * {@code ClassCastException: java.lang.Boolean cannot be cast to java.lang.Integer}
 * → 撳 tab 起 UI 直接 crash。</p>
 *
 * <p>規則：同一個 key 只准一種型別；讀取一律防禦，遇到舊壞值即場修復。</p>
 */
final class Prefs {

    static final String FILE = "piano";
    static final String KEY_CHAN = "in_chan";

    private Prefs() {
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 0 = 車內通知（BUS01）／3 = 車外（BUS12）。任何壞值一律回 0。 */
    static int chan(Context c) {
        try {
            return sp(c).getInt(KEY_CHAN, 0) == 3 ? 3 : 0;
        } catch (Throwable t) {
            return repairChan(c);
        }
    }

    static boolean outer(Context c) {
        return chan(c) == 3;
    }

    static void setChan(Context c, int mode) {
        try {
            sp(c).edit().putInt(KEY_CHAN, mode == 3 ? 3 : 0).apply();
        } catch (Throwable ignored) {
        }
    }

    private static int repairChan(Context c) {
        try {
            Object v = sp(c).getAll().get(KEY_CHAN);
            int m = (v instanceof Boolean && (Boolean) v) ? 3 : 0;
            setChan(c, m);
            return m;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 開機修復：所有本應係 int 嘅 key，遇到 boolean 就轉返（唔會刪用戶設定）。 */
    static void repair(Context c) {
        try {
            Map<String, ?> all = sp(c).getAll();
            Object chan = all.get(KEY_CHAN);
            if (chan != null && !(chan instanceof Integer)) {
                setChan(c, (chan instanceof Boolean && (Boolean) chan) ? 3 : 0);
            }
            for (String k : new String[]{"vol_pct", "park_raw", "drive_raw"}) {
                Object o = all.get(k);
                if (o != null && !(o instanceof Integer)) {
                    sp(c).edit().remove(k).apply();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
