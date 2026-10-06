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
 * 車機風格 theme（v2：深色、Material 3、藍 accent、幼邊框分格、大觸控區）。
 *
 * <p>⚠️ v3 嘅「tonal 無邊框 + 大字級 + 玻璃 + spring」已按用戶要求撤回（2026-10-07），
 * 呢個係 v2 設計（用戶揀嘅版本）。功能（三格、懸浮分頁、Preset）不受影響。</p>
 *
 * <p>所有顏色／圓角／間距集中喺呢一個檔 → 想換色只改呢度（＋ res/values/colors.xml）。</p>
 */
public final class Theme {

    // ---- 顏色 tokens（同 res/values/colors.xml 一致）----
    public static final int BG = 0xFF0E0F12;          // 主背景（近黑）
    public static final int BG_TOP = 0xFF141821;      // 背景漸變頂（輕微）
    public static final int SURFACE = 0xFF171A20;     // 卡片
    public static final int SURFACE_2 = 0xFF1F232B;   // 次級卡片／未選中
    public static final int SURFACE_3 = 0xFF262A31;
    public static final int STROKE = 0xFF2C3138;      // 幼邊框（v2 用邊框分格）
    public static final int TEXT = 0xFFF4F6F8;        // 主文字
    public static final int TEXT_DIM = 0xFFA9AEB6;    // 次要文字
    public static final int ACCENT = 0xFF4DA3FF;      // 主 accent（藍）
    public static final int ACCENT_PRESS = 0xFF3D8AD9;
    public static final int ACCENT_SOFT = 0x334DA3FF; // accent 半透明底
    public static final int ACCENT_LIGHT = 0xFF4DA3FF;
    public static final int ACCENT_DARK = 0xFF3D8AD9;
    public static final int DANGER = 0xFFE5484D;      // 停止／危險
    public static final int DANGER_BG = 0xFF3A1D20;
    public static final int DANGER_TEXT = 0xFFFFE8E8;
    public static final int OK = 0xFF3DD68C;          // 就緒／成功
    public static final int KEY_WHITE = 0xFFF7F7F9;   // 琴鍵（白）
    public static final int KEY_WHITE_BOTTOM = 0xFFE9ECF1;
    public static final int KEY_BLACK = 0xFF15171C;   // 琴鍵（黑）
    public static final int ACTIVE_TEXT = 0xFF07131F; // accent 底上嘅深色字
    public static final int ON_ACCENT = 0xFF07131F;
    public static final int STROKE_GLASS = 0xFF2C3138;
    public static final int WHITE_07 = 0x12FFFFFF;
    public static final int WHITE_12 = 0x1FFFFFFF;

    // ---- 尺寸 tokens（dp）----
    public static final int R_CARD = 16;
    public static final int R_BTN = 12;
    public static final int R_TILE = 14;
    public static final int R_PILL = 999;
    public static final int PAD = 12;
    public static final int GAP = 8;

    // ---- 字級 tokens（sp）----
    public static final float SP_TITLE = 16f;
    public static final float SP_BIG = 20f;
    public static final float SP_LABEL = 13f;
    public static final float SP_SMALL = 11f;

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

