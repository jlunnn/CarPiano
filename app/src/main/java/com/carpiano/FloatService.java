package com.carpiano;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 懸浮控制窗（v2.0 改為「分頁」設計）。
 *
 * <p><b>第 1 頁</b>：🎤「按住講」（獨立一格）—— 按住錄音、放開 1 秒後自動播出車外 speaker。</p>
 * <p><b>第 2 頁起</b>：2×2 Preset 語音格 —— 內容＝錄音頁嘅 Preset 清單（HORN、倒車提示、你錄低嘅語音），
 * 每頁 4 個，<b>有幾多 Preset 就自動有幾多頁</b>。左右掃換頁，頁點顯示位置。</p>
 *
 * <p>做法沿用 zeekr-shortcut 嘅浮動窗：{@code TYPE_APPLICATION_OVERLAY} ＋ {@code FLAG_NOT_FOCUSABLE}、
 * 用 {@code updateViewLayout} 拖曳（只可以拖手柄位）、放手才寫 SharedPreferences、下次還原位置。</p>
 */
public class FloatService extends Service {

    private static final String PREF = "piano";
    private static final String K_X = "fx";
    private static final String K_Y = "fy";
    private static final String K_PAGE = "float_page";
    private static final String REC_PREFIX = "rec-";
    private static final int PER_PAGE = 4;          // 第 2 頁起：每頁 4 個（2×2）

    /** 一個 Preset（按落去會用車外 speaker 播）。 */
    private static class Preset {
        final String label;
        final String assetWav;      // 內置音效（asset）
        final String assetMp3;      // 內置後備
        final File file;            // 自己錄嘅
        Preset(String label, String wav, String mp3, File f) {
            this.label = label; this.assetWav = wav; this.assetMp3 = mp3; this.file = f;
        }
    }

    private WindowManager wm;
    private View bar;
    private WindowManager.LayoutParams lp;
    private AudioOut audio;
    private MediaPlayer player;
    private TextView hint;
    private TextView pageLabel;
    private LinearLayout pager;
    private LinearLayout dotsRow;
    private PushToTalk ptt;
    private SharedPreferences sp;

