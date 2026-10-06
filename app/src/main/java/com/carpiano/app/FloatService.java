package com.carpiano.app;

import android.app.Service;
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
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 懸浮掣：用系統 overlay window 疊喺其他 app 之上：
 * <ul>
 *   <li>{@code TYPE_APPLICATION_OVERLAY}（API 26+）／{@code TYPE_PHONE}，
 *       {@code FLAG_NOT_FOCUSABLE} ＋ {@code PixelFormat.TRANSLUCENT}、{@code gravity=TOP|START}</li>
 *   <li>拖曳用 {@code updateViewLayout}，**邊界夾在屏幕內**，放手先寫 SharedPreferences</li>
 *   <li>下次開返還原上次位置</li>
 * </ul>
 *
 * 兩個**車 horn 符號**按鈕，一紅一藍，一律經 🚗 {@code BUS12_OUTER_NOTIFY} 出聲：
 * <ul>
 *   <li>🔴 紅：HORN 嗶嗶（assets/horn.wav）</li>
 *   <li>🔵 藍：倒車提示 — 廣東話「倒車，倒車，請小心」（assets/reverse.mp3）</li>
 * </ul>
 */
public class FloatService extends Service {

    private static final String PREF = "piano";
    private static final String K_X = "fx";
    private static final String K_Y = "fy";

    private WindowManager wm;
    private View bar;
    private WindowManager.LayoutParams lp;
    private AudioOut audio;
    private MediaPlayer player;
    private TextView hint;
    private SharedPreferences sp;
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
        btnSize = dp(88);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        buildBar();
        try {
            wm.addView(bar, lp);
        } catch (Throwable e) {
            stopSelf();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView roundButton(String symbol, String label, int colour, View.OnClickListener click) {
        TextView tv = new TextView(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        g.setStroke(dp(2), Color.parseColor("#E6FFFFFF"));
        tv.setBackground(g);
        tv.setText(symbol + "\n" + label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(click);
        return tv;
    }

    private void buildBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#B3101820"));
        bg.setCornerRadius(dp(16));
        row.setBackground(bg);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 拖曳手柄（唔想拖到按鈕，所以留一條獨立手柄）
        TextView grip = new TextView(this);
        grip.setText("⋮⋮");
        grip.setTextColor(Color.parseColor("#CCFFD166"));
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        grip.setGravity(Gravity.CENTER);
        row.addView(grip, new LinearLayout.LayoutParams(dp(30), btnSize));

        // 🔴 紅：HORN 嗶嗶
        TextView horn = roundButton("📯", "HORN", Color.parseColor("#D32F2F"),
                v -> playAsset("horn.wav", "horn.mp3", "HORN 嗶嗶"));
        row.addView(horn, new LinearLayout.LayoutParams(btnSize, btnSize));

        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        // 🔵 藍：倒車提示（廣東話女聲）
        TextView rev = roundButton("📯", "倒車", Color.parseColor("#1565C0"),
                v -> playAsset("reverse.wav", "reverse.mp3", "倒車提示（廣東話）"));
        row.addView(rev, new LinearLayout.LayoutParams(btnSize, btnSize));

        // ✕ 收埋
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.parseColor("#E6FFFFFF"));
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> stopSelf());
        row.addView(close, new LinearLayout.LayoutParams(dp(34), dp(34)));

        bar = row;

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
        lp.x = sp.getInt(K_X, dp(40));
        lp.y = sp.getInt(K_Y, dp(120));
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
                        // 放手先落盤，避免拖動過程反覆寫
                        sp.edit().putInt(K_X, lp.x).putInt(K_Y, lp.y).apply();
                        return true;
                    default:
                        return false;
                }
            }
        });

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        hint.setTextColor(Color.parseColor("#CCFFD166"));
        String addr = audio.outer() == null ? "⚠️ 搵唔到車外 speaker" : "🚗 車外 speaker";
        hint.setText(addr);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(hint);
        bar = col;
    }

    private void clamp() {
        int w = dp(280);            // 大約闊度（只為夾位用）
        lp.x = Math.max(0, Math.min(lp.x, Math.max(0, screenW - w)));
        lp.y = Math.max(0, Math.min(lp.y, Math.max(0, screenH - dp(120))));
    }

    private void playAsset(String wav, String mp3, String label) {
        Pcm.stopAll();
        stopPlayer();
        final AudioDeviceInfo dev = audio.outer();
        show(label + " …");
        Diag.send(this, "tap", "撳咗：" + label + "\n要嘅裝置：" + Pcm.devDesc(dev));
        Pcm.playAsset(this, wav, dev, msg -> {
            show(msg);
            Diag.send(this, "play", label + " → " + msg);
            if (msg.startsWith("⚠️")) {
                playFallback(mp3, label);          // WAV 唔得 → 後備 MediaPlayer
            }
        });
    }

    /** 後備：MediaPlayer（如果 WAV／AudioTrack 呢條路唔通）。 */
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
            show(label + "（後備播放）→ " + (dev == null ? "⚠️ 搵唔到車外 speaker" : "車外 speaker"));
            Diag.send(this, "play-fallback", label + " → MediaPlayer 已播");
        } catch (Throwable e) {
            show("⚠️ " + name + " 後備都播唔到：" + e.getClass().getSimpleName());
            Diag.send(this, "play-fail", label + " → " + e);
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
        stopPlayer();
        try {
            if (bar != null && wm != null) wm.removeView(bar);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
