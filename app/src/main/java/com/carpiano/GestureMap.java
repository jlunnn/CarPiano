package com.carpiano;

import android.content.Context;
import android.view.KeyEvent;

/**
 * **手勢 → 動作**（v11.30）。
 *
 * <p>用戶要求：單擊／雙擊／三擊／長按，各自綁不同功能；不同 keycode 亦可各自綁。</p>
 *
 * <p>偵測：</p>
 * <ul>
 *   <li>按住 ≥ 1.5 秒 → <b>長按</b>（觸發後放開唔再算單擊）</li>
 *   <li>放開後等 320ms；期內再按 → 雙擊；三擊已取消（按三次唔會觸發任何動作）</li>
 * </ul>
 */
final class GestureMap {

    static final int SINGLE = 0;
    static final int DOUBLE = 1;
    static final int TRIPLE = 2;
    static final int LONG = 3;
    static final String[] GESTURE_NAMES = {"單擊", "雙擊", "三擊", "長按"};
    /** 可綁定嘅手勢（已取消三擊）。 */
    static final int[] GESTURES = {SINGLE, DOUBLE, LONG};
    /** 長按判定時間：按住 1.5 秒先算長按（用戶要求）。 */
    static final long LONG_MS = 1500L;

    /** 動作清單（Piano 版：播 preset／HORN，全部出車外）。
     *  已取消：①「車外按住講」②「車內／車外路線切換」——手勢唔會再誤觸廣播路線。 */
    static final String[] ACTIONS = {
            "play_p1", "play_p2", "play_p3", "horn_out",
            "sentry_toggle", "amb_toggle", "none",
    };
    static final String[] LABELS = {
            "🔊 播 Preset 1 · 車外",
            "🔊 播 Preset 2 · 車外",
            "🔊 播 Preset 3 · 車外",
            "📯 播 HORN · 車外",
            "🔒 哨兵 開／關",
            "💡 氣氛燈 開／關",
            "🚫 無（唔做嘢）",
    };

    private static final String PF = "gesture_map";

