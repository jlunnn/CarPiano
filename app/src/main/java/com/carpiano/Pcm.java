package com.carpiano;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 用 AudioTrack 播 WAV（PCM16）→ 指定輸出裝置。
 *
 * <h3>點解唔用 MediaPlayer</h3>
 * 車外發聲係**靠指定路由**（BUS12_OUTER_NOTIFY），而 AudioTrack + {@code setPreferredDevice}
 * 係喺 ZEEKR 7X 上實測通嘅路（車機實測：車外响聲就係咁做）；
 * MediaPlayer 會經 media stream／DSP，落唔落得到同一條 BUS 唔敢包。
 * 所以所有音效（horn、倒車語音）都用 AudioTrack 播 PCM，MediaPlayer 只留作後備。
 */
public class Pcm {

    public interface Cb {
        void done(String msg);
    }

    private static AudioTrack current;

    /**
     * 用戶設定嘅音量（軟件增益，0–100%）。⚠️ 為什麼要自己做軟件增益：
     * 車機每條 BUS 背後係唔同 DSP／功放路徑，硬件 stream 音量係咪真係縮到 BUS12 出聲，
     * 要視乎車廠 HAL —— 靠唔住。軟件增益一定改到實際波形振幅，所以兩樣一齊做：
     * ① 照寫硬件 stream 音量（跟車機 HMI 顯示一致）② 自己縮 PCM。
     */
    private static volatile int gainPct = 100;

    public static void setGainPercent(int pct) {
        gainPct = Math.max(0, Math.min(100, pct));
    }

    public static int gainPercent() {
        return gainPct;
    }

