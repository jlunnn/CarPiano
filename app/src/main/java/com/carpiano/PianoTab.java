package com.carpiano;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Tab 1：🎹 鋼琴鍵盤（只出 🚗 車外 speaker）。
 *
 * v1.8：① 介面唔再顯示 BUS12 字眼，一律叫「車外 speaker」；
 *       ② 琴鍵加大＋每條鍵都有邊框／間隔，音符名清楚；
 *       ③ 琴音正規化 → 大聲又唔爆。
 */
public class PianoTab {

    interface Logger {
        void out(String s);
    }

    private static final String[] WHITE_NAME = {"C", "D", "E", "F", "G", "A", "B"};
    private static final int[] WHITE_SEMI = {0, 2, 4, 5, 7, 9, 11};
    private static final String[] BLACK_NAME = {"C#", "D#", "F#", "G#", "A#"};
    private static final int[] BLACK_SEMI = {1, 3, 6, 8, 10};
    /** 黑鍵跟喺邊個白鍵後面（同 octave 內 index）。 */
    private static final int[] BLACK_AFTER = {0, 1, 3, 4, 5};

    private final Context ctx;
    private final AudioOut audio;
    private final Logger log;

    private com.google.android.material.slider.Slider volBar;
    private LinearLayout keyBox;
    private int baseOctave = 4;   // 起始八度
    private int octaves = 2;
    private boolean firstNoteReported;

    public PianoTab(Context ctx, AudioOut audio, Logger log) {
        this.ctx = ctx;
        this.audio = audio;
        this.log = log;
    }

    /** 全版鋼琴頁：琴鍵佔 80% 版面（用戶 2026-10-07 要求）；音量／路線已搬去頂欄。 */
    public View buildUi() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        col.setPadding(pad, pad, pad, pad);