    private static android.content.SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PF, Context.MODE_PRIVATE);
    }

    /** 一次性遷移：舊 6 項清單（0=按住講, 4=路線切換, 5=HORN）→ 新 4 項。
     *  舊綁定保留：HORN 由 index 5 搬去 3；已取消嘅動作直接刪。 */
    static void migrate(Context c) {
        android.content.SharedPreferences p = sp(c);
        migrateV3(c, p);
        migrateV4(c, p);
    }

    /** v4：取消三擊 → 清走所有 m?_2 綁定。 */
    private static void migrateV4(Context c, android.content.SharedPreferences p) {
        if (p.getBoolean("map_v4", false)) {
            return;
        }
        android.content.SharedPreferences.Editor e = p.edit();
        for (int a = 0; a < ACTIONS.length; a++) {
            e.remove("m" + a + "_" + TRIPLE);
        }
        e.putBoolean("map_v4", true).apply();
        android.util.Log.i("CarPianoBT", "[手勢] 三擊已取消");
    }

    private static void migrateV3(Context c, android.content.SharedPreferences p) {
        if (p.getBoolean("map_v3", false)) {
            return;
        }
        android.content.SharedPreferences.Editor e = p.edit();
        for (int g = 0; g < GESTURE_NAMES.length; g++) {
            e.remove("m0_" + g);                                  // 車外按住講（取消）
            e.remove("m4_" + g);                                  // 路線切換（取消）
            int horn = p.getInt("m5_" + g, 0);
            e.remove("m5_" + g);
            if (horn != 0) {
                e.putInt("m3_" + g, horn);                        // 舊 HORN → 新 index 3
            }
        }
        e.putBoolean("map_v3", true).apply();
        android.util.Log.i("CarPianoBT", "[手勢] 清單已更新：取消按住講＋路線切換");
    }

    static int keyFor(Context c, int actionIdx, int gesture) {
        return sp(c).getInt("m" + actionIdx + "_" + gesture, 0);
    }

    static void bind(Context c, int actionIdx, int gesture, int keyCode) {
        // 同一組合只可以有一個動作 → 先清走舊嘅
        for (int a = 0; a < ACTIONS.length; a++) {
            if (a != actionIdx && keyFor(c, a, gesture) == keyCode) {
                sp(c).edit().remove("m" + a + "_" + gesture).apply();
            }
        }
        sp(c).edit().putInt("m" + actionIdx + "_" + gesture, keyCode).apply();
    }

    static void unbind(Context c, int actionIdx, int gesture) {
        sp(c).edit().remove("m" + actionIdx + "_" + gesture).apply();
    }

    static String label(Context c, int actionIdx, int gesture) {
        int k = keyFor(c, actionIdx, gesture);
        return k == 0 ? "未綁定" : KeyEvent.keyCodeToString(k) + " (" + k + ")";
    }

    /** 邊個動作佔用咗呢個 (keycode,gesture)？冇就 -1。 */
    static int owner(Context c, int keyCode, int gesture) {
        for (int a = 0; a < ACTIONS.length; a++) {
            if (keyFor(c, a, gesture) == keyCode) {
                return a;
            }
        }
        return -1;
    }

    /** 呢個 keycode 有冇任何綁定？冇 → 唔食個 key（等app正常打字）。 */
    static boolean hasAnyBinding(Context c, int keyCode) {
        for (int a = 0; a < ACTIONS.length; a++) {
            for (int gst = 0; gst < 4; gst++) {
                if (keyFor(c, a, gst) == keyCode) {
                    return true;
                }
            }
        }
        return false;
    }

    // ============================================================ 綁定捕捉

    private static volatile int pendA = -1;
    private static volatile int pendG = -1;

    static void startCapture(int actionIdx, int gesture) {
        pendA = actionIdx;
        pendG = gesture;
    }

    static int pendingAction() {
        return pendA;
    }

    static int pendingGesture() {
        return pendG;
    }

    /** 捕捉模式：下一個按鍵就綁（action, gesture）。 */
    static boolean captureIfPending(Context ctx, KeyEvent e) {
        if (pendA < 0 || e == null || e.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        bind(ctx, pendA, pendG, e.getKeyCode());
        android.util.Log.i("CarPianoBT", "[手勢] 綁定 " + LABELS[pendA] + " ← "
                + KeyEvent.keyCodeToString(e.getKeyCode()) + " " + GESTURE_NAMES[pendG]);
        pendA = -1;
        pendG = -1;
        return true;
    }

    // ============================================================ 手勢偵測

    private static final android.os.Handler H =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static int pendingKey = 0;
    private static int clicks = 0;
    private static long downAt = 0;
    private static boolean longFired = false;
    private static Runnable clickEnd;
    private static Runnable longRun;

    /** 由 Activity.dispatchKeyEvent 叫。回 true = 已處理（consume）。 */
    static boolean onKey(final Context ctx, final KeyEvent e, final Runnable onChange) {
        if (e == null) {
            return false;
        }
        final int kc = e.getKeyCode();
        if (!hasAnyBinding(ctx, kc)) {
            return false;   // 呢個鍵冇綁定 → 唔食
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) {
                return true;   // 按住重複 → 食掉
            }
            downAt = System.currentTimeMillis();
            longFired = false;
            if (onChange != null) {
                onChange.run();
            }
            // 長按計時
            final int kcF = kc;
            longRun = () -> {
                longFired = true;
                fire(ctx, kcF, LONG);
            };
            H.postDelayed(longRun, LONG_MS);
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            H.removeCallbacks(longRun);
            long held = System.currentTimeMillis() - downAt;
            if (longFired || held >= LONG_MS) {
                clicks = 0;
                return true;
            }
            clicks++;
            final int kcF = kc;
            if (clickEnd != null) {
                H.removeCallbacks(clickEnd);
            }
            clickEnd = () -> {
                int n = clicks;
                clicks = 0;
                if (n >= 3) {
                    return;             // 三擊已取消 → 唔做任何動作
                }
                fire(ctx, kcF, n == 2 ? DOUBLE : SINGLE);
            };
            H.postDelayed(clickEnd, 320);
            return true;
        }
        return false;
    }

    private static void fire(Context ctx, int keyCode, int gesture) {
        int a = owner(ctx, keyCode, gesture);
        if (a < 0) {
            // Beep.tick();  // 鋼琴版冇 Beep，靜音（手勢照樣觸發）
            return;   // 冇綁定 → 唔做嘢
        }
        BtActions.run(ctx, ACTIONS[a]);
        android.util.Log.i("CarPianoBT", "[手勢] " + KeyEvent.keyCodeToString(keyCode) + " "
                + GESTURE_NAMES[gesture] + " → " + LABELS[a]);
        // Beep.tick();  // 鋼琴版冇 Beep，靜音（手勢照樣觸發）
    }
}
