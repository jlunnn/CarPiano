package com.carpiano.app;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 只讀車廠車控信號（反射方式 → 免權限）。
 *
 * 點火／ACC 狀態：平台筆記記低 ACC 0x00200104、ON 0x00200105、DRIVING 0x00200107，
 * 對應嘅 function ID 係 0x20259000。
 *
 * ⚠️ 唔靠猜編碼：app 用「**學**」嘅方法 —— 停車時記住一個值、行車時記住另一個值，
 * 之後比較就得（見 AutoService）。
 */
public class VehicleRead {

    public static final long ID_IGNITION = 0x20259000L;

    private static Object carObj;
    private static Object fnObj;

    private VehicleRead() {
    }

    public static synchronized Object carFunction(Context ctx) {
        if (fnObj != null) return fnObj;
        try {
            if (carObj == null) {
                Class<?> c = Class.forName("com.ecarx.xui.adaptapi.car.Car");
                for (Method m : c.getMethods()) {
                    if (!m.getName().equals("create") || !Modifier.isStatic(m.getModifiers())) continue;
                    try {
                        Object o = m.invoke(null, ctx);
                        if (o != null) {
                            carObj = o;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (carObj == null) return null;

            for (String mn : new String[]{"getICarFunction", "getCarFunction", "getCarFunctionManager"}) {
                Object o = invokeNoArg(carObj, mn);
                if (o != null) {
                    fnObj = o;
                    return o;
                }
            }
            for (Method m : carObj.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || !m.getName().startsWith("get")) continue;
                if (!m.getReturnType().getSimpleName().toLowerCase().contains("carfunction")) continue;
                try {
                    Object o = m.invoke(carObj);
                    if (o != null) {
                        fnObj = o;
                        return o;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 讀一個 function value；讀唔到回 Integer.MIN_VALUE。 */
    public static int read(Context ctx, long id) {
        Object fn = carFunction(ctx);
        if (fn == null) return Integer.MIN_VALUE;
        try {
            Method g = findMethod(fn, "getFunctionValue", 1);
            if (g == null) return Integer.MIN_VALUE;
            Object r = g.invoke(fn, (int) id);
            return r instanceof Number ? ((Number) r).intValue() : Integer.MIN_VALUE;
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    public static String hex(int raw) {
        if (raw == Integer.MIN_VALUE) return "讀唔到";
        return "0x" + Integer.toHexString(raw).toUpperCase() + " (" + raw + ")";
    }

    static Object invokeNoArg(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method findMethod(Object target, String name, int params) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
