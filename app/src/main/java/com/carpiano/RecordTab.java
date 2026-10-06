package com.carpiano;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tab 2：錄音 ＋ **咪位置測試**（v1.3）。
 *
 * 點測「邊個咪／邊個位置」：
 *  1. 揀輸入裝置（列出 AudioManager 全部 input，例如內置咪／後咪／
 *     `BUS09_INPUT_FRONT_PASSENGER`（前客）／`BUS17_INPUT_REAR_SEAT`（後座））
 *  2. 撳 ⏺ 開始 → 對住某個位置講嘢／拍手，睇**即時聲量錶**有冇反應
 *  3. 畫面同時顯示 `實際路由` = `AudioRecord.getRoutedDevice()` → 證實系統真係用咗你揀嗰個
 *  4. 錄完可以 ▶ 播返（只出 BUS12 車外）聽下係邊個位置收到
 *
 * 錄音用 AudioRecord + WAV（唔用 MediaRecorder），因為只有 AudioRecord 可以
 * `setPreferredDevice(咪)` 同讀返 `getRoutedDevice()`。
 */
public class RecordTab {

    interface Logger {
        void out(String s);
    }

    private static final String PREFIX = "rec-";
    /** 主頁面板用：最新聲量 %／錄音狀態。 */
    public static volatile int LAST_LEVEL = 0;
    public static volatile boolean RECORDING = false;
    private static final int REQ_AUDIO = 77;
    private static final int RATE = 44100;

    private final Context ctx;
    private final AudioOut audio;
    private final Logger log;
    private final SharedPreferences sp;

    private TextView status;
    private final List<Button> chanButtons = new ArrayList<>();
    private static final String[] CHAN_LABELS = {"車內", "車外"};
    private static final int[] CHAN_MODES = {0, 3};
    private static final int[] CHAN_ICONS = {R.drawable.ic_spk_inside, R.drawable.ic_spk_outside};
    private TextView meter;
    private Button bRec, bStop;
    private LinearLayout meterBox;
    private View meterFill, meterSpacer;
    private TextView micLine;
    private TextView routeLine;
    private LinearLayout list;
    private LinearLayout slotBar;
    private final List<AudioDeviceInfo> inputs = new ArrayList<>();

    private AudioRecord ar;
    private Thread recThread;
    private volatile boolean recording;
    private File wavFile;
    private RandomAccessFile wavOut;
    private long pcmBytes;
    private MediaPlayer player;
    private boolean started;

    public RecordTab(Context ctx, AudioOut audio, Logger log) {
        this.ctx = ctx;
        this.audio = audio;
        this.log = log;
        this.sp = ctx.getSharedPreferences("piano", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------- 播放路線（v2.2）

    /** 0 = 通知（BUS01_SYS_NOTIFICATION，車內）／3 = 車外 speaker（BUS12）。 */
    private int chanMode() {
        return Prefs.chan(ctx);            // 舊壞值（Boolean）會即場修復，唔會 crash
    }

    private boolean isOuterRoute() {
        return chanMode() == 3;
    }

    private AudioDeviceInfo devForRoute() {
        if (isOuterRoute()) return audio.outer();
        AudioDeviceInfo d = audio.notifyBus();
        return d != null ? d : audio.inner();
    }

    private int usageForRoute() {
        return isOuterRoute() ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION;
    }

    private int contentTypeForRoute() {
        return isOuterRoute() ? AudioAttributes.CONTENT_TYPE_MUSIC
                : AudioAttributes.CONTENT_TYPE_SONIFICATION;
    }

    /** 對應嘅音量流：通知通道跟 STREAM_NOTIFICATION；車外唔動音量。 */
    private int streamForRoute() {
        return isOuterRoute() ? -1 : AudioManager.STREAM_NOTIFICATION;
    }

    private String routeName() {
        if (isOuterRoute()) {
            return audio.outer() == null ? "⚠️ 車外（搵唔到 speaker）" : "車外 speaker";
        }
        AudioDeviceInfo d = devForRoute();
        return d == null ? "⚠️ 車內通知通道（搵唔到）" : "車內 ‑ 通知通道";
    }

    private static String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC:
                return "媒體音量";
            case AudioManager.STREAM_NOTIFICATION:
                return "通知音量";
            case AudioManager.STREAM_ALARM:
                return "警報音量";
            case AudioManager.STREAM_SYSTEM:
                return "系統音量";
            default:
                return "音量流 " + stream;
        }
    }