        keyBox = new LinearLayout(ctx);
        keyBox.setOrientation(LinearLayout.VERTICAL);
        rebuildKeys();
        col.addView(keyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f));      // ← 80% 版面

        LinearLayout octRow = new LinearLayout(ctx);
        octRow.setOrientation(LinearLayout.HORIZONTAL);
        octRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView octInfo = new TextView(ctx);
        Theme.label(octInfo, Theme.TEXT_DIM, 12f);
        octInfo.setText("八度 " + baseOctave);
        Button down = Theme.button(ctx);
        down.setText("−");
        down.setTag("skip");
        Theme.secondary(down, ctx);
        down.setOnClickListener(v -> {
            if (baseOctave > 2) {
                baseOctave--;
                octInfo.setText("八度 " + baseOctave);
                rebuildKeys();
                log.out("八度：" + baseOctave);
            }
        });
        Button up = Theme.button(ctx);
        up.setText("＋");
        up.setTag("skip");
        Theme.secondary(up, ctx);
        up.setOnClickListener(v -> {
            if (baseOctave < 6) {
                baseOctave++;
                octInfo.setText("八度 " + baseOctave);
                rebuildKeys();
                log.out("八度：" + baseOctave);
            }
        });
        octRow.addView(down, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(10);
        octRow.addView(octInfo, ilp);
        octRow.addView(up, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(8);
        col.addView(octRow, olp);
        return col;
    }

    private void rebuildKeys() {
        keyBox.removeAllViews();
        keyBox.addView(buildBlackRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.40f));
        keyBox.addView(buildWhiteRow(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.60f));
    }

    /** 白鍵：每條都有深色邊框 + 2dp 間隔 → 一條條分得清楚。 */
    private View buildWhiteRow() {
        LinearLayout whiteRow = new LinearLayout(ctx);
        whiteRow.setOrientation(LinearLayout.HORIZONTAL);
        int n = octaves * WHITE_NAME.length;
        int gap = dp(2);
        for (int o = 0; o < octaves; o++) {
            for (int i = 0; i < WHITE_NAME.length; i++) {
                final int semi = (baseOctave + o) * 12 + WHITE_SEMI[i];
                final String nm = WHITE_NAME[i] + (baseOctave + o);
                Button w = Theme.button(ctx);
                w.setText(nm);
                w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                w.setTextColor(Color.parseColor("#111111"));
                w.setAllCaps(false);
                w.setTag("key");
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                w.setPadding(0, 0, 0, dp(10));
                w.setBackground(whiteKeyBg());
                w.setOnClickListener(v -> play(semi, nm));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(i == 0 && o == 0 ? 0 : gap / 2, 0, gap / 2, 0);
                whiteRow.addView(w, lp);
            }
        }
        return whiteRow;
    }

    /** 黑鍵：坐喺兩個白鍵中間，一樣有邊框。 */
    private View buildBlackRow() {
        LinearLayout blackRow = new LinearLayout(ctx);
        blackRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int o = 0; o < octaves; o++) {
            for (int i = 0; i < WHITE_NAME.length; i++) {
                LinearLayout slot = new LinearLayout(ctx);
                slot.setOrientation(LinearLayout.HORIZONTAL);
                int blackIdx = -1;
                for (int b = 0; b < BLACK_AFTER.length; b++) {
                    if (BLACK_AFTER[b] == i) blackIdx = b;
                }
                if (blackIdx >= 0) {
                    View left = new View(ctx);
                    slot.addView(left, new LinearLayout.LayoutParams(0, 1, 0.58f));
                    Button bk = Theme.button(ctx);
                    bk.setText("");                       // 黑鍵唔寫字，睇顏色就知
                    bk.setAllCaps(false);
                    bk.setTag("key");
                    bk.setBackground(blackKeyBg());
                    final int semiB = (baseOctave + o) * 12 + BLACK_SEMI[blackIdx];
                    final String bname = BLACK_NAME[blackIdx] + (baseOctave + o);
                    bk.setOnClickListener(v -> play(semiB, bname));
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.84f);
                    blp.setMargins(dp(1), 0, dp(1), 0);
                    slot.addView(bk, blp);
                    View right = new View(ctx);
                    slot.addView(right, new LinearLayout.LayoutParams(0, 1, 0.58f));
                } else {
                    View sp = new View(ctx);
                    slot.addView(sp, new LinearLayout.LayoutParams(0, 1, 2f));
                }
                blackRow.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }
        return blackRow;
    }

    private GradientDrawable whiteKeyBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F7F7F9"));
        g.setCornerRadius(dp(5));
        g.setStroke(dp(2), Color.parseColor("#3A3A3A"));
        return g;
    }

    private GradientDrawable blackKeyBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#15171C"));
        g.setCornerRadius(dp(4));
        g.setStroke(dp(2), Color.parseColor("#9AA0A6"));
        return g;
    }

    /** semi：MIDI 半音（60 = C4）。 */
    private void play(int semi, String label) {
        double freq = 440.0 * Math.pow(2.0, (semi - 69) / 12.0);
        AudioDeviceInfo dev = dev();
        audio.playNote(freq, 1400, dev, 1.0f, usage());
        log.out("🎹 " + label + "  " + String.format(java.util.Locale.US, "%.1f Hz", freq)
                + "  → " + (dev == null ? "預設輸出" : (routeOuter() ? "車外 speaker（BUS12）" : "車內（通知通道 BUS01）")));
        if (!firstNoteReported) {
            firstNoteReported = true;
            Diag.send(ctx, "piano-note",
                    "第一個琴音：" + label + " " + String.format(java.util.Locale.US, "%.1f Hz", freq)
                            + "\n要嘅裝置：" + Pcm.devDesc(dev));
        }
    }

    /** 只用車外 speaker。 */
    /** 跟頂欄「播放來源」：車外 = BUS12／車內 = 通知通道（BUS01）。 */
    private boolean routeOuter() {
        return ctx.getSharedPreferences("piano", android.content.Context.MODE_PRIVATE)
                .getInt("in_chan", 0) == 3;
    }

    private AudioDeviceInfo dev() {
        if (routeOuter()) return audio.outer();
        AudioDeviceInfo d = audio.notifyBus() != null ? audio.notifyBus() : audio.inner();
        return d;
    }

    private int usage() {
        return routeOuter() ? android.media.AudioAttributes.USAGE_MEDIA
                : android.media.AudioAttributes.USAGE_NOTIFICATION;
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
