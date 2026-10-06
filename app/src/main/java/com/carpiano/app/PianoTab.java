package com.carpiano.app;

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

    private SeekBar volBar;
    private LinearLayout keyBox;
    private int baseOctave = 4;   // 起始八度
    private int octaves = 2;
    private boolean firstNoteReported;

    public PianoTab(Context ctx, AudioOut audio, Logger log) {
        this.ctx = ctx;
        this.audio = audio;
        this.log = log;
    }

    public View buildUi() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        col.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(ctx);
        Theme.title(t, 15f);
        t.setText("🎹 鋼琴 — 聲音由 🚗 車外 speaker 出");
        col.addView(t);

        TextView where = new TextView(ctx);
        Theme.label(where, audio.outer() == null ? Theme.DANGER : Theme.OK, 11f);
        where.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_spk_outside, 0, 0, 0);
        where.setCompoundDrawablePadding(Theme.dp(ctx, 6));
        where.setText(audio.outer() == null ? "搵唔到車外 —— 會改用預設輸出" : "車外就緒");
        col.addView(where);

        LinearLayout volRow = new LinearLayout(ctx);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView vl = new TextView(ctx);
        vl.setText("音量 ");
        volRow.addView(vl);
        volBar = new SeekBar(ctx);
        volBar.setMax(audio.maxMusicVolume());
        volBar.setProgress(Math.max(1, audio.maxMusicVolume() * 60 / 100));
        volBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                audio.setVolume(Math.max(1, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        volRow.addView(volBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(volRow);

        LinearLayout octRow = new LinearLayout(ctx);
        octRow.setOrientation(LinearLayout.HORIZONTAL);
        Button down = new Button(ctx);
        down.setText("− 八度");
        down.setOnClickListener(v -> {
            if (baseOctave > 2) {
                baseOctave--;
                rebuildKeys();
                log.out("八度：" + baseOctave);
            }
        });
        octRow.addView(down, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button up = new Button(ctx);
        up.setText("＋ 八度");
        up.setOnClickListener(v -> {
            if (baseOctave < 6) {
                baseOctave++;
                rebuildKeys();
                log.out("八度：" + baseOctave);
            }
        });
        octRow.addView(up, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(octRow);

        keyBox = new LinearLayout(ctx);
        keyBox.setOrientation(LinearLayout.VERTICAL);
        rebuildKeys();
        col.addView(keyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void rebuildKeys() {
        keyBox.removeAllViews();
        keyBox.addView(buildBlackRow());
        keyBox.addView(buildWhiteRow());
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
                Button w = new Button(ctx);
                w.setText(nm);
                w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                w.setTextColor(Color.parseColor("#111111"));
                w.setAllCaps(false);
                w.setTag("key");
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                w.setPadding(0, 0, 0, dp(10));
                w.setBackground(whiteKeyBg());
                w.setOnClickListener(v -> play(semi, nm));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(165), 1f);
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
                    Button bk = new Button(ctx);
                    bk.setText("");                       // 黑鍵唔寫字，睇顏色就知
                    bk.setAllCaps(false);
                    bk.setTag("key");
                    bk.setBackground(blackKeyBg());
                    final int semiB = (baseOctave + o) * 12 + BLACK_SEMI[blackIdx];
                    final String bname = BLACK_NAME[blackIdx] + (baseOctave + o);
                    bk.setOnClickListener(v -> play(semiB, bname));
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(100), 0.84f);
                    blp.setMargins(dp(1), 0, dp(1), 0);
                    slot.addView(bk, blp);
                    View right = new View(ctx);
                    slot.addView(right, new LinearLayout.LayoutParams(0, 1, 0.58f));
                } else {
                    View sp = new View(ctx);
                    slot.addView(sp, new LinearLayout.LayoutParams(0, 1, 2f));
                }
                blackRow.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        audio.playNote(freq, 1400, dev, 1.0f);
        log.out("🎹 " + label + "  " + String.format(java.util.Locale.US, "%.1f Hz", freq)
                + "  → " + (dev == null ? "預設輸出（搵唔到車外 speaker）" : "車外 speaker"));
        if (!firstNoteReported) {
            firstNoteReported = true;
            Diag.send(ctx, "piano-note",
                    "第一個琴音：" + label + " " + String.format(java.util.Locale.US, "%.1f Hz", freq)
                            + "\n要嘅裝置：" + Pcm.devDesc(dev));
        }
    }

    /** 只用車外 speaker。 */
    private AudioDeviceInfo dev() {
        return audio.outer();
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
