package com.carpiano;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 車機風格 theme（深色、Material 3、藍調 accent、大觸控區）。
 *
 * <p>A＋B 級 UI 升級：加咗 Material 3 之後，按鈕／卡片／滑桿自動有**水波紋、狀態層、elevation**；
 * 但顏色一律由呢個檔（＋ res/values/colors.xml）控制，唔用動態色，保持車機一套色。</p>
 *
 * <p>所有顏色／圓角／間距集中喺呢一個檔 → 想換色只改呢度，唔使逐個 UI 檔搵。</p>
 */
public final class Theme {

    // ---- 顏色 tokens（同 res/values/colors.xml 一致）----
    public static final int BG = 0xFF0E0F12;          // 主背景（近黑）
    public static final int BG_TOP = 0xFF141821;      // 背景漸變頂
    public static final int SURFACE = 0xFF171A20;     // 卡片
    public static final int SURFACE_2 = 0xFF1F232B;   // 次級卡片／未選中
    public static final int STROKE = 0xFF2C3138;      // 幼邊框
    public static final int TEXT = 0xFFF4F6F8;        // 主文字
    public static final int TEXT_DIM = 0xFFA9AEB6;    // 次要文字
    public static final int ACCENT = 0xFF4DA3FF;      // 主 accent（藍）
    public static final int ACCENT_PRESS = 0xFF3D8AD9;
    public static final int ACCENT_SOFT = 0x334DA3FF; // accent 半透明底
    public static final int DANGER = 0xFFE5484D;      // 停止／危險
    public static final int OK = 0xFF3DD68C;          // 就緒／成功
    public static final int KEY_WHITE = 0xFFE9ECF1;   // 琴鍵（白）
    public static final int KEY_BLACK = 0xFF0B0C0E;   // 琴鍵（黑）
    public static final int ACTIVE_TEXT = 0xFF07131F; // accent 底上嘅深色字／圖示

    // ---- 尺寸 tokens（dp）----
    public static final int R_CARD = 16;
    public static final int R_BTN = 12;
    public static final int R_PILL = 999;
    public static final int PAD = 12;
    public static final int GAP = 8;

    private Theme() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 建立元件

    /** 造一個按鈕：有 Material 3 就用（有圓角＋水波紋），冇就退回普通 Button。 */
    public static Button button(Context c) {
        try {
            com.google.android.material.button.MaterialButton b =
                    new com.google.android.material.button.MaterialButton(c);
            b.setAllCaps(false);
            b.setInsetTop(0);
            b.setInsetBottom(0);
            return b;
        } catch (Throwable t) {
            Button b = new Button(c);
            b.setAllCaps(false);
            return b;
        }
    }

    /** 造一張卡片（Material 3 = 有 elevation／圓角／狀態層）。 */
    public static View cardView(Context c) {
        try {
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(c);
            card.setRadius(dp(c, R_CARD));
            card.setCardElevation(dp(c, 3));
            card.setCardBackgroundColor(SURFACE);
            card.setStrokeColor(STROKE);
            card.setStrokeWidth(dp(c, 1));
            int p = dp(c, PAD);
            card.setContentPadding(p, p, p, p);
            return card;
        } catch (Throwable t) {
            LinearLayout l = new LinearLayout(c);
            card(l, c);
            return l;
        }
    }

    // ---------------------------------------------------------------- 背景／卡片