    private void refreshChanButtons() {
        for (int i = 0; i < chanButtons.size() && i < CHAN_LABELS.length; i++) {
            Button b = chanButtons.get(i);
            boolean active = chanMode() == CHAN_MODES[i];
            b.setText((active ? "✅ " : "") + CHAN_LABELS[i]);
            Theme.segment(b, ctx, active);
        }
    }



    /** 全版錄音頁（v4）：錄音（左）｜音量 level bar（中）｜停止（右）平均分佈。 */
    public View buildUi() {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        col.setPadding(pad, pad, pad, pad);

        // 播放路線（揀選已搬去頂欄中間）
        routeLine = new TextView(ctx);
        Theme.label(routeLine, Theme.TEXT_DIM, 12f);
        col.addView(routeLine);
        updateRouteLine();

        // ---- 狀態行（精簡；錄音掣已搬入右欄上框）----
        micLine = new TextView(ctx);
        Theme.label(micLine, Theme.TEXT_DIM, 11f);
        micLine.setText("實際用嘅咪：（未開始）");
        col.addView(micLine);

        status = new TextView(ctx);
        Theme.label(status, Theme.TEXT, 12f);
        status.setText("狀態：待命");
        col.addView(status);

        // ================= 下方：左右兩欄（用盡畫面闊度）=================
        // 左＝Preset playlist（每個檔案一個框，內含 ▶播放／⏹停止／✎改名／🗑刪除）
        // 右＝錄音（按住錄、放手停，右側聲量條）
        LinearLayout cols = new LinearLayout(ctx);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // ---------- 左欄：Playlist ----------
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(Theme.bg(Theme.SURFACE, Theme.STROKE, Theme.R_CARD, ctx));
        int cp = dp(10);
        left.setPadding(cp, cp, cp, cp);
        TextView lt = new TextView(ctx);
        Theme.title(lt, 16f);
        lt.setText("🎵 Preset Playlist（已儲存語音）");
        left.addView(lt);

        // ---- Preset 1／2／3 位（手動指派）：撳一下揀錄音，長按清空 ----
        slotBar = new LinearLayout(ctx);
        slotBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = dp(6);
        left.addView(slotBar, sblp);
        TextView slotHint = new TextView(ctx);
        Theme.label(slotHint, Theme.TEXT_DIM, 11f);
        slotHint.setText("撳 1／2／3 揀錄音放上去；長按個位清空");
        left.addView(slotHint);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView ls = new ScrollView(ctx);
        ls.setFillViewport(true);
        ls.addView(list);
        LinearLayout.LayoutParams lslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lslp.topMargin = dp(6);
        left.addView(ls, lslp);

        LinearLayout.LayoutParams clp1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        clp1.rightMargin = dp(5);
        cols.addView(left, clp1);

        // ---------- 右欄：錄音 ----------
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);

        // ===== 上框：錄音（按著錄音、放手停止並儲存；左 80% 掣 + 右 20% 垂直聲量條）=====
        LinearLayout recBox = new LinearLayout(ctx);
        recBox.setOrientation(LinearLayout.VERTICAL);
        recBox.setBackground(Theme.bg(Theme.SURFACE, Theme.STROKE, Theme.R_CARD, ctx));
        recBox.setPadding(cp, cp, cp, cp);
        TextView rbt = new TextView(ctx);
        Theme.title(rbt, 16f);
        rbt.setText("🎙 錄音（按住錄音，放手停止並儲存）");
        recBox.addView(rbt);