    private final List<Preset> presets = new ArrayList<>();
    private int page;                    // 0 = 按住講；1..N = Preset 頁
    private int btnSize, screenW, screenH;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = new AudioOut(this);
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        btnSize = dp(76);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        // Android 11+ 喺服務入面收音一定要「前台服務 + microphone 類型」
        startForegroundNotice();
        loadPresets();
        page = Math.max(0, Math.min(sp.getInt(K_PAGE, 0), pageCount() - 1));
        buildBar();
        try {
            wm.addView(bar, lp);
        } catch (Throwable e) {
            stopSelf();
        }
    }

    private static final int NOTI_ID = 7401;

    private void startForegroundNotice() {
        try {
            String ch = "float";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(ch) == null) {
                    android.app.NotificationChannel c = new android.app.NotificationChannel(
                            ch, "懸浮控制窗", android.app.NotificationManager.IMPORTANCE_MIN);
                    c.setShowBadge(false);
                    nm.createNotificationChannel(c);
                }
            }
            android.app.Notification n = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new android.app.Notification.Builder(this, ch)
                    : new android.app.Notification.Builder(this))
                    .setContentTitle("懸浮控制窗已開啟")
                    .setContentText("第 1 頁：🎤 按住講（錄完自動播車外）｜第 2 頁起：Preset 語音")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            show("⚠️ 前台服務開唔到：" + t.getClass().getSimpleName());
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ================================================================ Preset

    /** Preset ＝ 內置（HORN／倒車）＋ 所有錄音（rec-*.wav），錄音越多頁越多。 */
    private void loadPresets() {
        presets.clear();
        presets.add(new Preset("HORN 嗶嗶", "horn.wav", "horn.mp3", null));
        presets.add(new Preset("倒車提示", "reverse.wav", "reverse.mp3", null));
        File dir = getFilesDir();
        File[] fs = dir.listFiles((d, name) -> name.startsWith(REC_PREFIX)
                && (name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".mp3")));
        if (fs != null) {
            Arrays.sort(fs, Comparator.comparing(File::getName));
            for (File f : fs) {
                String label = f.getName().replace(REC_PREFIX, "")
                        .replace(".wav", "").replace(".m4a", "");
                presets.add(new Preset(label, null, null, f));
            }
        }
    }

    /** 總頁數 = 1（按住講）＋ Preset 頁數。 */
    private int pageCount() {
        return 1 + Math.max(1, (presets.size() + PER_PAGE - 1) / PER_PAGE);
    }

    // ================================================================ 建立浮動窗

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(Theme.R_TILE));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    /** v3：玻璃後面模糊（API 31+；車機唔支援都唔影響，半透明底照樣好睇）。 */
    private int lp_blur = 0;

    private TextView tile(String label, String sub, int fill, int stroke, int textColour) {
        TextView tv = new TextView(this);
        tv.setText(sub == null ? label : label + "\n" + sub);
        tv.setTextColor(textColour);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(round(fill, stroke));
        tv.setPadding(dp(4), dp(4), dp(4), dp(4));
        return tv;
    }

    private void buildBar() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6101820"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2), Theme.ACCENT);                 // v2：實心 + accent 框
        col.setBackground(bg);
        col.setPadding(dp(8), dp(6), dp(8), dp(6));

        // ---- 頂列：拖曳手柄 + 標題 + 頁碼 + 收埋 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView grip = new TextView(this);
        grip.setText("⋮⋮");
        grip.setTextColor(Color.parseColor("#CCFFD166"));
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        grip.setGravity(Gravity.CENTER);
        head.addView(grip, new LinearLayout.LayoutParams(dp(30), dp(30)));

        TextView title = new TextView(this);
        title.setText("懸浮控制");
        title.setTextColor(Theme.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pageLabel = new TextView(this);
        pageLabel.setTextColor(Theme.TEXT_DIM);
        pageLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        head.addView(pageLabel);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#E6FFFFFF"));
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> {
            sp.edit().putBoolean("float_enabled", false).apply();
            show("已收埋懸浮控制窗（設定頁可以再開）");
            stopSelf();
        });
        head.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));
        col.addView(head);

        // ---- 分頁區（左右掃換頁）----
        pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.VERTICAL);
        pager.setGravity(Gravity.CENTER);
        col.addView(pager);

        // ---- 下半部分：◀ 頁點 ▶（用戶要求加左右箭咀，方便撥左撥右）----
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER_VERTICAL);

        Button prev = arrowBtn("◀");
        prev.setOnClickListener(v -> flipPage(-1));
        navRow.addView(prev, new LinearLayout.LayoutParams(dp(58), dp(48)));

        dotsRow = new LinearLayout(this);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        navRow.addView(dotsRow, dlp2);

        Button next = arrowBtn("▶");
        next.setOnClickListener(v -> flipPage(1));
        navRow.addView(next, new LinearLayout.LayoutParams(dp(58), dp(48)));

        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(6);
        col.addView(navRow, nlp);

        // ---- 狀態行 ----
        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        hint.setTextColor(0xFFA9AEB6);
        hint.setText(audio.outer() == null ? "⚠️ 搵唔到車外 speaker" : "🚗 車外 speaker");
        col.addView(hint);

        bar = col;
        renderPage();

        // 左右掃（喺整個窗都收，方便車上用手指掃）
        col.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        return false;                 // 唔攔截，按鈕照收到事件
                    case MotionEvent.ACTION_UP:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            goPage(dx < 0 ? page + 1 : page - 1);
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });

        int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt(K_X, screenW - dp(220));
        lp.y = sp.getInt(K_Y, dp(90));
        clamp();

        grip.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        clamp();
                        try {
                            wm.updateViewLayout(bar, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        sp.edit().putInt(K_X, lp.x).putInt(K_Y, lp.y).apply();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** 懸浮窗用嘅箭咀掣（細、透明底、圓角）。 */
    private Button arrowBtn(String txt) {
        Button b = Theme.button(this);
        b.setText(txt);
        b.setTag("skip");
        Theme.secondary(b, this);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    /** 撥頁（箭咀用）：會循環（最後一頁 → 第一頁）。 */
    private void flipPage(int dir) {
        int n = pageCount();
        if (n <= 1) return;
        page = ((page + dir) % n + n) % n;
        sp.edit().putInt(K_PAGE, page).apply();
        renderPage();          // renderPage 會同時重畫頁點
    }

    /** 畫出第 page 頁（0 = 按住講；其餘 = 2×2 Preset）。 */
    private void renderPage() {
        pager.removeAllViews();
        if (page == 0) {
            // ---- 第 1 頁：按住講（演唱會咪 stand 圖示 + 細字）----
            LinearLayout pttTile = new LinearLayout(this);
            pttTile.setOrientation(LinearLayout.VERTICAL);
            pttTile.setGravity(android.view.Gravity.CENTER);
            pttTile.setBackground(round(Theme.ACCENT, 0));
            int micPx = (int) (btnSize * 0.85f);      // 咪 stand 圖示大小
            ImageView micIv = new ImageView(this);
            micIv.setImageResource(R.drawable.ic_mic_stand);
            micIv.setClickable(false);
            micIv.setFocusable(false);
            pttTile.addView(micIv, new LinearLayout.LayoutParams(micPx, micPx));
            TextView micCap = new TextView(this);
            micCap.setText("按住講");
            micCap.setTextColor(Theme.ON_ACCENT);
            micCap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            micCap.setGravity(android.view.Gravity.CENTER);
            micCap.setClickable(false);
            micCap.setFocusable(false);
            pttTile.addView(micCap);
            pttTile.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackground(round(Theme.DANGER, 0));
                        Pcm.stopAll();
                        stopPlayer();
                        if (ptt == null) ptt = new PushToTalk(this, msg -> show(msg));
                        ptt.start();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(round(Theme.ACCENT, 0));
                        if (ptt != null) ptt.stop();
                        playLastRecording();
                        return true;
                    default:
                        return true;
                }
            });
            pager.addView(pttTile, new LinearLayout.LayoutParams(btnSize * 2 + dp(10), btnSize * 2 + dp(10)));
        } else {
            int from = (page - 1) * PER_PAGE;
            for (int row = 0; row < 2; row++) {
                LinearLayout r = new LinearLayout(this);
                r.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < 2; c++) {
                    int idx = from + row * 2 + c;
                    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(btnSize, btnSize);
                    if (c == 0) tlp.rightMargin = dp(10);
                    if (idx < presets.size()) {
                        final Preset p = presets.get(idx);
                        TextView t = tile("▶\n" + brief(p.label), null, Theme.SURFACE_2, Theme.STROKE, Theme.TEXT);
                        t.setOnClickListener(v -> {
                            t.setBackground(round(Theme.ACCENT, 0));
                            t.postDelayed(() -> t.setBackground(round(Theme.SURFACE_2, Theme.STROKE)), 260);
                            playPreset(p);
                        });
                        r.addView(t, tlp);
                    } else {
                        View empty = new View(this);
                        r.addView(empty, tlp);      // 保持 2×2 格仔對齊
                    }
                }
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, btnSize);
                if (row == 0) rlp.bottomMargin = dp(10);
                pager.addView(r, rlp);
            }
        }

        // 頁點 + 頁碼
        dotsRow.removeAllViews();
        for (int i = 0; i < pageCount(); i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i == page ? Theme.ACCENT : 0xFF3A4048);
            dot.setBackground(d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(i == page ? 16 : 7), dp(7));
            dlp.leftMargin = dp(3);
            dlp.rightMargin = dp(3);
            dlp.topMargin = dp(5);
            dotsRow.addView(dot, dlp);
        }
        pageLabel.setText((page == 0 ? "第 1／" : "第 " + (page + 1) + "／") + pageCount() + " 頁");
        sp.edit().putInt(K_PAGE, page).apply();
    }

    /** 標籤太長就縮短（車機細格）。 */
    private static String brief(String s) {
        String t = s.replace("20261006-", "").replace("rec-", "");
        return t.length() > 9 ? t.substring(0, 9) : t;
    }

    private void goPage(int p) {
        int n = Math.max(0, Math.min(p, pageCount() - 1));
        if (n == page) return;
        if (ptt != null) ptt.stop();
        page = n;
        renderPage();
    }

    private void clamp() {
        int w = dp(200);
        lp.x = Math.max(0, Math.min(lp.x, Math.max(0, screenW - w)));
        lp.y = Math.max(0, Math.min(lp.y, Math.max(0, screenH - dp(230))));
    }

    // ================================================================ 播聲

    private void playLastRecording() {
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        show("⏳ 1 秒後播車外…");
        h.postDelayed(() -> {
            if (ptt == null) return;
            File f = ptt.lastFile();
            long ms = ptt.lastMs();
            if (f == null || !f.exists() || ms < 150) {
                show("⚠️ 錄得太短（" + ms + " ms）→ 唔播");
                return;
            }
            playFile(f, "自己錄音");
        }, 1000);
    }

    private void playPreset(Preset p) {
        if (p.file != null) {
            playFile(p.file, p.label);
        } else {
            playAsset(p.assetWav, p.assetMp3, p.label);
        }
    }

    private void playFile(File f, String label) {
        Pcm.stopAll();
        stopPlayer();
        AudioDeviceInfo dev = audio.outer();
        show("▶ " + label + " → " + (dev == null ? "⚠️ 搵唔到車外" : "車外"));
        Pcm.playFile(this, f, dev,
                android.media.AudioAttributes.USAGE_MEDIA,
                android.media.AudioAttributes.CONTENT_TYPE_MUSIC,
                msg -> show(msg));
    }

    private void playAsset(String wav, String mp3, String label) {
        Pcm.stopAll();
        stopPlayer();
        final AudioDeviceInfo dev = audio.outer();
        show(label + " …");
        Pcm.playAsset(this, wav, dev, msg -> {
            show(msg);
            if (msg.startsWith("⚠️")) playFallback(mp3, label);
        });
    }

    private void playFallback(String name, String label) {
        stopPlayer();
        try {
            player = new MediaPlayer();
            AssetFileDescriptor afd = getAssets().openFd(name);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.prepare();
            AudioDeviceInfo dev = audio.outer();
            AudioOut.setPlayerDevice(player, dev);
            player.setOnCompletionListener(mp -> {
                show(label + "（後備）✅ 播完");
                stopPlayer();
            });
            player.start();
        } catch (Throwable e) {
            show("⚠️ " + name + " 後備都播唔到：" + e.getClass().getSimpleName());
            stopPlayer();
        }
    }

    private void show(String s) {
        if (hint != null) hint.post(() -> hint.setText(s));
    }

    private void stopPlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.stop();
                p.release();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (ptt != null) ptt.stop();
        } catch (Throwable ignored) {
        }
        stopPlayer();
        try {
            if (bar != null && wm != null) wm.removeView(bar);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
