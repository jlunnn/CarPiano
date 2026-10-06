package com.carpiano;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * **Zeekr BLE 按鈕 → Piano 動作**（手勢觸發）。
 *
 * <p>由 {@link GestureMap} 綁定嘅 (動作, 手勢) 決定執行邊個；動作清單見
 * {@code GestureMap.ACTIONS}。全部出車外 {@code BUS12_OUTER_NOTIFY}（同鋼琴／錄音頁一致）。</p>
 */
public final class BtActions {

    private static final String PREF = "piano";

    private BtActions() {
    }

    public static void run(final Context ctx, String act) {
        final Context c = ctx.getApplicationContext();
        final AudioOut audio = new AudioOut(c);
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        switch (act == null ? "" : act) {
            case "play_p1":                            // 🔊 Preset 1
            case "play_p2":                            // 🔊 Preset 2
            case "play_p3": {                           // 🔊 Preset 3
                final int n = act.charAt(act.length() - 1) - '0';   // play_p1 → 1
                File f = presetFile(c, n);
                if (f == null) {
                    android.util.Log.i("CarPianoBT", "冇第 " + n + " 個 preset");
                    return;
                }
                Pcm.stopAll();
                final AudioDeviceInfo dev = audio.outer();
                Pcm.playFile(c, f, dev, AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.CONTENT_TYPE_SPEECH,
                        msg -> android.util.Log.i("CarPianoBT", "▶ " + f.getName() + "｜" + msg));
                return;
            }

            case "none":                                // 🚫 唔做嘢
                return;

            case "sentry_toggle":                       // 🔒 哨兵 開／關
                new Thread(() -> {
                    String r = CarCtl.toggle(c, CarCtl.SENTRY, "哨兵");
                    android.util.Log.i("CarPianoBT", "[手勢] " + r);
                }, "sentry").start();
                return;

            case "amb_toggle":                          // 💡 全車氛圍燈 開／關
                new Thread(() -> {
                    String r = CarCtl.toggle(c, CarCtl.AMBIENCE, "氣氛燈");
                    android.util.Log.i("CarPianoBT", "[手勢] " + r);
                }, "ambience").start();
                return;

            case "horn_out":                            // 📯 HORN 車外
                Pcm.stopAll();
                Pcm.playAsset(c, "horn.wav", audio.outer(), AudioAttributes.USAGE_MEDIA,
                        AudioAttributes.CONTENT_TYPE_SONIFICATION,
                        msg -> android.util.Log.i("CarPianoBT", "HORN｜" + msg));
                return;

            default:
        }
    }

    /** 第 n 個 preset（1-based）＝ 用戶手動指派嘅位 n（Record 頁設定）。 */
    private static File presetFile(Context c, int n) {
        return Slots.file(c, n);
    }

}
