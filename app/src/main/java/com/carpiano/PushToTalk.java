package com.carpiano;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * 按住錄音（懸浮掣「按住講」用）：按住嗰刻開始錄成 WAV，放手即完成，
 * 之後由 {@code FloatService} 等 1 秒再播去車外 speaker（BUS12）。
 *
 * <p>為什麼唔用 MediaRecorder：要指定輸出格式／即刻拿到檔案長度，而且我們自己寫 WAV
 * 可以同 {@link Pcm} 用同一條 AudioTrack + setPreferredDevice 播放路線（車外 BUS12 已實測通）。</p>
 *
 * <p>⚠️ 錄音中一定要係前台服務（Android 11+ 由服務收音需要 foregroundServiceType="microphone"）。</p>
 */
final class PushToTalk {

    interface Cb {
        void onState(String msg);
    }

    private static final int RATE = 16000;
    private static final int CHUNK = 1024;

    private final Context ctx;
    private final Cb cb;

    private AudioRecord rec;
    private Thread th;
    private volatile boolean recording;
    private File file;
    private long pcmBytes;
    private long startedAt;

    PushToTalk(Context ctx, Cb cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    boolean isRecording() {
        return recording;
    }

    File lastFile() {
        return file;
    }

    /** 最後一次錄咗幾多毫秒。 */
    long lastMs() {
        return pcmBytes * 1000 / (RATE * 2);
    }

    /** 開始錄音（非阻塞）。 */
    void start() {
        if (recording) return;
        recording = true;
        startedAt = System.currentTimeMillis();
        pcmBytes = 0;
        file = new File(ctx.getFilesDir(), "ptt-last.wav");
        th = new Thread(() -> {
            FileOutputStream os = null;
            try {
                int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf, CHUNK * 8));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    state("⚠️ 開咪失敗（可能未授權收音權限）");
                    recording = false;
                    return;
                }
                os = new FileOutputStream(file);
                os.write(wavHeader(0));           // 先寫佔位 header，收完再回填長度
                rec.startRecording();
                state("🔴 錄音中…（放開即停，1 秒後播車外）");
                byte[] buf = new byte[CHUNK * 2];
                while (recording) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) {
                        os.write(buf, 0, n);
                        pcmBytes += n;
                    }
                }
                os.flush();
            } catch (Throwable t) {
                state("⚠️ 錄音失敗：" + t.getClass().getSimpleName() + " " + t.getMessage());
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Throwable ignored) {
                    }
                }
                AudioRecord r = rec;
                rec = null;
                if (r != null) {
                    try {
                        r.stop();
                    } catch (Throwable ignored) {
                    }
                    try {
                        r.release();
                    } catch (Throwable ignored) {
                    }
                }
                recording = false;
                patchHeader();
            }
        }, "ptt-rec");
        th.start();
    }

    /** 放手：停止錄音（會等寫檔完成）。 */
    void stop() {
        if (!recording) return;
        recording = false;
        Thread t = th;
        if (t != null) {
            try {
                t.join(700);
            } catch (Throwable ignored) {
            }
        }
        long ms = System.currentTimeMillis() - startedAt;
        state("⏹ 錄完（" + ms + " ms，約 " + (pcmBytes / 1024) + " KB）→ 1 秒後播車外 speaker…");
    }

    /** 回填 WAV header 嘅 RIFF／data 長度。 */
    private void patchHeader() {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            byte[] h = wavHeader((int) pcmBytes);
            raf.seek(0);
            raf.write(h);
        } catch (Throwable ignored) {
        }
    }

    private static byte[] wavHeader(int dataLen) {
        int byteRate = RATE * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF");
        le32(h, 4, 36 + dataLen);
        put(h, 8, "WAVE");
        put(h, 12, "fmt ");
        le32(h, 16, 16);
        le16(h, 20, 1);              // PCM
        le16(h, 22, 1);              // mono
        le32(h, 24, RATE);
        le32(h, 28, byteRate);
        le16(h, 32, 2);              // block align
        le16(h, 34, 16);             // bits
        put(h, 36, "data");
        le32(h, 40, dataLen);
        return h;
    }

    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void le32(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private void state(String s) {
        try {
            cb.onState(s);
        } catch (Throwable ignored) {
        }
    }
}
