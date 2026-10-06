package com.carpiano.app;

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
 * v1.6 加咗**診斷上報**：app 一起、起完 UI、崩潰、每次播音都上報一次
 * → 出事可以喺 app 內睇返，唔使隔空猜。
 */
public class MainActivity extends Activity {

    private AudioOut audio;
    private LinearLayout content;
    private Button tabPiano, tabRec, tabAuto;
    private TextView outLine;
    private TextView autoStatus;
    private Button autoToggle;
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

        showTab(0);
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

    /** 一次過收集診斷資料（上報用）。 */
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

        // ⚠️ 記錄行一定要**最先**建立：下面建各 tab 時會寫記錄（例如掃咪），
        // 如果等到最後才 new TextView，log() 就會 NPE（v1.6 實測踩過）。
        outLine = new TextView(this);
        outLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        outLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        outLine.setMaxLines(2);
        outLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        outLine.setText("（操作記錄）");

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView ver = new TextView(this);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        ver.setText("🎹 Car Piano v" + versionName() + "\n🚗 車外 speaker："
                + (audio.outer() == null ? "搵唔到" : "就緒"));
        head.addView(ver, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button check = new Button(this);
        check.setText("🩺 自檢（本機）");
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        check.setOnClickListener(v -> selfCheck());
        head.addView(check, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        String crash = Diag.lastCrash(this);
        if (crash != null) {
            TextView c = new TextView(this);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            c.setTextColor(0xFFFF8888);
            c.setMaxLines(3);
            c.setText("⚠️ 上次有崩潰記錄（按 🩺 自檢可查看）："
                    + crash.replace('\n', ' ').substring(0, Math.min(160, crash.length())));
            root.addView(c);
        }

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabPiano = new Button(this);
        tabPiano.setText("🎹 鋼琴");
        tabPiano.setOnClickListener(v -> showTab(0));
        tabRow.addView(tabPiano, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabRec = new Button(this);
        tabRec.setText("🎙 錄音");
        tabRec.setOnClickListener(v -> showTab(1));
        tabRow.addView(tabRec, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabAuto = new Button(this);
        tabAuto.setText("⚙️ 設定");
        tabAuto.setOnClickListener(v -> showTab(2));
        tabRow.addView(tabAuto, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button tabFloat = new Button(this);
        tabFloat.setText("📌 懸浮掣");
        tabFloat.setOnClickListener(v -> toggleFloat());
        tabRow.addView(tabFloat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabRow);

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        pianoTab = new PianoTab(this, audio, this::log);
        recordTab = new RecordTab(this, audio, this::log);
        int pad = dp(6);
        content.setPadding(pad, pad, pad, pad);
        content.addView(pianoTab.buildUi(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(recordTab.buildUi(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(buildAutoPanel(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(outLine);
        return root;
    }

    private View buildAutoPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setText("⚙️ 設定 — 🚗 開車自動開啟／熄車自動關閉");
        col.addView(t);

        TextView how = new TextView(this);
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        how.setText("做法：長期輪詢點火值（0x20259000，只讀、免權限）。編碼唔靠猜 —— "
                + "你停車時記住一個值、行車時記住另一個，之後自動比較。\n"
                + "⚠️ 容器唔派開機廣播，所以「開車自動」生效期間要 app 一直喺度（服務挨得過深睡）。");
        col.addView(how);

        autoStatus = new TextView(this);
        autoStatus.setTypeface(android.graphics.Typeface.MONOSPACE);
        autoStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        autoStatus.setText("點火值：-- ｜ 狀態：--");
        col.addView(autoStatus);

        autoToggle = new Button(this);
        autoToggle.setText(autoEnabled() ? "🛑 停用開車自動" : "🚗 啟用開車自動");
        autoToggle.setOnClickListener(v -> {
            boolean on = autoEnabled();
            sp.edit().putBoolean("auto_enabled", !on).apply();
            if (on) {
                stopService(new Intent(this, AutoService.class));
                log("🛑 已停用開車自動");
            } else {
                startAuto();
                log("🚗 已啟用開車自動（服務已起，等點火值變）");
            }
            autoToggle.setText(autoEnabled() ? "🛑 停用開車自動" : "🚗 啟用開車自動");
            Diag.send(this, "auto-toggle", "auto_enabled=" + autoEnabled());
        });
        col.addView(autoToggle);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button learnPark = new Button(this);
        learnPark.setText("📌 記住『泊車』值");
        learnPark.setOnClickListener(v -> learn("park_raw", "泊車"));
        row1.addView(learnPark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button learnDrive = new Button(this);
        learnDrive.setText("📌 記住『行車』值");
        learnDrive.setOnClickListener(v -> learn("drive_raw", "行車"));
        row1.addView(learnDrive, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button simOn = new Button(this);
        simOn.setText("▶ 模擬開車（測試）");
        simOn.setOnClickListener(v -> {
            sim(true);
            log("▶ 人手模擬：開車");
        });
        row2.addView(simOn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button simOff = new Button(this);
        simOff.setText("⏹ 模擬熄車（測試）");
        simOff.setOnClickListener(v -> {
            sim(false);
            log("⏹ 人手模擬：熄車");
        });
        row2.addView(simOff, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button clearSim = new Button(this);
        clearSim.setText("↺ 交返自動");
        clearSim.setOnClickListener(v -> {
            Intent i = new Intent(this, AutoService.class);
            i.setAction(AutoService.ACTION_SIM);
            i.putExtra("clear", true);
            startService(i);
            log("↺ 取消模擬，交返自動判斷");
        });
        row3.addView(clearSim, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button readNow = new Button(this);
        readNow.setText("🔍 即刻讀一次");
        readNow.setOnClickListener(v -> {
            int raw = VehicleRead.read(this, VehicleRead.ID_IGNITION);
            showAuto(raw, false, "單次讀取");
            log("🔍 點火值 = " + VehicleRead.hex(raw));
            Diag.send(this, "ignition-read", "raw = " + VehicleRead.hex(raw));
        });
        row3.addView(readNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row3);

        TextView learned = new TextView(this);
        learned.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        learned.setText("已學：泊車 " + VehicleRead.hex(sp.getInt("park_raw", Integer.MIN_VALUE))
                + " ｜ 行車 " + VehicleRead.hex(sp.getInt("drive_raw", Integer.MIN_VALUE)));
        col.addView(learned);

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

    private void showTab(int idx) {
        if (content == null || content.getChildCount() < 3) return;
        for (int i = 0; i < 3; i++) {
            content.getChildAt(i).setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        tabPiano.setEnabled(idx != 0);
        tabRec.setEnabled(idx != 1);
        tabAuto.setEnabled(idx != 2);
        log(idx == 0 ? "→ 🎹 鋼琴" : idx == 1 ? "→ 🎙 錄音" : "→ ⚙️ 設定");
    }

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