    public static GradientDrawable bg(int fill, int stroke, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /** 主背景：微漸變（深灰藍 → 近黑），比純色有層次。 */
    public static GradientDrawable bgGradient() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG});
    }

    public static GradientDrawable cardDrawable(Context c) {
        return bg(SURFACE, STROKE, R_CARD, c);
    }

    /** 卡片（唔係 MaterialCardView 時用）：深色底 + 幼邊框 + 內距 + 少少 elevation。 */
    public static void card(View v, Context c) {
        v.setBackground(cardDrawable(c));
        int p = dp(c, PAD);
        v.setPadding(p, p, p, p);
        try {
            v.setElevation(dp(c, 2));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 按鈕樣式

    /** 主按鈕（accent 實心、有波紋）。 */
    public static void primary(Button b, Context c) {
        b.setAllCaps(false);
        b.setTextColor(ACTIVE_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(0);
            mb.setRippleColor(ColorStateList.valueOf(0x33FFFFFF));
            mb.setElevation(dp(c, 3));
        } else {
            b.setBackground(stateful(c, ACCENT, ACCENT_PRESS, Color.TRANSPARENT));
        }
    }

    /** 次按鈕（深底 + 幼邊框 + 波紋）。 */
    public static void secondary(Button b, Context c) {
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTypeface(Typeface.DEFAULT);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setBackgroundTintList(ColorStateList.valueOf(SURFACE_2));
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(dp(c, 1));
            mb.setStrokeColor(ColorStateList.valueOf(STROKE));
            mb.setRippleColor(ColorStateList.valueOf(0x33FFFFFF));
            mb.setElevation(0);
        } else {
            b.setBackground(stateful(c, SURFACE_2, 0xFF2A2F38, STROKE));
        }
    }

    /** 危險（停止）按鈕。 */
    public static void danger(Button b, Context c) {
        b.setAllCaps(false);
        b.setTextColor(0xFFFFE8E8);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTypeface(Typeface.DEFAULT);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setBackgroundTintList(ColorStateList.valueOf(0xFF3A1D20));
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(dp(c, 1));
            mb.setStrokeColor(ColorStateList.valueOf(DANGER));
            mb.setRippleColor(ColorStateList.valueOf(0x55E5484D));
            mb.setElevation(0);
        } else {
            b.setBackground(stateful(c, 0xFF3A1D20, 0xFF4A2427, DANGER));
        }
    }

    /** 分段控制（tab／選項列）：選中 = accent 底，未選中 = 深底幼邊框。 */
    public static void segment(Button b, Context c, boolean active) {
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(active ? 0 : dp(c, 1));
            mb.setStrokeColor(ColorStateList.valueOf(STROKE));
            mb.setBackgroundTintList(ColorStateList.valueOf(active ? ACCENT : SURFACE_2));
            mb.setRippleColor(ColorStateList.valueOf(active ? 0x33FFFFFF : 0x22FFFFFF));
            mb.setElevation(active ? dp(c, 3) : 0);
        } else {
            b.setBackground(active ? stateful(c, ACCENT, ACCENT_PRESS, Color.TRANSPARENT)
                    : stateful(c, SURFACE_2, 0xFF2A2F38, STROKE));
        }
        if (active) {
            b.setTextColor(ACTIVE_TEXT);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            b.setTextColor(TEXT_DIM);
            b.setTypeface(Typeface.DEFAULT);
        }
    }

    /** 分段掣 + 圖示：跟 active 狀態一齊轉字色、底色同圖示顏色。 */
    public static void segmentIcon(Button b, Context c, boolean active, int iconRes) {
        segment(b, c, active);
        android.graphics.drawable.Drawable d =
                c.getResources().getDrawable(iconRes, c.getTheme()).mutate();
        d.setTint(active ? ACTIVE_TEXT : TEXT_DIM);
        b.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        b.setCompoundDrawablePadding(dp(c, 5));
    }

    /** 為按鈕加圖示（自動跟當前字色 tint）。 */
    public static void icon(Button b, int iconRes) {
        Context c = b.getContext();
        android.graphics.drawable.Drawable d =
                c.getResources().getDrawable(iconRes, c.getTheme()).mutate();
        d.setTint(b.getCurrentTextColor());
        b.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        b.setCompoundDrawablePadding(dp(c, 5));
    }

    /** 縮細按鈕（車機上用細掣做密集操作，例如錄音／停止）；仍然保持 40dp 觸控高度。 */
    public static void compact(Button b, Context c) {
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setMinimumHeight(dp(c, 40));
        b.setMinimumWidth(0);
        int ph = dp(c, 8), pv = dp(c, 4);
        b.setPadding(ph, pv, ph, pv);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) b).setCornerRadius(dp(c, 10));
        }
    }

    // ---------------------------------------------------------------- 文字

    /** 等寬／細字（狀態、log）用。 */
    public static void mono(TextView tv, int colour, float sp) {
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(colour);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    public static void label(TextView tv, int colour, float sp) {
        tv.setTextColor(colour);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    public static void title(TextView tv, float sp) {
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** 小膠囊標籤（狀態用），可以帶一個圖示。 */
    public static TextView pill(Context c, String text, int colour) {
        return pill(c, 0, text, colour);
    }

    public static TextView pill(Context c, int iconRes, String text, int colour) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(colour);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        if (iconRes != 0) {
            tv.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
            tv.setCompoundDrawablePadding(dp(c, 6));
        }
        tv.setBackground(bg(0x22000000 | (colour & 0xFFFFFF), colour, R_PILL, c));
        return tv;
    }

    // ---------------------------------------------------------------- 版面小工具

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    /**
     * 一次過為一棵 UI 樹嘅按鈕上樣式。
     *
     * <p>用 tag 分辨：`key` = 唔好改（鋼琴鍵有自己嘅外觀）、`skip` = 唔理（自己控制，例如分段掣）、
     * `primary` = accent 實心、`danger` = 紅框、其餘 = 次級（深底幼邊框）。</p>
     */
    public static void styleTree(View v) {
        Context c = v.getContext();
        if (v instanceof Button) {
            Button b = (Button) v;
            Object t = b.getTag();
            if ("key".equals(t) || "skip".equals(t)) return;
            if ("primary".equals(t)) primary(b, c);
            else if ("danger".equals(t)) danger(b, c);
            else secondary(b, c);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleTree(g.getChildAt(i));
        }
    }

    private static StateListDrawable stateful(Context c, int normal, int pressed, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, bg(pressed, stroke, R_BTN, c));
        s.addState(new int[]{}, bg(normal, stroke, R_BTN, c));
        return s;
    }
}