    /** 對 PCM16 做線性增益（就地改）。 */
    static void applyGain(byte[] b, int off, int len, double g) {
        if (g == 1.0 || len < 2) return;
        int end = Math.min(off + len, b.length - 1);
        for (int i = off; i + 1 < end; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            int v = (int) Math.round(s * g);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            b[i] = (byte) (v & 0xFF);
            b[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }

    private Pcm() {
    }

    public static synchronized void stopAll() {
        AudioTrack t = current;
        current = null;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.stop();
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String devDesc(AudioDeviceInfo d) {
        if (d == null) return "（冇）";
        return "type=" + d.getType() + " addr=" + d.getAddress();
    }

    /** 介面用嘅講法（唔露出 BUS 名，一律叫「車外 speaker」）。 */
    public static String friendly(AudioDeviceInfo d) {
        if (d == null) return "預設輸出（車內）";
        String a = d.getAddress();
        if (a != null && a.toUpperCase().contains("BUS12")) return "車外 speaker";
        return "其他輸出";
    }

    /** 播 assets 內嘅 WAV（預設 media usage）。 */
    public static void playAsset(final Context ctx, final String asset, final AudioDeviceInfo dev,
                                 final Cb cb) {
        playAsset(ctx, asset, dev, AudioAttributes.USAGE_MEDIA,
                AudioAttributes.CONTENT_TYPE_MUSIC, cb);
    }

    /**
     * 播 assets 內嘅 WAV，可以指定 usage／contentType。
     *
     * <p>⚠️ 為咩要指定 usage：車機每條 BUS 背後係唔同 DSP／功放路徑，
     * 系統會按 usage 決定「呢條 stream 應該去邊」；用錯 usage 就算
     * {@code setPreferredDevice} 成功、亦可能被改寫或者唔導通（BUS11 靜音就係同一類情況）。</p>
     */
    public static void playAsset(final Context ctx, final String asset, final AudioDeviceInfo dev,
                                 final int usage, final int contentType, final Cb cb) {
        new Thread(() -> {
            try (InputStream in = ctx.getAssets().open(asset)) {
                play(ctx, Diag.readAll(in), asset, dev, usage, contentType, cb);
            } catch (Throwable e) {
                cb.done("⚠️ 開唔到 assets/" + asset + "：" + e.getClass().getSimpleName());
            }
        }, "piano-pcm-a").start();
    }

    /** 播本機 WAV 檔（錄音）。 */
    public static void playFile(final Context ctx, final File f, final AudioDeviceInfo dev,
                               final Cb cb) {
        playFile(ctx, f, dev, AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, cb);
    }

    /** 播本機 WAV 檔（錄音），可以指定 usage／contentType。 */
    public static void playFile(final Context ctx, final File f, final AudioDeviceInfo dev,
                               final int usage, final int contentType, final Cb cb) {
        new Thread(() -> {
            try (InputStream in = new FileInputStream(f)) {
                play(ctx, Diag.readAll(in), f.getName(), dev, usage, contentType, cb);
            } catch (Throwable e) {
                cb.done("⚠️ 讀唔到 " + f.getName() + "：" + e.getClass().getSimpleName());
            }
        }, "piano-pcm-f").start();
    }

    public static String usageName(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_MEDIA:
                return "媒體";
            case AudioAttributes.USAGE_NOTIFICATION:
                return "通知";
            case AudioAttributes.USAGE_ALARM:
                return "警報";
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                return "導航";
            default:
                return "usage=" + usage;
        }
    }

    /** 核心：parse WAV → AudioTrack（MODE_STREAM）→ 指定裝置／usage → 邊寫邊播。 */
    private static void play(Context ctx, byte[] wav, String name, AudioDeviceInfo dev,
                             int usage, int contentType, Cb cb) {
        AudioTrack t = null;
        try {
            int[] h = parseWav(wav);        // {dataOff, dataLen, rate, channels, bits}
            int rate = h[2], channels = h[3], bits = h[4];
            if (h[0] < 0 || h[1] <= 0) {
                cb.done("⚠️ " + name + " 唔係 PCM WAV（改用後備播放）");
                return;
            }
            int channelMask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(bits == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(Math.max(rate, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (dev != null) {
                t.setPreferredDevice(dev);
            }
            synchronized (Pcm.class) {
                stopAllSilent();
                current = t;
            }
            t.play();
            int off = h[0], len = h[1], chunk = 4096;
            double gain = normalizePcm(wav, off, len, bits);   // ⭐ 正規化 → 解決「好細聲」
            double user = gainPct / 100.0;                     // ⭐ 用戶音量（軟件增益）
            applyGain(wav, off, len, user);
            while (len > 0) {
                int n = Math.min(chunk, len);
                t.write(wav, off, n);
                off += n;
                len -= n;
            }
            int ms = (h[1] / Math.max(1, rate * channels * (bits / 8))) * 1000;
            Thread.sleep(120);
            AudioDeviceInfo routed = null;
            try {
                routed = t.getRoutedDevice();
            } catch (Throwable ignored) {
            }
            cb.done("✅ " + name + " 播完（" + ms + " ms）｜出聲：" + friendly(routed)
                    + "｜" + usageName(usage)
                    + "｜音量增益 ×" + String.format(java.util.Locale.US, "%.1f", gain)
                    + (user == 1.0 ? "" : " ×用戶 " + gainPct + "%")
                    + "｜（要：" + friendly(dev) + "）");
        } catch (Throwable e) {
            cb.done("⚠️ " + name + " 播唔到：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            synchronized (Pcm.class) {
                if (current == t) current = null;
            }
            if (t != null) {
                try {
                    t.stop();
                    t.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void stopAllSilent() {
        AudioTrack t = current;
        current = null;
        if (t != null) {
            try {
                t.pause();
                t.stop();
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 正規化 PCM16 到 ~−0.5 dBFS（有上限 ×16），回傳用咗嘅增益。 */
    static double normalizePcm(byte[] b, int off, int len, int bits) {
        if (bits != 16 || len < 4) return 1.0;
        int peak = 0;
        int end = Math.min(off + len, b.length - 1);
        for (int i = off; i + 1 < end; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            int a = Math.abs(s);
            if (a > peak) peak = a;
        }
        if (peak <= 0) return 1.0;
        double g = 32767.0 * 0.945 / peak;
        if (g < 1.02) return 1.0;              // 已經夠大聲 → 唔動
        if (g > 16.0) g = 16.0;                // +24 dB 上限（避免連底噪都放大到爆）
        for (int i = off; i + 1 < end; i += 2) {
            int s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
            int v = (int) Math.round(s * g);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            b[i] = (byte) (v & 0xFF);
            b[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return g;
    }

    /** 解 WAV header → {dataOffset, dataLen, sampleRate, channels, bitsPerSample}；讀唔到回 −1。 */
    static int[] parseWav(byte[] b) {
        int[] bad = {-1, -1, 44100, 1, 16};
        if (b.length < 44) return bad;
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return bad;
        int i = 12;
        int rate = 44100, ch = 1, bits = 16;
        while (i + 8 <= b.length) {
            String id = "" + (char) b[i] + (char) b[i + 1] + (char) b[i + 2] + (char) b[i + 3];
            int sz = le32(b, i + 4);
            if ("fmt ".equals(id)) {
                ch = le16(b, i + 10);
                rate = le32(b, i + 12);
                bits = le16(b, i + 22);
            } else if ("data".equals(id)) {
                int off = i + 8;
                int len = Math.min(sz, b.length - off);
                return new int[]{off, len, rate, ch, bits};
            }
            if (sz <= 0) break;
            i += 8 + sz + (sz & 1);
        }
        return bad;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /** 未用，但保留：把字節轉做可讀（診斷用）。 */
    static String hex(byte[] b, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) s.append(String.format("%02X ", b[i]));
        return s.toString();
    }

    static byte[] concat(ByteArrayOutputStream a) {
        return a.toByteArray();
    }
}