    /** 主背景：微漸變（深灰藍 → 近黑）。 */
    public static GradientDrawable bgGradient() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BG_TOP, BG});
    }

    /** 玻璃／半透明底（浮窗用；v2 係實心 + accent 框，呢個留返做備用）。 */
    public static GradientDrawable glass(Context c, int alphaPct, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        int a = Math.max(0, Math.min(100, alphaPct)) * 255 / 100;
        g.setColor((a << 24) | 0x00101820);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, 2), ACCENT);
        return g;
    }

    public static GradientDrawable accentGradient(Context c, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 玻璃卡（參考圖：半透明底 + 藍色邊 + 大圓角）。 */
    public static GradientDrawable glassyCard(Context c) {
        return glassyCard(c, 1);
    }

    /** 玻璃卡（可指定邊框粗幼 dp；status bar 用粗邊）。 */
    public static GradientDrawable glassyCard(Context c, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xCC161B24);                 // 半透明深藍
        g.setCornerRadius(dp(c, 18));
        g.setStroke(dp(c, Math.max(1, strokeDp)), 0xBB4DA3FF);   // 藍色邊（微光）
        return g;
    }

    public static GradientDrawable cardDrawable(Context c) {
        return bg(SURFACE, STROKE, R_CARD, c);
    }

    /** 卡片：深色底 + 幼邊框 + 內距。 */
    public static void card(View v, Context c) {
        v.setBackground(cardDrawable(c));
        int p = dp(c, PAD);
        v.setPadding(p, p, p, p);
    }

    /** v3 嘅 spring 動態已撤回 —— 保留空方法避免呼叫點出錯。 */
    public static void pressSpring(View v) {
    }

    // ---------------------------------------------------------------- 按鈕樣式

    /** 主按鈕（accent 實心、有波紋）。 */
    public static void primary(Button b, Context c) {
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);       // 文字置中（用戶要求）
        b.setTextColor(ACTIVE_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);   // 26sp × 0.7（用戶要求縮 30%）
        b.setTypeface(Typeface.DEFAULT_BOLD);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(0);
            mb.setRippleColor(ColorStateList.valueOf(0x33FFFFFF));
            mb.setElevation(dp(c, 2));
            mb.setMinimumHeight(dp(c, 48));
        } else {
            b.setBackground(stateful(c, ACCENT, ACCENT_PRESS, Color.TRANSPARENT));
        }
    }

    /** 次按鈕（深底 + 幼邊框 + 波紋）。 */
    public static void secondary(Button b, Context c) {
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);       // 文字置中（用戶要求）
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);   // 26sp × 0.7（用戶要求縮 30%）
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
            mb.setMinimumHeight(dp(c, 46));
        } else {
            b.setBackground(stateful(c, SURFACE_2, 0xFF2A2F38, STROKE));
        }
    }

    /** 危險（停止）按鈕。 */
    public static void danger(Button b, Context c) {
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);       // 文字置中（用戶要求）
        b.setTextColor(DANGER_TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);   // 26sp × 0.7（用戶要求縮 30%）
        b.setTypeface(Typeface.DEFAULT);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setBackgroundTintList(ColorStateList.valueOf(DANGER_BG));
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(dp(c, 1));
            mb.setStrokeColor(ColorStateList.valueOf(DANGER));
            mb.setRippleColor(ColorStateList.valueOf(0x55E5484D));
            mb.setElevation(0);
            mb.setMinimumHeight(dp(c, 46));
        } else {
            b.setBackground(stateful(c, DANGER_BG, 0xFF4A2427, DANGER));
        }
    }

    /** 分段控制（tab／選項列）：選中 = accent 底，未選中 = 深底幼邊框。 */
    public static void segment(Button b, Context c, boolean active) {
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);       // 文字置中（用戶要求）
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);   // 24 × 0.7
        if (b instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton mb =
                    (com.google.android.material.button.MaterialButton) b;
            mb.setCornerRadius(dp(c, R_BTN));
            mb.setStrokeWidth(active ? 0 : dp(c, 1));
            mb.setStrokeColor(ColorStateList.valueOf(STROKE));
            mb.setBackgroundTintList(ColorStateList.valueOf(active ? ACCENT : SURFACE_2));
            mb.setRippleColor(ColorStateList.valueOf(active ? 0x33FFFFFF : 0x22FFFFFF));
            mb.setElevation(active ? dp(c, 2) : 0);
            mb.setMinimumHeight(dp(c, 46));
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

    /** 分段掣 + 圖示。 */
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

    /** 縮細按鈕（車機上用細掣做密集操作，例如錄音／停止）。 */
    public static void compact(Button b, Context c) {
        b.setAllCaps(false);
        b.setGravity(android.view.Gravity.CENTER);       // 文字置中（用戶要求）
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);   // 22 × 0.7
        b.setMinimumHeight(dp(c, 44));
        b.setMinimumWidth(0);
        int ph = dp(c, 8), pv = dp(c, 4);
        b.setPadding(ph, pv, ph, pv);
        if (b instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) b).setCornerRadius(dp(c, 10));
        }
    }

    // ---------------------------------------------------------------- 文字

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

    /** 大數值（v2 保留：只係一般粗體，唔係 v3 嘅超大字）。 */
    public static void big(TextView tv, float sp) {
        tv.setTextColor(TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** 狀態膠囊（帶幼邊框）。 */
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
     * <p>tag：`key` = 唔好改（鋼琴鍵）、`skip` = 自己控制、`primary` / `danger`，
     * 其餘 = 次級（深底幼邊框）。</p>
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
