package com.carpiano;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Car Piano —— 三個 tab：
 *   1. 🎹 鋼琴鍵盤（只出 🚗 車外 BUS12）
 *   2. 🎙 錄音 ＋ 咪位置測試（只播 🚗 車外 BUS12）
 *   3. 🚗 開車自動（開車自動開啟／熄車自動關閉）
 *
 * v1.6 加咗**本機診斷**：app 一起、起完 UI、崩潰、每次播音都寫一段本機記錄
 * → 出事可以喺 app 內睇返，唔使隔空猜。
 */
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private AudioOut audio;
    private LinearLayout content;
    private TextView outLine;
    private LinearLayout pageHost, tabRow;
    private final java.util.List<Button> tabButtons = new java.util.ArrayList<>();
    private final java.util.List<Button> chanButtons = new java.util.ArrayList<>();
    private final java.util.List<LinearLayout> chanTiles = new java.util.ArrayList<>();
    private final java.util.List<TextView> chanTileTexts = new java.util.ArrayList<>();
    private TextView dashRecState, dashRecCount;
    private Button backBtn;
    private TextView pianoStatus, recStatus;
    private View dashLevelFill, dashLevelSpace;
    private final android.os.Handler dashH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dashTick = new Runnable() {
        @Override
        public void run() {
            refreshDashboard();
            refreshStatuses();
            dashH.postDelayed(this, 1000);
        }
    };
    private final View[] pages = new View[4];
    private static final String[] TAB_NAMES = {"主頁", "Piano", "汽車 Hifi 喇叭"};   // 設定只用右上齒輪
    private static final int[] TAB_ICONS = {R.drawable.ic_home, R.drawable.ic_tab_piano, R.drawable.ic_hifi};
    private TextView autoStatus;
    private Button autoToggle;
    private LinearLayout updateBar;
    private TextView updateText, updateStatus;
    private Update updater;
    private TextView floatState;
    private PianoTab pianoTab;
    private RecordTab recordTab;
    private final StringBuilder logBuf = new StringBuilder();
    private boolean pendingFloat;
    private SharedPreferences sp;
    private boolean uiOk;

    private final BroadcastReceiver stateRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AutoService.BC_CLOSE.equals(i.getAction())) {
                log("🅿️ 收到熄車 → 自動關閉 app");
                finish();
                return;
            }
            if (AutoService.BC_STATE.equals(i.getAction())) {
                showAuto(i.getIntExtra("raw", Integer.MIN_VALUE),
                        i.getBooleanExtra("driving", false),
                        i.getStringExtra("note"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diag.send(this, "boot-app", "撳咗 app icon → onCreate 開始");
        installCrashHandler();
        audio = new AudioOut(this);
        sp = getSharedPreferences("piano", MODE_PRIVATE);

        try {
            setContentView(buildRoot());
            uiOk = true;
            Diag.send(this, "ui-ok", snapshot());
        } catch (Throwable t) {
            fail("起 UI 失敗", t);
            return;
        }

        IntentFilter f = new IntentFilter();
        f.addAction(AutoService.BC_STATE);
        f.addAction(AutoService.BC_CLOSE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateRx, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateRx, f);
            }
        } catch (Throwable t) {
            log("⚠️ 註冊接收器失敗：" + t.getClass().getSimpleName());
        }

        checkUpdate(false);          // 開 app 自動查一次；有新版先彈提示
        startFloatIfAllowed();       // v2.0：懸浮控制窗預設自動開（設定頁可以隱藏）
    }

    // ---------------------------------------------------------------- 更新提示

    /** 更新提示橫額：平時隱藏，check 到新版才顯示，一撳就下載＋交系統安裝。 */
    private View buildUpdateBar() {
        updateBar = new LinearLayout(this);
        updateBar.setOrientation(LinearLayout.VERTICAL);
        Theme.card(updateBar, this);
        updateBar.setVisibility(View.GONE);

        updateText = new TextView(this);
        Theme.title(updateText, 13f);
        updateText.setText("⬆️ 有新版本");
        updateBar.addView(updateText);

        updateStatus = new TextView(this);
        Theme.label(updateStatus, Theme.TEXT_DIM, 11f);
        updateStatus.setText("——");
        updateBar.addView(updateStatus);

        LinearLayout row = new LinearLayout(this);
        Button go = Theme.button(this);
        go.setText("下載並更新");
        go.setTag("skip");
        Theme.primary(go, this);
        Theme.compact(go, this);
        Theme.icon(go, R.drawable.ic_download);
        go.setOnClickListener(v -> {
            updateText.setText("⬆️ 更新中");
            updater.downloadAndInstall();
        });
        row.addView(go, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button later = Theme.button(this);
        later.setText("稍後");
        later.setTag("skip");
        Theme.secondary(later, this);
        Theme.compact(later, this);
        later.setOnClickListener(v -> updateBar.setVisibility(View.GONE));
        row.addView(later, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        updateBar.addView(row);
        return updateBar;
    }

    /** 查更新（interactive = 用戶撳掣，會顯示橫額講進度）。 */
    private void checkUpdate(final boolean interactive) {
        if (updater == null) {
            updater = new Update(this, new Update.Listener() {
                @Override
                public void onStatus(String s) {
                    log("⬆️ " + s);
                    if (interactive) {
                        runOnUiThread(() -> {
                            updateStatus.setText(s);
                            updateBar.setVisibility(View.VISIBLE);
                        });
                    }
                }

                @Override
                public void onFound(String versionName, long size) {
                    runOnUiThread(() -> {
                        updateText.setText("⬆️ 有新版本 v" + versionName + "（現時 v" + versionName() + "）");
                        updateStatus.setText(size > 0
                                ? "約 " + (size / 1048576) + " MB｜撳「下載並更新」即刻裝"
                                : "撳「下載並更新」即刻裝");
                        updateBar.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onUpToDate() {
                    runOnUiThread(() -> {
                        if (interactive) {
                            updateText.setText("✅ 已是最新版本");
                            updateStatus.setText("本機 v" + versionName() + " 已經係最新");
                            updateBar.setVisibility(View.VISIBLE);
                        } else {
                            updateBar.setVisibility(View.GONE);
                        }
                    });
                }

                @Override
                public void onProgress(int pct) {
                    runOnUiThread(() -> {
                        updateStatus.setText("下載中 " + pct + "%");
                        updateBar.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onInstalled(boolean handedToInstaller, String savedPath) {
                    runOnUiThread(() -> {
                        updateStatus.setText(handedToInstaller
                                ? "✅ 已下載＋交系統安裝器（tap「返回」，唔好 tap「打開」）"
                                : "已下載到：" + savedPath);
                        updateBar.setVisibility(View.VISIBLE);
                    });
                }
            });
        }
        if (interactive) {
            updateStatus.setText("檢查更新中…");
            updateBar.setVisibility(View.VISIBLE);
        }
        updater.check();
    }

    // ============================================================ 診斷

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            StringWriter sw = new StringWriter();
            sw.write("thread = " + thread.getName() + "\n");
            e.printStackTrace(new PrintWriter(sw));
            String s = sw.toString();
            Diag.saveCrash(getApplicationContext(), s);
            Diag.send(getApplicationContext(), "crash", s);
            if (def != null) def.uncaughtException(thread, e);
        });
    }

    private void fail(String what, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = what + "\n" + sw;
        Diag.saveCrash(this, s);
        Diag.send(this, "ui-fail", s);
        TextView err = new TextView(this);
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        err.setTextColor(0xFFFF5555);
        err.setText("⚠️ " + s + "\n\n（已寫入本機診斷記錄）");
        ScrollView sv = new ScrollView(this);
        sv.addView(err);
        setContentView(sv);
    }

    /** 一次過收集診斷資料（本機記錄用）。 */
    private String snapshot() {
        StringBuilder b = new StringBuilder();
        b.append("uiOk           : ").append(uiOk).append('\n');
        b.append("version        : v").append(versionName()).append(" (code ").append(versionCode()).append(")\n");
        b.append("screen         : ").append(getResources().getDisplayMetrics().widthPixels)
                .append("x").append(getResources().getDisplayMetrics().heightPixels)
                .append(" density=").append(getResources().getDisplayMetrics().density).append('\n');
        b.append("overlay 權限   : ").append(Build.VERSION.SDK_INT >= 23
                ? android.provider.Settings.canDrawOverlays(this) : "n/a").append('\n');
        b.append("錄音權限       : ").append(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED).append('\n');

        b.append("\n--- 輸出裝置（全部）---\n");
        java.util.List<android.media.AudioDeviceInfo> outs = audio.outputs();
        b.append("count = ").append(outs.size()).append('\n');
        for (android.media.AudioDeviceInfo d : outs) {
            String addr = d.getAddress() == null ? "" : d.getAddress();
            b.append("  type=").append(d.getType())
                    .append(" addr=").append(addr)
                    .append(" products=").append(d.getProductName())
                    .append(" sinks=").append(d.isSink()).append('\n');
        }
        android.media.AudioDeviceInfo o = audio.outer();
        b.append("BUS12 揀到     : ").append(o == null ? "❌ 搵唔到" : "✅ " + o.getAddress()
                + " type=" + o.getType()).append('\n');
        b.append("maxVol=").append(audio.maxMusicVolume())
                .append(" musicVol=").append(audio.musicVolume()).append('\n');

        b.append("\n--- 輸入裝置（全部）---\n");
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
                b.append("  type=").append(d.getType())
                        .append(" addr=").append(d.getAddress())
                        .append(" products=").append(d.getProductName()).append('\n');
            }
        }

        b.append("\n--- ECARX / 點火 ---\n");
        Object fn = VehicleRead.carFunction(this);
        b.append("carFunction    : ").append(fn == null ? "❌ 拎唔到" : "✅ " + fn.getClass().getName()).append('\n');
        int raw = VehicleRead.read(this, VehicleRead.ID_IGNITION);
        b.append("點火 0x20259000: ").append(VehicleRead.hex(raw)).append('\n');
        b.append("已學泊車值     : ").append(VehicleRead.hex(sp.getInt("park_raw", Integer.MIN_VALUE))).append('\n');
        b.append("已學行車值     : ").append(VehicleRead.hex(sp.getInt("drive_raw", Integer.MIN_VALUE))).append('\n');
        b.append("auto_enabled   : ").append(autoEnabled()).append('\n');

        b.append("\n--- assets ---\n");
        try {
            for (String n : getAssets().list("")) {
                b.append("  ").append(n).append('\n');
            }
        } catch (Throwable t) {
            b.append("  list 失敗：").append(t).append('\n');
        }

        b.append("\n--- 錄音檔 ---\n");
        java.io.File[] fs = getFilesDir().listFiles((d, n) -> n.startsWith("rec-"));
        b.append("count = ").append(fs == null ? 0 : fs.length).append('\n');

        String crash = Diag.lastCrash(this);
        if (crash != null) {
            b.append("\n--- 上次崩潰 ---\n");
            b.append(crash.length() > 2000 ? crash.substring(0, 2000) : crash).append('\n');
        }
        return b.toString();
    }

    private void selfCheck() {
        log("🩺 自檢：收集資料中…");
        Diag.send(this, "selfcheck", snapshot());
        String crash = Diag.lastCrash(this);
        log("🩺 自檢完成（車外 speaker：" + (audio.outer() == null ? "⚠️ 搵唔到" : "OK") + "）"
                + (crash == null ? "" : " ｜ 有上次崩潰記錄"));
    }

    // ============================================================ UI

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.bgGradient());   // 微漸變，比純色有層次
        int rp = Theme.dp(this, 10);
        root.setPadding(rp, rp, rp, rp);

        // ⚠️ 記錄行一定要**最先**建立：下面建各 tab 時會寫記錄（例如掃咪），
        // 如果等到最後才 new TextView，log() 就會 NPE（v1.6 實測踩過）。
        outLine = new TextView(this);
        Theme.mono(outLine, Theme.TEXT_DIM, 10f);
        outLine.setMaxLines(2);
        outLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        outLine.setText("（操作記錄）");

        // ================= 頂卡（v5，跟參考圖：兩行）=================
        // 行 A：icon + Car Piano ｜ 車內(Internal) / 車外(External) ｜ 齒輪
        // 行 B：喇叭 icon + 音量滑桿 + 「音量」
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(Theme.glassyCard(this, 3));      // status bar 粗邊（用戶要求）
        int tp = Theme.dp(this, 12);
        top.setPadding(tp, tp, tp, tp);

        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        rowA.setGravity(android.view.Gravity.CENTER_VERTICAL);
        backBtn = Theme.button(this);
        backBtn.setText("← 主頁");
        backBtn.setTag("skip");
        Theme.secondary(backBtn, this);
        Theme.compact(backBtn, this);
        backBtn.setVisibility(View.GONE);
        backBtn.setOnClickListener(v -> showPage(0));
        rowA.addView(backBtn);
        android.widget.ImageView appIc = new android.widget.ImageView(this);
        appIc.setImageResource(R.drawable.ic_tab_piano);
        LinearLayout.LayoutParams ailp = new LinearLayout.LayoutParams(Theme.dp(this, 26), Theme.dp(this, 26));
        ailp.leftMargin = Theme.dp(this, 6);
        rowA.addView(appIc, ailp);
        TextView title = new TextView(this);
        title.setText("Car Piano");
        Theme.title(title, 17f);
        LinearLayout.LayoutParams tlpA = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlpA.leftMargin = Theme.dp(this, 8);
        rowA.addView(title, tlpA);

        // 中間：車內／車外 pill（跟參考圖：圖示 + 文字 + (Internal)/(External)）
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        chanTiles.clear();
        chanTileTexts.clear();
        for (int i = 0; i < 2; i++) {
            final int mode = (i == 0) ? 0 : 3;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.HORIZONTAL);
            tile.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int ip = Theme.dp(this, 8);
            tile.setPadding(ip, ip, ip, ip);
            android.widget.ImageView ic = new android.widget.ImageView(this);
            ic.setImageResource(i == 0 ? R.drawable.ic_spk_inside : R.drawable.ic_spk_outside);
            tile.addView(ic, new LinearLayout.LayoutParams(Theme.dp(this, 22), Theme.dp(this, 22)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            Theme.title(t1, 15f);
            t1.setText(i == 0 ? "車內" : "車外");
            TextView t2 = new TextView(this);
            Theme.label(t2, Theme.TEXT_DIM, 10f);
            t2.setText(i == 0 ? "(Internal)" : "(External)");
            tx.addView(t1);
            tx.addView(t2);
            LinearLayout.LayoutParams tlpB = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlpB.leftMargin = Theme.dp(this, 6);
            tile.addView(tx, tlpB);
            tile.setOnClickListener(v -> {
                sp.edit().putInt("in_chan", mode).apply();
                refreshChanTiles();
                refreshStatuses();
                if (recordTab != null) recordTab.onRouteChanged();
                log("🔈 播放來源 → " + (mode == 3 ? "車外" : "車內"));
            });
            chanTiles.add(tile);
            chanTileTexts.add(t1);
            LinearLayout.LayoutParams plp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 1) plp2.leftMargin = Theme.dp(this, 10);      // 兩 pill 留空隙
            pills.addView(tile, plp2);
        }
        rowA.addView(pills);

        android.widget.ImageView gear = new android.widget.ImageView(this);   // 右上齒輪（藍框）
        gear.setImageResource(R.drawable.ic_gear);
        gear.setBackground(Theme.bg(Theme.SURFACE_2, Theme.ACCENT, Theme.R_BTN, this));
        int gp = Theme.dp(this, 7);
        gear.setPadding(gp, gp, gp, gp);
        gear.setClickable(true);
        gear.setContentDescription("設定");
        gear.setOnClickListener(v -> showPage(3));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Theme.dp(this, 42), Theme.dp(this, 42));
        glp.leftMargin = Theme.dp(this, 14);
        rowA.addView(gear, glp);
        top.addView(rowA);

        // ---- 行 B：音量 ----
        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        rowB.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView spkIc = new android.widget.ImageView(this);
        spkIc.setImageResource(R.drawable.ic_spk_outside);
        rowB.addView(spkIc, new LinearLayout.LayoutParams(Theme.dp(this, 24), Theme.dp(this, 24)));
        final int initPct = sp.getInt("vol_pct", 60);
        final com.google.android.material.slider.Slider vol =
                new com.google.android.material.slider.Slider(this);
        vol.setValueFrom(0f);
        vol.setValueTo(100f);
        vol.setStepSize(1f);
        vol.setValue(initPct);
        vol.setThumbRadius(Theme.dp(this, 9));
        vol.setTrackHeight(Theme.dp(this, 5));
        vol.setHaloRadius(Theme.dp(this, 18));
        vol.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
        vol.setTrackInactiveTintList(android.content.res.ColorStateList.valueOf(0xFF2A2F38));
        vol.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT));
        vol.setHaloTintList(android.content.res.ColorStateList.valueOf(Theme.ACCENT_SOFT));
        final TextView volPct = new TextView(this);
        Theme.label(volPct, Theme.TEXT_DIM, 13f);
        volPct.setText(initPct + "%");
        vol.addOnChangeListener((bar, value, fromUser) -> {
            int v = Math.max(0, Math.min(100, Math.round(value)));
            audio.setVolume(Math.max(1, audio.maxMusicVolume() * v / 100));
            Pcm.setGainPercent(v);
            volPct.setText(v + "%");
            refreshStatuses();
        });
        vol.addOnSliderTouchListener(new com.google.android.material.slider.Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(com.google.android.material.slider.Slider s) {
            }

            @Override
            public void onStopTrackingTouch(com.google.android.material.slider.Slider s) {
                int v = Math.round(s.getValue());
                sp.edit().putInt("vol_pct", v).apply();
                log("🔊 音量已記住：" + v + "%");
            }
        });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = Theme.dp(this, 10);
        rowB.addView(vol, vlp);
        TextView volLab = new TextView(this);
        Theme.label(volLab, Theme.TEXT_DIM, 13f);
        volLab.setText("音量");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Theme.dp(this, 10);
        rowB.addView(volPct, llp);
        LinearLayout.LayoutParams llp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp2.leftMargin = Theme.dp(this, 8);
        rowB.addView(volLab, llp2);
        LinearLayout.LayoutParams rblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rblp.topMargin = Theme.dp(this, 8);
        top.addView(rowB, rblp);

        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tclp.bottomMargin = Theme.dp(this, 8);
        root.addView(top, tclp);

        // ================= 內文：全版頁（一次一頁）=================
        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pianoTab = new PianoTab(this, audio, this::log);
        recordTab = new RecordTab(this, audio, this::log);

        showPage(0);          // 開 app 顯示主頁（2×2 面板）

        outLine.setBackground(Theme.bg(Theme.SURFACE, Theme.STROKE, Theme.R_BTN, this));
        int op = Theme.dp(this, 8);
        outLine.setPadding(op, op, op, op);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Theme.dp(this, 6);
        root.addView(outLine, olp);
        return root;
    }


    /** 主頁（v5，跟參考圖）：左 Piano（MIDI 控制器插圖）｜右 Recorder（錄音室咪 + 波形 + 音軌）。 */
    private View buildDashboard() {
        LinearLayout rootD = new LinearLayout(this);
        rootD.setOrientation(LinearLayout.HORIZONTAL);

        // ---------------- 左：Piano ----------------
        LinearLayout pP = panel("Piano", "鍵盤演奏與音色設定");
        android.widget.ImageView hp = new android.widget.ImageView(this);
        hp.setImageResource(R.drawable.hero_midi);
        hp.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        pP.addView(hp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView pGo = new TextView(this);
        Theme.label(pGo, Theme.ACCENT, 14f);
        pGo.setText("按此打開鋼琴 →");
        pP.addView(pGo);
        pP.setOnClickListener(v -> showPage(1));
        rootD.addView(pP, weightLp(1f, true));

        // ---------------- 右：Recorder ----------------
        LinearLayout rP = panel("Recorder", "車內外聲音與音軌管理");
        android.widget.ImageView wv = new android.widget.ImageView(this);
        wv.setImageResource(R.drawable.hero_wave);
        wv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        wv.setAlpha(0.85f);
        LinearLayout.LayoutParams wvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 34));
        wvlp.topMargin = Theme.dp(this, 4);
        rP.addView(wv, wvlp);
        android.widget.ImageView hm = new android.widget.ImageView(this);
        hm.setImageResource(R.drawable.hero_studio);
        hm.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        rP.addView(hm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 音軌 01 / 02
        for (int n = 1; n <= 2; n++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, Theme.R_BTN, this));
            int rp2 = Theme.dp(this, 8);
            row.setPadding(rp2, rp2, rp2, rp2);
            TextView lbl = new TextView(this);
            Theme.label(lbl, Theme.TEXT, 14f);
            String nm = recordingName(n);
            lbl.setText("音軌 0" + n + (nm == null ? "　（未有錄音）" : "　" + nm));
            row.addView(lbl, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            android.widget.ImageView mini = new android.widget.ImageView(this);
            mini.setImageResource(R.drawable.hero_wave);
            mini.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
            mini.setAlpha(nm == null ? 0.25f : 0.9f);
            row.addView(mini, new LinearLayout.LayoutParams(Theme.dp(this, 130), Theme.dp(this, 20)));
            row.setOnClickListener(v -> showPage(2));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Theme.dp(this, 6);
            rP.addView(row, rlp);
        }

        // 狀態（● 待命／錄音中）＋ 聲量條
        LinearLayout lrow = new LinearLayout(this);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        lrow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, 6, this));
        int bp = Theme.dp(this, 3);
        bar.setPadding(bp, bp, bp, bp);
        dashLevelFill = new View(this);
        dashLevelFill.setBackground(Theme.bg(Theme.ACCENT, 0, 5, this));
        bar.addView(dashLevelFill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.001f));
        dashLevelSpace = new View(this);
        bar.addView(dashLevelSpace, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        lrow.addView(bar, new LinearLayout.LayoutParams(0, Theme.dp(this, 20), 1f));
        dashRecState = new TextView(this);
        Theme.label(dashRecState, Theme.OK, 13f);
        dashRecState.setText("● 待命");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Theme.dp(this, 10);
        lrow.addView(dashRecState, slp);
        dashRecCount = new TextView(this);
        Theme.label(dashRecCount, Theme.TEXT_DIM, 12f);
        dashRecCount.setText("錄音 0 段");
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.leftMargin = Theme.dp(this, 8);
        lrow.addView(dashRecCount, clp2);
        LinearLayout.LayoutParams llp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp3.topMargin = Theme.dp(this, 8);
        rP.addView(lrow, llp3);

        TextView rGo = new TextView(this);
        Theme.label(rGo, Theme.ACCENT, 14f);
        rGo.setText("按此打開錄音 →");
        LinearLayout.LayoutParams glp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp2.topMargin = Theme.dp(this, 4);
        rP.addView(rGo, glp2);
        rP.setOnClickListener(v -> showPage(2));
        rootD.addView(rP, weightLp(1f, false));

        refreshChanTiles();
        return rootD;
    }

    /** 頁面頂部 status bar（粗邊）：標題 + 即時狀態。 */
    private View pageStatusBar(int idx) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackground(Theme.glassyCard(this, 3));            // 粗邊
        int p = Theme.dp(this, 10);
        bar.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        Theme.title(t, 17f);
        t.setText(idx == 1 ? "🎹 Piano" : "🎙 Recorder");
        bar.addView(t);
        TextView s = new TextView(this);
        Theme.label(s, Theme.TEXT_DIM, 13f);
        s.setGravity(android.view.Gravity.END);
        bar.addView(s, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (idx == 1) pianoStatus = s;
        else recStatus = s;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theme.dp(this, 8);
        bar.setLayoutParams(lp);
        refreshStatuses();
        return bar;
    }

    private String routeText() {
        return chanMode() == 3 ? "車外" : "車內";
    }

    /** 全部即時狀態（主頁 + 各頁 status bar）。 */
    private void refreshStatuses() {
        int volPct = sp.getInt("vol_pct", 60);
        if (pianoStatus != null) {
            pianoStatus.setText("播放來源：" + routeText() + "　｜　音量 " + volPct + "%　｜　✅ 就緒");
        }
        if (recStatus != null) {
            int n = 0;
            File[] fs = getFilesDir().listFiles((dir, name) -> name.startsWith("rec-"));
            if (fs != null) n = fs.length;
            recStatus.setText((RecordTab.RECORDING ? "● 錄音中" : "● 待命")
                    + "　｜　播放來源：" + routeText() + "　｜　錄音 " + n + " 段"
                    + "　｜　音量 " + volPct + "%");
        }
    }

    /** 第 n 新嘅錄音名（主頁音軌行用）；冇就 null。 */
    private String recordingName(int n) {
        File[] fs = getFilesDir().listFiles((dir, name) -> name.startsWith("rec-")
                && (name.endsWith(".wav") || name.endsWith(".m4a")));
        if (fs == null || fs.length < n) return null;
        java.util.List<File> l = new java.util.ArrayList<>(java.util.Arrays.asList(fs));
        l.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        return l.get(n - 1).getName().replace("rec-", "").replace(".wav", "").replace(".m4a", "");
    }

    private LinearLayout.LayoutParams weightLp(float w, boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, w);
        if (left) lp.rightMargin = Theme.dp(this, Theme.GAP / 2);
        else lp.leftMargin = Theme.dp(this, Theme.GAP / 2);
        return lp;
    }

    /** 面板：標題 + 副題（跟參考圖）。 */
    private LinearLayout panel(String title, String sub) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(Theme.glassyCard(this));
        int pad = Theme.dp(this, 12);
        p.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        Theme.title(t, 18f);
        t.setText(title);
        p.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            Theme.label(s, Theme.TEXT_DIM, 12f);
            s.setText(sub);
            p.addView(s);
        }
        return p;
    }

    private void styleChanTile(LinearLayout tile, boolean active) {
        tile.setBackground(Theme.bg(active ? 0x334DA3FF : 0x14FFFFFF,
                active ? Theme.ACCENT : Theme.STROKE, Theme.R_PILL, this));
    }

    private void refreshChanTiles() {
        for (int i = 0; i < chanTiles.size() && i < 2; i++) {
            boolean active = chanMode() == ((i == 0) ? 0 : 3);
            styleChanTile(chanTiles.get(i), active);
            if (i < chanTileTexts.size()) {
                chanTileTexts.get(i).setTextColor(active ? Theme.ACCENT : Theme.TEXT);
            }
        }
    }

    /** 主頁面板每秒刷新（錄音狀態／聲量／段數）。 */
    private void refreshDashboard() {
        if (dashRecState == null) return;
        boolean rec = RecordTab.RECORDING;
        dashRecState.setText(rec ? "● 錄音中" : "● 待命");
        dashRecState.setTextColor(rec ? Theme.DANGER : Theme.OK);
        int p = Math.max(0, Math.min(100, RecordTab.LAST_LEVEL));
        if (dashLevelFill != null && dashLevelSpace != null) {
            LinearLayout.LayoutParams f = (LinearLayout.LayoutParams) dashLevelFill.getLayoutParams();
            f.weight = Math.max(0.001f, p / 100f);
            dashLevelFill.setLayoutParams(f);
            LinearLayout.LayoutParams s = (LinearLayout.LayoutParams) dashLevelSpace.getLayoutParams();
            s.weight = Math.max(0.001f, (100 - p) / 100f);
            dashLevelSpace.setLayoutParams(s);
        }
        if (dashRecCount != null) {
            File[] fs = getFilesDir().listFiles((dir, name) -> name.startsWith("rec-"));
            dashRecCount.setText("錄音 " + (fs == null ? 0 : fs.length) + " 段");
        }
    }

    /** 顯示其中一頁（0=主頁／1=Piano／2=錄音／3=設定），全版。 */
    private void showPage(int idx) {
        if (pageHost == null) return;
        pageHost.removeAllViews();
        if (pages[idx] == null) {
            if (idx == 0) {
                pages[idx] = buildDashboard();            // 主頁：2×2 面板（跟參考圖）
            } else if (idx == 1 || idx == 2) {
                // 頂部 status bar（粗邊）＋ 內容
                LinearLayout wrap = new LinearLayout(this);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.addView(pageStatusBar(idx));
                if (idx == 1) {
                    wrap.addView(pianoTab.buildUi(), new LinearLayout.LayoutParams(   // 唔用 ScrollView
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));            // → 琴鍵可佔 80%
                } else {
                    ScrollView sv = new ScrollView(this);
                    sv.setFillViewport(true);
                    sv.addView(recordTab.buildUi());
                    wrap.addView(sv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
                }
                pages[idx] = wrap;
            } else {
                ScrollView sv = new ScrollView(this);
                sv.setFillViewport(true);
                sv.addView(buildAutoPanel());
                pages[idx] = sv;
            }
        }
        if (backBtn != null) backBtn.setVisibility(idx == 0 ? View.GONE : View.VISIBLE);
        dashH.removeCallbacks(dashTick);
        dashH.post(dashTick);                  // 狀態每秒更新（各頁 status bar 都跟）
        pageHost.addView(pages[idx], new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabButtons.size(); i++) {
            Theme.segmentIcon(tabButtons.get(i), this, i == idx, TAB_ICONS[i]);
        }
    }

    /** 播放路線（0 = 車內通知／3 = 車外 BUS12）；同錄音頁共用同一個 pref。 */
    private int chanMode() {
        return sp.getInt("in_chan", 0) == 3 ? 3 : 0;
    }

    private void refreshChanButtons() {
        for (int i = 0; i < chanButtons.size() && i < 2; i++) {
            int mode = (i == 0) ? 0 : 3;
            Button b = chanButtons.get(i);
            b.setText(i == 0 ? "車內" : "車外");
            Theme.segmentIcon(b, this, chanMode() == mode,
                    i == 0 ? R.drawable.ic_spk_inside : R.drawable.ic_spk_outside);
        }
    }

    /** 三格版面用（保留舊方法，唔再用）。 */
    private void addColumn(LinearLayout parent, int iconRes, String title, View body, boolean wide) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageResource(iconRes);
        ic.setAlpha(0.95f);
        int s = Theme.dp(this, 22);
        header.addView(ic, new LinearLayout.LayoutParams(s, s));
        TextView tv = new TextView(this);
        tv.setText(title);
        Theme.title(tv, 16f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Theme.dp(this, 10);
        header.addView(tv, tlp);
        col.addView(header);
        col.addView(body);
        Theme.styleTree(col);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                wide ? 1f : 0f);
        if (wide) {
            clp.leftMargin = Theme.dp(this, Theme.GAP);
            clp.rightMargin = Theme.dp(this, Theme.GAP);
        } else {
            clp.bottomMargin = Theme.dp(this, Theme.GAP);
        }
        parent.addView(col, clp);
    }


    /** 懸浮窗狀態文字。 */
    private String floatStateText() {
        boolean on = sp.getBoolean("float_enabled", true);
        boolean perm = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this);
        return (on ? "狀態：已開啟（預設）" : "狀態：已隱藏")
                + (perm ? "" : "｜⚠️ 未授權「懸浮窗」權限");
    }

    /** 按設定開／關懸浮窗（預設開啟）。 */
    private void startFloatIfAllowed() {
        if (!sp.getBoolean("float_enabled", true)) return;
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            pendingFloat = true;
            log("⚠️ 要授權「懸浮窗」先開得 —— 開緊設定頁");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
            return;
        }
        startService(new Intent(this, FloatService.class));
        log("📌 懸浮控制窗已開（預設）");
    }

    /** 卡片外框（統一卡片外觀／圓角／內距）；順手為入面嘅按鈕上車機風格。 */
    private View cardWrap(View inner) {
        // A＋B 級：用 Material 3 卡片（有 elevation／圓角／狀態層）
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        card.setRadius(Theme.dp(this, Theme.R_CARD));
        card.setCardElevation(Theme.dp(this, 3));
        card.setCardBackgroundColor(Theme.SURFACE);
        card.setStrokeColor(Theme.STROKE);
        card.setStrokeWidth(Theme.dp(this, 1));     // v2：幼邊框分格
        int p = Theme.dp(this, Theme.PAD);
        card.setContentPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theme.dp(this, Theme.GAP);
        card.addView(inner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Theme.styleTree(inner);
        card.setLayoutParams(lp);
        return card;
    }

    private View buildAutoPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        Theme.title(t, 15f);
        t.setText("⚙️ 設定");
        col.addView(t);

        TextView how = new TextView(this);
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        how.setTextColor(Theme.TEXT_DIM);
        how.setText("長期輪詢點火值（0x20259000，只讀、免權限），停車／行車自動判斷。");
        col.addView(how);

        autoStatus = new TextView(this);
        autoStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        autoStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        autoStatus.setText("點火值：-- ｜ 狀態：--");
        col.addView(autoStatus);

        androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(this);
        sw.setText("🚗 開車自動開啟／熄車自動關閉");
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        sw.setTextColor(Theme.TEXT);
        sw.setChecked(autoEnabled());
        sw.setOnCheckedChangeListener((v, on) -> {
            sp.edit().putBoolean("auto_enabled", on).apply();
            if (on) {
                startAuto();
                log("🚗 開車自動：ON（服務已起，等點火值變）");
            } else {
                stopService(new Intent(this, AutoService.class));
                log("🛑 開車自動：OFF");
            }
            Diag.send(this, "auto-toggle", "auto_enabled=" + on);
        });
        col.addView(sw);

        // ---- 懸浮控制窗（預設開啟；喺呢頁可以收埋／開返／重設位置）----
        TextView fTitle = new TextView(this);
        Theme.label(fTitle, Theme.TEXT, 13f);
        fTitle.setPadding(0, Theme.dp(this, 8), 0, 0);
        fTitle.setText("懸浮控制窗（第 1 頁 🎤 按住講；第 2 頁起 2×2 Preset 語音）");
        col.addView(fTitle);

        floatState = new TextView(this);
        Theme.label(floatState, Theme.TEXT_DIM, 11f);
        floatState.setText(floatStateText());
        col.addView(floatState);

        LinearLayout frow = new LinearLayout(this);
        frow.setOrientation(LinearLayout.HORIZONTAL);
        Button bShow = Theme.button(this);
        bShow.setText("顯示懸浮窗");
        bShow.setTag("skip");
        Theme.primary(bShow, this);
        Theme.compact(bShow, this);
        Theme.icon(bShow, R.drawable.ic_tab_float);
        bShow.setOnClickListener(v -> {
            sp.edit().putBoolean("float_enabled", true).apply();
            startFloatIfAllowed();
            floatState.setText(floatStateText());
        });
        frow.addView(bShow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button bHide = Theme.button(this);
        bHide.setText("隱藏懸浮窗");
        bHide.setTag("skip");
        Theme.secondary(bHide, this);
        Theme.compact(bHide, this);
        bHide.setOnClickListener(v -> {
            sp.edit().putBoolean("float_enabled", false).apply();
            stopService(new Intent(this, FloatService.class));
            floatState.setText(floatStateText());
            log("已隱藏懸浮控制窗（按「顯示懸浮窗」可以再開）");
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.leftMargin = Theme.dp(this, 6);
        frow.addView(bHide, hlp);
        col.addView(frow);

        Button bReset = Theme.button(this);
        bReset.setText("重設懸浮窗位置");
        bReset.setTag("skip");
        Theme.secondary(bReset, this);
        Theme.compact(bReset, this);
        bReset.setOnClickListener(v -> {
            sp.edit().remove("fx").remove("fy").remove("float_page").apply();
            stopService(new Intent(this, FloatService.class));
            startFloatIfAllowed();
            log("懸浮窗位置已重設（返右上角）");
        });
        col.addView(bReset);

        Button chk = Theme.button(this);
        chk.setText("檢查更新");
        chk.setTag("skip");
        Theme.secondary(chk, this);
        Theme.compact(chk, this);
        Theme.icon(chk, R.drawable.ic_download);
        chk.setOnClickListener(v -> checkUpdate(true));
        col.addView(chk);

        TextView upNote = new TextView(this);
        Theme.label(upNote, Theme.TEXT_DIM, 10f);
        upNote.setText("開 app 會自動查一次；有新版會喺畫面頂顯示提示 → 一撳「下載並更新」即刻下載＋交系統安裝器。");
        col.addView(upNote);

        // ---- 開車自動：只有 ON／OFF（用戶要求刪走 記住泊車值／記住行車值／
        //      模擬開車／模擬塞車／交返自動／立刻讀一次 全部按鈕）----
        return col;
    }

    // ============================================================ 開車自動

    private boolean autoEnabled() {
        return sp.getBoolean("auto_enabled", false);
    }

    private void startAuto() {
        Intent i = new Intent(this, AutoService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable t) {
            log("⚠️ 起自動服務失敗：" + t.getClass().getSimpleName());
        }
    }

    private void learn(String key, String label) {
        int raw = VehicleRead.read(this, VehicleRead.ID_IGNITION);
        if (raw == Integer.MIN_VALUE) {
            log("⚠️ 讀唔到點火值（" + label + "）");
            Diag.send(this, "learn-fail", "讀唔到點火值（" + label + "）");
            return;
        }
        sp.edit().putInt(key, raw).apply();
        log("📌 已記住『" + label + "』值 = " + VehicleRead.hex(raw));
        Diag.send(this, "learn", label + " = " + VehicleRead.hex(raw));
        showAuto(raw, false, "記住『" + label + "』");
    }

    private void sim(boolean on) {
        Intent i = new Intent(this, AutoService.class);
        i.setAction(AutoService.ACTION_SIM);
        i.putExtra("sim", on);
        startService(i);
        Diag.send(this, "sim", "模擬 " + (on ? "開車" : "熄車"));
    }

    private void showAuto(int raw, boolean driving, String note) {
        if (autoStatus == null) return;
        final String s = "點火值：" + VehicleRead.hex(raw)
                + "\n狀態：" + (driving ? "🚗 行車中" : "🅿️ 泊車")
                + (note == null ? "" : " ｜ " + note)
                + "\n服務：" + (autoEnabled() ? "開" : "關");
        autoStatus.post(() -> autoStatus.setText(s));
    }

    // ============================================================ 懸浮掣

    private void toggleFloat() {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            pendingFloat = true;
            log("⚠️ 要授權「懸浮窗」—— 開緊設定頁，開完返 app 會自動彈出");
            Diag.send(this, "float-perm", "未授權懸浮窗，開設定頁");
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                log("開設定頁失敗：" + t.getClass().getSimpleName());
                Diag.send(this, "float-perm-fail", "開唔到授權頁：" + t);
            }
            return;
        }
        startService(new Intent(this, FloatService.class));
        log("📌 懸浮掣已開：🔴 HORN 嗶嗶 ／ 🔵 倒車提示（車外 speaker）");
        Diag.send(this, "float-show", "開懸浮掣");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingFloat && android.provider.Settings.canDrawOverlays(this)) {
            pendingFloat = false;
            startService(new Intent(this, FloatService.class));
            log("📌 授權成功，懸浮掣已開");
            Diag.send(this, "float-show", "授權成功後開懸浮掣");
        }
        int raw = VehicleRead.read(this, VehicleRead.ID_IGNITION);
        int dr = sp.getInt("drive_raw", Integer.MIN_VALUE);
        showAuto(raw, dr != Integer.MIN_VALUE && raw == dr,
                "泊車=" + VehicleRead.hex(sp.getInt("park_raw", Integer.MIN_VALUE)));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("auto_open", false)) {
            log("🚗 開車自動：已拉起 app");
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(stateRx);
        } catch (Throwable ignored) {
        }
        try {
            audio.stopNote();
            Pcm.stopAll();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ============================================================ 基本


    /** 所有操作／錯誤記錄（只保留最後 3 行，避免越寫越高遮住鍵盤）。 */
    private void log(String s) {
        if (outLine == null) {
            // ⚠️ UI 未起好（例如 RecordTab.buildUi() 掃咪時）→ 只入 buffer。
            // 呢一步以前會 NPE：Attempt to invoke setText on a null TextView（v1.6 實測崩潰）。
            logBuf.append(s).append('\n');
            return;
        }
        runOnUiThread(() -> {
            String prev = logBuf.toString();
            logBuf.setLength(0);
            logBuf.append(s).append('\n');
            int lines = 0;
            for (int i = 0; i < prev.length(); i++) {
                if (prev.charAt(i) == '\n') {
                    lines++;
                    if (lines >= 2) {
                        logBuf.append(prev.substring(i + 1));
                        break;
                    }
                }
            }
            outLine.setText(logBuf.toString().trim());
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int versionCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
