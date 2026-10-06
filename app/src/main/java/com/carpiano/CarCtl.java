package com.carpiano;

import android.content.Context;

import java.lang.reflect.Method;

/**
 * 車控寫入（反射 ECARX adaptapi，免權限）—— 只畀手勢 toggle 用。
 *
 * <p>ID 係車上實測確認過嘅：</p>
 * <ul>
 *   <li>哨兵模式 {@code 0x20240100}（實測可寫、讀返一致）</li>
 *   <li>全車氛圍燈 {@code 0x2A080600}（實測寫得入）</li>
 * </ul>
 *
 * <p>每次都係「讀 → 反轉 → 寫 → 350ms 後讀返核對」，唔會盲寫死值。</p>
 */
final class CarCtl {

    static final int SENTRY = 0x20240100;
    static final int AMBIENCE = 0x2A080600;

    private CarCtl() {
    }

    /** 讀值；讀唔到回 Integer.MIN_VALUE。 */
    static int read(Context c, int id) {
        Object fn = VehicleRead.carFunction(c);
        if (fn == null) return Integer.MIN_VALUE;
        try {
            Method g = find(fn, "getFunctionValue", 1);
            if (g == null) return Integer.MIN_VALUE;
            Object r = g.invoke(fn, id);
            return r instanceof Number ? ((Number) r).intValue() : Integer.MIN_VALUE;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    /** 寫值；回 true = setFunctionValue 冇拋錯（唔代表真係生效）。 */
    static boolean write(Context c, int id, int v) {
        Object fn = VehicleRead.carFunction(c);
        if (fn == null) return false;
        try {
            Method s = find(fn, "setFunctionValue", 2);
            if (s == null) return false;
            s.invoke(fn, id, v);
            return true;
        } catch (Throwable t) {
            android.util.Log.i("CarPianoBT", "寫 0x" + Integer.toHexString(id)
                    + " 失敗：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 切換開／關；回傳人類可讀結果（會 sleep，唔好喺 UI thread 叫）。 */
    static String toggle(Context c, int id, String name) {
        int now = read(c, id);
        if (now == Integer.MIN_VALUE) {
            return name + "：讀唔到";
        }
        int want = now == 1 ? 0 : 1;
        boolean sent = write(c, id, want);
        try {
            Thread.sleep(350);
        } catch (InterruptedException ignored) {
        }
        int after = read(c, id);
        boolean ok = after == want;
        return name + "：" + now + " → " + want + "｜讀返 " + after
                + (ok ? " ✅" : (sent ? " ❌（寫入回報成功但未生效）" : " ❌（寫入失敗）"));
    }

    private static Method find(Object o, String name, int argc) {
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        return null;
    }
}
