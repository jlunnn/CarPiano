package com.carpiano;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * **常駐監聽**（v11.86）—— 開 app 即刻開始，唔使開藍牙 tab。
 *
 * <p>做法：只監聽廣播（唔連接），parse 出 `FF xx yy`，
 * 用第 3 個 byte 判定手勢（00 單擊／02 雙擊／01 長按），
 * 再執行用戶喺「手勢 → 動作」揀嘅動作。</p>
 */
final class AdvButton {

    private static final String PF = "bt_bound";
    private static final String GP = "gest_act";

    static volatile boolean running;
    static volatile String state = "未啟動";
    static volatile String lastCode = "";
    static volatile int count;
    static volatile long lastAt;
    private static String boundMac = "";
    /** stop() 冇 ctx 用（原版靠 App.ctx）。 */
    static Context appCtx;
    private static String lastFired = "";

    static String boundMac(Context ctx) {
        try {
            return ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).getString("mac", "");
        } catch (Throwable t) {
            return "";
        }
    }

    static final String PREF_ON = "adv_on";

    /** 用戶係唔係想常駐開（預設 true；撳停就記住 false）。 */
    static boolean wanted(Context ctx) {
        try {
            return ctx.getSharedPreferences("bt_bound", Context.MODE_PRIVATE)
                    .getBoolean(PREF_ON, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setWanted(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences("bt_bound", Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_ON, on).apply();
        } catch (Throwable ignored) {
        }
    }

    static void start(final Context ctx) {
        if (running) {
            return;
        }
        final String mac = boundMac(ctx);
        if (mac == null || mac.isEmpty()) {
            state = "未綁定";
            return;
        }
        boundMac = mac;
        appCtx = ctx.getApplicationContext();
        setWanted(ctx, true);
        lastCode = "";
        lastFired = "";
        state = "● 常駐監聽中";
        running = true;
        BleProbe.startRaw(ctx, mac, 0, (m, name, rssi, raw, at) -> {
            if (!running) {
                return;
            }
            String code = codeOf(raw);
            if (code.equals(lastCode)) {
                return;
            }
            lastCode = code;
            count++;
            lastAt = System.currentTimeMillis();
            final int g = gestureOf(code);
            state = GestureMap.GESTURE_NAMES[g] + "  " + code;
            if (!code.equals(lastFired)) {
                lastFired = code;
                int a = action(ctx, g);
                if (a >= 0) {
                    BtActions.run(ctx, GestureMap.ACTIONS[a]);
                    android.util.Log.i("CarPianoBT", "[常駐] " + GestureMap.GESTURE_NAMES[g]
                            + " " + code + " → " + GestureMap.LABELS[a]);
                }
            }
        });
    }

    static void stop() {
        if (appCtx != null) setWanted(appCtx, false);
        running = false;
        state = "⏹ 已停";
        BleProbe.stopRaw();
    }

    static int action(Context c, int g) {
        try {
            return c.getSharedPreferences(GP, Context.MODE_PRIVATE).getInt("g" + g, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    static String codeOf(String raw) {
        try {
            String[] p = raw.trim().split(" ");
            for (int i = 0; i + 2 < p.length; i++) {
                if (p[i].equalsIgnoreCase("FF")) {
                    return "FF " + p[i + 1] + " " + p[i + 2];
                }
            }
            if (p.length >= 8) {
                return "FF " + p[6] + " " + p[7];
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    static int gestureOf(String code) {
        try {
            String[] p = code.trim().split(" ");
            int t = Integer.parseInt(p[p.length - 1], 16);
            switch (t) {
                case 0x00: return GestureMap.SINGLE;
                case 0x02: return GestureMap.DOUBLE;
                case 0x01: return GestureMap.LONG;
                default: return GestureMap.SINGLE;
            }
        } catch (Throwable ignored) {
            return GestureMap.SINGLE;
        }
    }
}
