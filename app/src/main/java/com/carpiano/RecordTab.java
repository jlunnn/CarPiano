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
    private android.widget.EditText ttsText, ttsName;
    private TextView ttsStatus;
    private TextView routeLine;
    private LinearLayout list;
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
        int m = sp.getInt("in_chan", 0);
        return m == 3 ? 3 : 0;            // 舊值（媒體／導航）一律當通知
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

        // ---- 三大件：正方形，左中右平均分佈（用戶 2026-10-07 要求）----
        int sq = dp(150);                                  // 正方形邊長
        LinearLayout ctlRow = new LinearLayout(ctx);
        ctlRow.setOrientation(LinearLayout.HORIZONTAL);
        ctlRow.setGravity(android.view.Gravity.CENTER);

        // 左：錄音（正方形，咪 stand 圖示）
        bRec = Theme.button(ctx);
        bRec.setText("開始錄音");
        bRec.setTag("skip");
        Theme.primary(bRec, ctx);
        bRec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        bRec.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.Drawable micD =
                ctx.getResources().getDrawable(R.drawable.ic_mic_stand, ctx.getTheme());
        int micS = dp(58);
        micD.setBounds(0, 0, micS, micS);
        bRec.setCompoundDrawables(null, micD, null, null);
        bRec.setCompoundDrawablePadding(dp(6));
        bRec.setOnClickListener(v -> startRec());
        ctlRow.addView(squareSlot(bRec, sq));

        // 中：音量 level bar（正方形）
        LinearLayout meterCol = new LinearLayout(ctx);
        meterCol.setOrientation(LinearLayout.VERTICAL);
        meterCol.setGravity(android.view.Gravity.CENTER);
        meterCol.setBackground(Theme.bg(Theme.SURFACE, Theme.STROKE, Theme.R_TILE, ctx));
        int ip = dp(14);
        meterCol.setPadding(ip, ip, ip, ip);
        meter = new TextView(ctx);
        Theme.label(meter, Theme.TEXT, 20f);
        meter.setGravity(android.view.Gravity.CENTER);
        meter.setText("0%");
        meterCol.addView(meter);
        TextView mLabel = new TextView(ctx);
        Theme.label(mLabel, Theme.TEXT_DIM, 12f);
        mLabel.setGravity(android.view.Gravity.CENTER);
        mLabel.setText("聲量");
        meterCol.addView(mLabel);
        meterBox = new LinearLayout(ctx);
        meterBox.setOrientation(LinearLayout.HORIZONTAL);
        meterBox.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, 8, ctx));
        int mp = dp(3);
        meterBox.setPadding(mp, mp, mp, mp);
        meterFill = new View(ctx);
        meterFill.setBackground(Theme.bg(Theme.ACCENT, 0, 6, ctx));
        meterBox.addView(meterFill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.001f));
        meterSpacer = new View(ctx);
        meterBox.addView(meterSpacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams mbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        mbp.topMargin = dp(10);
        meterCol.addView(meterBox, mbp);
        ctlRow.addView(squareSlot(meterCol, sq));

        // 右：停止（正方形）
        bStop = Theme.button(ctx);
        bStop.setText("停止");
        bStop.setTag("skip");
        Theme.danger(bStop, ctx);
        bStop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        bStop.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.Drawable stD =
                ctx.getResources().getDrawable(R.drawable.ic_stop, ctx.getTheme());
        stD.setBounds(0, 0, micS, micS);
        stD.setTint(Theme.DANGER_TEXT);
        bStop.setCompoundDrawables(null, stD, null, null);
        bStop.setCompoundDrawablePadding(dp(6));
        bStop.setOnClickListener(v -> stopAll());
        ctlRow.addView(squareSlot(bStop, sq));

        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(10);
        col.addView(ctlRow, crlp);

        micLine = new TextView(ctx);
        Theme.label(micLine, Theme.TEXT_DIM, 11f);
        micLine.setText("實際用嘅咪：（未開始）");
        col.addView(micLine);

        status = new TextView(ctx);
        Theme.label(status, Theme.TEXT, 12f);
        status.setText("狀態：待命");
        col.addView(status);

        // ---- 英文 TTS → Preset 語音（用戶 2026-10-07 要求）----
        TextView ttsTitle = new TextView(ctx);
        Theme.title(ttsTitle, 15f);
        ttsTitle.setText("🔤 英文 TTS → Preset 語音");
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttlp.topMargin = dp(10);
        col.addView(ttsTitle, ttlp);

        ttsText = new android.widget.EditText(ctx);
        ttsText.setHint("打英文句子（例如：Please move aside, thank you.）");
        ttsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        ttsText.setMaxLines(2);
        ttsText.setTextColor(Theme.TEXT);
        ttsText.setHintTextColor(Theme.TEXT_DIM);
        ttsText.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, Theme.R_BTN, ctx));
        int ep = dp(10);
        ttsText.setPadding(ep, ep, ep, ep);
        col.addView(ttsText);

        LinearLayout ttsRow = new LinearLayout(ctx);
        ttsRow.setOrientation(LinearLayout.HORIZONTAL);
        ttsRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ttsName = new android.widget.EditText(ctx);
        ttsName.setHint("檔名（可留空）");
        ttsName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        ttsName.setTextColor(Theme.TEXT);
        ttsName.setHintTextColor(Theme.TEXT_DIM);
        ttsName.setBackground(Theme.bg(Theme.SURFACE_2, Theme.STROKE, Theme.R_BTN, ctx));
        ttsName.setPadding(ep, ep, ep, ep);
        ttsRow.addView(ttsName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button ttsGen = Theme.button(ctx);
        ttsGen.setText("產生並儲存");
        ttsGen.setTag("skip");
        Theme.primary(ttsGen, ctx);
        ttsGen.setOnClickListener(v -> genTts());
        LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tglp.leftMargin = dp(8);
        ttsRow.addView(ttsGen, tglp);
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trlp.topMargin = dp(6);
        col.addView(ttsRow, trlp);

        ttsStatus = new TextView(ctx);
        Theme.label(ttsStatus, Theme.TEXT_DIM, 11f);
        ttsStatus.setText("（產生完會自動加入下面清單，懸浮窗第 2 頁即時用得到）");
        col.addView(ttsStatus);

        TextView cl = new TextView(ctx);
        Theme.label(cl, Theme.TEXT_DIM, 11f);
        cl.setText("Preset 語音（懸浮控制窗第 2 頁起同步；每行：▶ 播放／⏹ 停止／✎ 改名／🗑 刪除）：");
        LinearLayout.LayoutParams cllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cllp.topMargin = dp(10);
        col.addView(cl, cllp);

        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        refreshList();
        refreshInputs();
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

    /** 英文 TTS → 存成 preset 語音（本地 Android TTS，唔得就用 server）。 */
    private void genTts() {
        String txt = ttsText == null ? "" : ttsText.getText().toString().trim();
        if (txt.isEmpty()) {
            say("⚠️ 請先打英文句子");
            return;
        }
        ttsStatus.setText("⏳ 產生中…（先試本地 TTS，唔得自動用 server edge-tts）");
        log.out("🔤 TTS 產生：" + txt.substring(0, Math.min(40, txt.length())));
        Tts.synth(ctx, txt, ttsName == null ? "" : ttsName.getText().toString(), (f, msg) -> {
            if (f == null) {
                ttsStatus.setText("❌ " + msg);
                say("❌ TTS 失敗：" + msg);
            } else {
                ttsStatus.setText("✅ " + msg + "　→ 已入 preset（懸浮窗第 2 頁用得到）");
                say("✅ TTS 已儲存：" + f.getName());
                ttsText.setText("");
                if (ttsName != null) ttsName.setText("");
            }
            log.out("🔤 " + msg);
            refreshList();
        });
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
                    say(ok ? "✎ 已改名：" + nm : "⚠️ 改名失敗（名可能已用）");
                    log.out("🎙 ✎ 改名 " + f.getName() + " → " + nm + "："
                            + (ok ? "成功" : "失敗"));
                    refreshList();
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
            if (shown++ >= 20) break;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView nm = new TextView(ctx);
            nm.setText(f.getName().replace(PREFIX, "").replace(".wav", "").replace(".m4a", "").replace(".mp3", "")
                    + "  " + (f.length() / 1024) + "KB");
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            row.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
            Button playB = Theme.button(ctx);
            playB.setText("播放");
            playB.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            playB.setTag("skip");
            Theme.secondary(playB, ctx);
            Theme.compact(playB, ctx);
            Theme.icon(playB, R.drawable.ic_play);          // ▶ Play 圖示
            playB.setOnClickListener(v -> play(f));
            row.addView(playB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
            Button stopB = Theme.button(ctx);                  // 停止試聽（新增）
            stopB.setText("停止");
            stopB.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
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
            row.addView(stopB, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
            Button rn = Theme.button(ctx);                      // ✎ 改名（用戶要求 8）
            rn.setText("改名");
            rn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            rn.setTag("skip");
            Theme.secondary(rn, ctx);
            Theme.compact(rn, ctx);
            rn.setOnClickListener(v -> promptRename(f));
            row.addView(rn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
            Button del = Theme.button(ctx);
            del.setText("");
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            del.setTag("skip");
            Theme.danger(del, ctx);
            Theme.compact(del, ctx);
            Theme.icon(del, R.drawable.ic_trash);            // 🗑 刪除圖示
            del.setOnClickListener(v -> {
                boolean ok = f.delete();
                log.out("🎙 刪除 " + f.getName() + " → " + ok);
                refreshList();
            });
            row.addView(del, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
            Theme.styleTree(row);              // 新加嘅 row 都要上車機風格
            list.addView(row);
        }
    }

    private void say(String s) {
        final String m = s;
        if (status != null) status.post(() -> status.setText("狀態：" + m));
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