        LinearLayout recRow = new LinearLayout(ctx);
        recRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rrlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rrlp.topMargin = dp(6);
        recBox.addView(recRow, rrlp);

        // 左 80%：錄音掣（按住錄）
        bRec = Theme.button(ctx);
        bRec.setText("按住錄音");
        bRec.setTag("skip");
        Theme.primary(bRec, ctx);
        bRec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        bRec.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.Drawable micD =
                ctx.getResources().getDrawable(R.drawable.ic_mic_stand, ctx.getTheme());
        int micS = dp(52);
        micD.setBounds(0, 0, micS, micS);
        bRec.setCompoundDrawables(null, micD, null, null);
        bRec.setCompoundDrawablePadding(dp(6));
        final Runnable recOn = () -> {
            if (!recording) startRec();
        };
        bRec.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    recOn.run();
                    v.setBackground(Theme.bg(Theme.DANGER, 0, Theme.R_TILE, ctx));
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (recording) stopRec();
                    Theme.primary((Button) v, ctx);
                    return true;
                default:
                    return true;
            }
        });
        recRow.addView(bRec, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f));

        // 右 20%：垂直聲量條（由下向上升）
        LinearLayout barBox = new LinearLayout(ctx);
        barBox.setOrientation(LinearLayout.VERTICAL);
        barBox.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, 8, ctx));
        int bpad = dp(3);
        barBox.setPadding(bpad, bpad, bpad, bpad);
        meter = new TextView(ctx);                          // 聲量 %（喺 bar 頂，貼住條 bar）
        Theme.label(meter, Theme.TEXT_DIM, 11f);
        meter.setGravity(android.view.Gravity.CENTER);
        meter.setText("0%");
        barBox.addView(meter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meterSpacer = new View(ctx);                        // 上面空位（條 bar 由下向上升）
        barBox.addView(meterSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        meterFill = new View(ctx);                          // 下面填充 → 向上升
        meterFill.setBackground(Theme.bg(Theme.ACCENT, 0, 6, ctx));
        barBox.addView(meterFill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.001f));
        LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.2f);
        bblp.leftMargin = dp(8);
        recRow.addView(barBox, bblp);


        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        right.addView(recBox, recLp);


        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        clp2.leftMargin = dp(5);
        cols.addView(right, clp2);

        LinearLayout.LayoutParams colsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        colsLp.topMargin = dp(10);
        col.addView(cols, colsLp);

        refreshList();
        refreshInputs();
        refreshSlots();
        return col;
    }

    /** 把一件控件包成「正方形 + 佔 1/3 寬（左中右平均分佈）」。 */
    private View squareSlot(View inner, int sq) {
        LinearLayout slot = new LinearLayout(ctx);
        slot.setGravity(android.view.Gravity.CENTER);
        slot.addView(inner, new LinearLayout.LayoutParams(sq, sq));
        slot.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return slot;
    }

    /** 頂欄改咗播放路線 → 更新本頁顯示。 */
    public void onRouteChanged() {
        updateRouteLine();
        log.out("🎙 播放路線 → " + routeName() + "（由頂欄改）");
    }

    private void updateRouteLine() {
        if (routeLine == null) return;
        routeLine.setText("播放路線：" + routeName() + "　（喺頂欄中間改）");
    }

    /** 改名（用戶要求 8：錄完嘅名可以自行修改及儲存）。 */
    public void promptRename(final File f) {
        final String oldName = f.getName();
        String cur = f.getName().replace(PREFIX, "").replace(".wav", "").replace(".m4a", "");
        final android.widget.EditText et = new android.widget.EditText(ctx);
        et.setText(cur);
        et.setSelectAllOnFocus(true);
        int pp = dp(12);
        et.setPadding(pp, pp, pp, pp);
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("✎ 改名（存落就即刻生效）")
                .setView(et)
                .setPositiveButton("儲存", (d, w) -> {
                    String nm = et.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (nm.isEmpty()) nm = cur;
                    String ext = f.getName().endsWith(".m4a") ? ".m4a" : (f.getName().endsWith(".mp3") ? ".mp3" : ".wav");
                    File nf = new File(ctx.getFilesDir(), PREFIX + nm + ext);
                    boolean ok = !nf.exists() && f.renameTo(nf);
                    if (!ok) {
                        nf = new File(ctx.getFilesDir(), PREFIX + nm + "-" + System.currentTimeMillis() + ext);
                        ok = f.renameTo(nf);
                    }
                    if (ok) {
                        for (int i = 1; i <= Slots.COUNT; i++) {
                            if (oldName.equals(Slots.get(ctx, i))) Slots.set(ctx, i, nf.getName());
                        }
                    }
                    say(ok ? "✎ 已改名：" + nm : "⚠️ 改名失敗（名可能已用）");
                    log.out("🎙 ✎ 改名 " + f.getName() + " → " + nm + "："
                            + (ok ? "成功" : "失敗"));
                    refreshList();
                    refreshSlots();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 輸入裝置

    /** 掃描輸入裝置（只為診斷／顯示；唔會指定，交系統自己路由）。 */
    private void refreshInputs() {
        inputs.clear();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                inputs.add(d);
            }
        }
        if (!started) {
            started = true;
            log.out("🎙 掃到 " + inputs.size() + " 個輸入裝置（唔使揀，交系統自己路由）");
        }
    }

    /**
     * 唔指定輸入裝置 —— 交系統自己路由。
     *
     * <p>（之前試過列出咪俾人揀，但用戶要求唔要選擇 UI；錄音交系統處理最穩陣，
     * 實際用咗邊個咪會喺「實際用嘅咪」一行顯示（{@code getRoutedDevice()}）。）</p>
     */
    private AudioDeviceInfo selectedInput() {
        return null;
    }

    // ---------------------------------------------------------------- 錄音

    private void askPermission() {
        if (Build.VERSION.SDK_INT >= 23
                && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(
                        new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            }
        }
    }

    private void startRec() {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            say("⚠️ 需要麥克風權限 —— 彈出授權視窗，按「允許」再撳一次");
            askPermission();
            return;
        }
        stopPlayer();
        final AudioDeviceInfo dev = selectedInput();
        try {
            wavFile = new File(ctx.getFilesDir(), PREFIX
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                    + (dev == null ? "-default" : "-mic" + dev.getId()) + ".wav");
            wavOut = new RandomAccessFile(wavFile, "rw");
            writeWavHeader(wavOut, 0);          // 收工時再補返正確長度
            pcmBytes = 0;

            int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, RATE);          // ~1 秒
            ar = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .build();
            if (dev != null) {
                try {
                    ar.setPreferredDevice(dev);
                } catch (Throwable e) {
                    log.out("🎙 setPreferredDevice 失敗：" + e.getClass().getSimpleName());
                }
            }
            ar.startRecording();
            recording = true;
            RECORDING = true;
            say("⏺ 錄音中…（對住要測嘅位置講嘢，睇聲量錶）");
            log.out("🎙 開始錄音 → " + wavFile.getName() + "（咪：系統自動路由）");
            refreshRoute("錄音中");
            recThread = new Thread(this::recordLoop, "piano-rec");
            recThread.start();
        } catch (Throwable e) {
            say("錄音失敗：" + e.getClass().getSimpleName() + " " + e.getMessage());
            log.out("🎙 錄音失敗 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            cleanupRec();
        }
    }

    /** 錄 PCM → WAV，同時每 ~120 ms 更新聲量錶（測試咪位置用）。 */
    private void recordLoop() {
        short[] buf = new short[2048];
        long lastUi = 0;
        double peak = 0;
        try {
            while (recording && ar != null) {
                int n = ar.read(buf, 0, buf.length);
                if (n > 0) {
                    double sum = 0;
                    for (int i = 0; i < n; i++) {
                        double v = buf[i] / 32768.0;
                        sum += v * v;
                    }
                    double rms = Math.sqrt(sum / n);
                    if (rms > peak) peak = rms;
                    byte[] bytes = new byte[n * 2];
                    for (int i = 0; i < n; i++) {
                        bytes[i * 2] = (byte) (buf[i] & 0xFF);
                        bytes[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xFF);
                    }
                    wavOut.write(bytes);
                    pcmBytes += bytes.length;
                }
                long now = System.currentTimeMillis();
                if (now - lastUi > 120) {
                    lastUi = now;
                    final double r = peak;
                    peak = 0;
                    uiMeter(r);
                }
            }
        } catch (Throwable e) {
            // log.out 內部已經 runOnUiThread，唔可以喺度再包 ctx.post（Context 冇 post）
            log.out("🎙 錄音中斷 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void uiMeter(double rms) {
        int pct = (int) Math.min(100, Math.round(rms * 6 * 100 / 3));   // ×6 易睇，上限 100%
        setLevel(pct);
    }

    /** 更新放大咗嘅 level bar（weight 控制填充比例）。 */
    private void setLevel(final int pctRaw) {
        final int p = Math.max(0, Math.min(100, pctRaw));
        if (meter == null) return;
        meter.post(() -> {
            meter.setText(p + "%");
            LAST_LEVEL = p;
            if (meterFill != null && meterSpacer != null) {
                LinearLayout.LayoutParams f = (LinearLayout.LayoutParams) meterFill.getLayoutParams();
                f.weight = Math.max(0.001f, p / 100f);
                meterFill.setLayoutParams(f);
                LinearLayout.LayoutParams s2 = (LinearLayout.LayoutParams) meterSpacer.getLayoutParams();
                s2.weight = Math.max(0.001f, (100 - p) / 100f);
                meterSpacer.setLayoutParams(s2);
            }
        });
    }

    private void stopRec() {
        if (!recording) return;
        recording = false;
        RECORDING = false;
        try {
            if (recThread != null) recThread.join(1500);
        } catch (InterruptedException ignored) {
        }
        long ms = pcmBytes * 1000 / (RATE * 2);
        cleanupRec();
        if (ms < 300 && wavFile != null) {          // 太短（誤觸）→ 唔留檔
            File bad = wavFile;
            boolean del = bad.delete();
            say("⏱ 太短（" + ms + "ms）→ 唔儲存");
            log.out("🎙 太短略過 " + bad.getName() + " 刪除=" + del);
            refreshList();
            return;
        }
        String msg = "⏹ 錄完：" + (wavFile == null ? "?" : wavFile.getName())
                + "（" + (ms / 1000) + " 秒、" + (pcmBytes / 1024) + " KB）";
        say(msg);
        log.out("🎙 " + msg);
        refreshRoute("錄完");
        refreshList();
        // 用戶要求 8：錄完即刻可以改名（可以按「取消」保留原名）
        final File done = wavFile;
        if (done != null && done.exists() && pcmBytes > RATE) {
            if (status != null) status.postDelayed(() -> promptRename(done), 600);
        }
    }

    private void cleanupRec() {
        try {
            if (ar != null) {
                ar.stop();
                ar.release();
            }
        } catch (Throwable ignored) {
        }
        ar = null;
        try {
            if (wavOut != null) {
                writeWavHeader(wavOut, pcmBytes);      // 補返 header 長度
                wavOut.close();
            }
        } catch (Throwable ignored) {
        }
        wavOut = null;
    }

    private static void writeWavHeader(RandomAccessFile f, long dataBytes) throws Exception {
        long saved = f.getFilePointer();
        f.seek(0);
        int dataLen = (int) Math.min(Integer.MAX_VALUE, dataBytes);
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write("RIFF".getBytes("US-ASCII"));
        b.write(le(36 + dataLen, 4));
        b.write("WAVE".getBytes("US-ASCII"));
        b.write("fmt ".getBytes("US-ASCII"));
        b.write(le(16, 4));
        b.write(le(1, 2));                 // PCM
        b.write(le(1, 2));                 // mono
        b.write(le(RATE, 4));
        b.write(le(RATE * 2, 4));          // byte rate
        b.write(le(2, 2));                 // block align
        b.write(le(16, 2));                // bits
        b.write("data".getBytes("US-ASCII"));
        b.write(le(dataLen, 4));
        f.write(b.toByteArray());
        f.seek(saved);
    }

    private static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((v >> (8 * i)) & 0xFF);
        return b;
    }

    private void refreshRoute(String phase) {
        if (routeLine == null) return;
        String s;
        try {
            AudioDeviceInfo d = ar == null ? null : ar.getRoutedDevice();
            s = d == null ? "（系統未報）" : micName(d);
        } catch (Throwable t) {
            s = "讀唔到：" + t.getClass().getSimpleName();
        }
        final String m = "實際用嘅咪（" + phase + "）：" + s;
        if (micLine != null) micLine.post(() -> micLine.setText(m));
        log.out("🎙 " + m);
    }

    /** 咪嘅位置名（介面只顯示位置，唔顯示 BUS 代號／type 數字）。 */
    static String micName(AudioDeviceInfo d) {
        if (d == null) return "（未報）";
        String a = d.getAddress() == null ? "" : d.getAddress().toUpperCase();
        if (a.contains("BUS09")) return "前座客位咪";
        if (a.contains("BUS17")) return "後座咪";
        if (a.contains("BUS16")) return "後座咪";
        if (a.contains("BUS04")) return "前座咪";
        if (a.contains("BUS00")) return "車廂咪";
        if (a.contains("BUS100")) return "藍牙咪";
        if (a.contains("ICC")) return "中控咪";
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "內置咪";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "藍牙咪";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB 咪";
            default:
                return "車廂咪";
        }
    }

    // ---------------------------------------------------------------- 播放

    /**
     * 播一段錄音 —— 用介面上揀嘅播放路線（🔔 車內通知通道 ／ 🚗 車外 speaker）。
     */
    private void play(final File f) {
        stopPlayer();
        Pcm.stopAll();
        final AudioDeviceInfo dev = devForRoute();
        final int usage = usageForRoute();
        final int ct = contentTypeForRoute();
        final String where = routeName();
        final int stream = streamForRoute();
        // 通知通道：如果通知音量太細，順手調返上去（會顯示喺狀態行，唔會靜靜改）
        if (stream >= 0) {
            int max = audio.maxVolume(stream);
            int cur = audio.volume(stream);
            if (max > 0 && cur >= 0 && cur < max * 60 / 100) {
                audio.setVolume(stream, max * 70 / 100);
                say("已把" + streamName(stream) + "調到 70%（原本 " + cur + "/" + max + "）");
                log.out("🎙 " + streamName(stream) + " " + cur + "/" + max + " → " + (max * 70 / 100));
            }
        }
        Diag.send(ctx, "rec-play", "播 " + f.getName() + "｜去=" + where
                + "｜裝置=" + Pcm.devDesc(dev) + "｜usage=" + Pcm.usageName(usage)
                + "｜音量流=" + (stream < 0 ? "（唔改）"
                        : streamName(stream) + " " + audio.volume(stream) + "/" + audio.maxVolume(stream)));
        if (f.getName().endsWith(".wav")) {
            log.out("🎙 ▶ " + f.getName() + " → " + where);
            Pcm.playFile(ctx, f, dev, usage, ct, msg -> {
                say("▶ " + f.getName() + "（" + where + "）｜" + msg);
                log.out("🎙 " + msg);
                Diag.send(ctx, "rec-play-done", f.getName() + "（" + where + "）→ " + msg);
            });
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            AudioOut.setPlayerDevice(player, dev);
            player.setOnCompletionListener(mp -> {
                say("✅ 播完：" + f.getName());
                stopPlayer();
            });
            player.start();
            say("▶ 播 " + f.getName() + " → " + where);
            log.out("🎙 ▶ " + f.getName() + " → " + where);
        } catch (Throwable e) {
            say("播放失敗：" + e.getClass().getSimpleName() + " " + e.getMessage());
            log.out("🎙 播放失敗 " + e.getClass().getSimpleName() + ": " + e.getMessage());
            stopPlayer();
        }
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

    private void stopAll() {
        stopRec();
        stopPlayer();
    }

    // ---------------------------------------------------------------- 清單

    private void refreshList() {
        if (list == null) return;
        list.removeAllViews();
        File[] fs = ctx.getFilesDir().listFiles((dir, name) ->
                name.startsWith(PREFIX) && (name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".mp3")));
        if (fs == null || fs.length == 0) {
            TextView tv = new TextView(ctx);
            tv.setText("（未有錄音）");
            list.addView(tv);
            return;
        }
        List<File> files = new ArrayList<>(Arrays.asList(fs));
        files.sort(Comparator.comparingLong(File::lastModified).reversed());
        int shown = 0;
        for (final File f : files) {
            if (shown++ >= 30) break;
            // ---- 每個已儲存語音：一個框，內含 ▶播放／⏹停止／✎改名／🗑刪除（用戶要求）----
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, Theme.R_BTN, ctx));
            int bp = dp(8);
            box.setPadding(bp, bp, bp, bp);

            String bare = f.getName().replace(PREFIX, "")
                    .replace(".wav", "").replace(".m4a", "").replace(".mp3", "");
            TextView nm = new TextView(ctx);
            Theme.label(nm, Theme.TEXT, 14f);
            String when = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(f.lastModified()));
            int slot = Slots.slotOf(ctx, f.getName());
            nm.setText((slot > 0 ? "【" + slot + "】 " : "") + bare
                    + "　" + (f.length() / 1024) + "KB　" + when);
            box.addView(nm);

            // ---- 放去 Preset 1／2／3（手動指派）----
            LinearLayout arow = new LinearLayout(ctx);
            arow.setOrientation(LinearLayout.HORIZONTAL);
            arow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView al = new TextView(ctx);
            Theme.label(al, Theme.TEXT_DIM, 12f);
            al.setText("放去　");
            arow.addView(al, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int n = 1; n <= Slots.COUNT; n++) {
                final int nn = n;
                Button ab = Theme.button(ctx);
                ab.setTag("skip");
                ab.setText(String.valueOf(nn));
                ab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                ab.setGravity(android.view.Gravity.CENTER);
                if (slot == nn) Theme.primary(ab, ctx); else Theme.secondary(ab, ctx);
                Theme.compact(ab, ctx);
                ab.setOnClickListener(v -> {
                    Slots.put(ctx, nn, f.getName());
                    say("✅ " + bare + " → Preset " + nn);
                    log.out("🎙 " + f.getName() + " → Preset " + nn);
                    refreshList();
                    refreshSlots();
                });
                LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(
                        dp(54), ViewGroup.LayoutParams.WRAP_CONTENT);
                ablp.rightMargin = dp(6);
                arow.addView(ab, ablp);
            }
            LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            arlp.topMargin = dp(4);
            box.addView(arow, arlp);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(6);
            box.addView(row, rlp);

            Button playB = Theme.button(ctx);
            playB.setText("播放");
            playB.setTag("skip");
            Theme.secondary(playB, ctx);
            Theme.compact(playB, ctx);
            Theme.icon(playB, R.drawable.ic_play);
            playB.setOnClickListener(v -> play(f));
            row.addView(playB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button stopB = Theme.button(ctx);
            stopB.setText("停止");
            stopB.setTag("skip");
            Theme.secondary(stopB, ctx);
            Theme.compact(stopB, ctx);
            Theme.icon(stopB, R.drawable.ic_stop);
            stopB.setOnClickListener(v -> {
                Pcm.stopAll();
                stopPlayer();
                say("⏹ 已停止播放");
                log.out("🎙 ⏹ 停止播放 " + f.getName());
            });
            row.addView(stopB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button rn = Theme.button(ctx);
            rn.setText("改名");
            rn.setTag("skip");
            Theme.secondary(rn, ctx);
            Theme.compact(rn, ctx);
            rn.setOnClickListener(v -> promptRename(f));
            row.addView(rn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = Theme.button(ctx);
            del.setText("");
            del.setTag("skip");
            Theme.danger(del, ctx);
            Theme.compact(del, ctx);
            Theme.icon(del, R.drawable.ic_trash);
            del.setOnClickListener(v -> {
                for (int i = 1; i <= Slots.COUNT; i++) {
                    if (f.getName().equals(Slots.get(ctx, i))) Slots.set(ctx, i, null);
                }
                boolean ok = f.delete();
                log.out("🎙 刪除 " + f.getName() + " → " + ok);
                refreshList();
                refreshSlots();
            });
            row.addView(del, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));

            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxLp.topMargin = dp(6);
            list.addView(box, boxLp);
        }
    }

    /** 重畫 1／2／3 位（有指派就顯示名，冇就「未設定」）。 */
    private void refreshSlots() {
        if (slotBar == null) return;
        slotBar.removeAllViews();
        for (int n = 1; n <= Slots.COUNT; n++) {
            final int nn = n;
            File f = Slots.file(ctx, nn);
            Button b = Theme.button(ctx);
            b.setTag("skip");
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            b.setGravity(android.view.Gravity.CENTER);
            b.setText(nn + "　" + (f == null ? "未設定" : Slots.bare(f.getName())));
            if (f == null) Theme.secondary(b, ctx); else Theme.primary(b, ctx);
            Theme.compact(b, ctx);
            b.setOnClickListener(v -> pickSlot(nn));
            b.setOnLongClickListener(v -> {
                if (Slots.get(ctx, nn) != null) {
                    Slots.set(ctx, nn, null);
                    say("位 " + nn + " 已清空");
                    log.out("🎙 位 " + nn + " 清空");
                    refreshSlots();
                    refreshList();
                }
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (nn > 1) lp.leftMargin = dp(6);
            slotBar.addView(b, lp);
        }
    }

    /** 揀一個已錄音效放入位 n。 */
    private void pickSlot(final int n) {
        final List<File> recs = Slots.recs(ctx);
        if (recs.isEmpty()) {
            say("（未有錄音，先錄一段）");
            return;
        }
        final String[] names = new String[recs.size()];
        for (int i = 0; i < recs.size(); i++) {
            File f = recs.get(i);
            int at = Slots.slotOf(ctx, f.getName());
            names[i] = (at > 0 ? "【" + at + "】" : "") + Slots.bare(f.getName());
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("Preset " + n + " 放邊段錄音？")
                .setItems(names, (d, i) -> {
                    Slots.put(ctx, n, recs.get(i).getName());
                    say("✅ " + Slots.bare(recs.get(i).getName()) + " → Preset " + n);
                    log.out("🎙 " + recs.get(i).getName() + " → Preset " + n);
                    refreshSlots();
                    refreshList();
                })
                .setNeutralButton("清空呢個位", (d, w) -> {
                    Slots.set(ctx, n, null);
                    refreshSlots();
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void say(String s) {
        final String m = s;
        if (status != null) status.post(() -> status.setText("狀態：" + m));
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
