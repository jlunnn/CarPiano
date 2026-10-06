package com.carpiano.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * 共用音頻層。
 *
 * ⭐ 車外發聲嘅關鍵（2026-10-05 車機 實測）：要指定路由去 `BUS12_OUTER_NOTIFY`；
 *    另一條 `BUS11_OUTER_SPEAKER_PLAYBACK` 路由會成功但**完全冇聲**。
 */
public class AudioOut {

    public static final String OUTER_BUS = "BUS12_OUTER_NOTIFY";

    private final Context ctx;
    private final AudioManager am;
    private AudioTrack track;

    public AudioOut(Context ctx) {
        this.ctx = ctx;
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public List<AudioDeviceInfo> outputs() {
        List<AudioDeviceInfo> out = new ArrayList<>();
        if (am == null) return out;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) out.add(d);
        return out;
    }

    public AudioDeviceInfo byAddr(String needle) {
        if (needle == null) return null;
        for (AudioDeviceInfo d : outputs()) {
            String a = d.getAddress();
            if (a != null && a.toUpperCase().contains(needle.toUpperCase())) return d;
        }
        return null;
    }

    /** 車外喇叭（已實測有聲嘅一條）。 */
    public AudioDeviceInfo outer() {
        return byAddr(OUTER_BUS);
    }

    /**
     * 車內（車廂）輸出。
     *
     * ⚠️ 注意：**唔可以靠「預設輸出」**——部分車機容器裡面
     * 「唔指定裝置」係冇聲嘅（車外指定 BUS12 就有聲）。所以車內都要**明選一條 BUS**。
     * 次序：`BUS00_MEDIA` → 其他 `BUS00*` → 系統宣告嘅 builtin speaker。
     */
    public AudioDeviceInfo inner() {
        List<AudioDeviceInfo> outs = outputs();
        for (AudioDeviceInfo d : outs) {
            String a = d.getAddress() == null ? "" : d.getAddress().toUpperCase();
            if (a.equals("BUS00_MEDIA")) return d;
        }
        for (AudioDeviceInfo d : outs) {
            String a = d.getAddress() == null ? "" : d.getAddress().toUpperCase();
            if (a.startsWith("BUS00")) return d;
        }
        for (AudioDeviceInfo d : outs) {
            if (d.isSink() && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return d;
        }
        return null;
    }

    /** 車內各條通道。 */
    public AudioDeviceInfo notifyBus() {
        return byAddr("BUS01_SYS_NOTIFICATION");
    }

    public AudioDeviceInfo mediaBus() {
        return byAddr("BUS00_MEDIA");
    }

    public AudioDeviceInfo navBus() {
        return byAddr("BUS02_NAV_GUIDANCE");
    }

    public AudioDeviceInfo alertsBus() {
        return byAddr("BUS05_ALERTS");
    }

    public int maxMusicVolume() {
        return am == null ? 15 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    public int musicVolume() {
        return am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    /** 其他音量流（通知／警報…）。用通知 BUS 播時，聲音係跟「通知音量」，唔係媒體音量。 */
    public int maxVolume(int stream) {
        return am == null ? 15 : am.getStreamMaxVolume(stream);
    }

    public int volume(int stream) {
        return am == null ? -1 : am.getStreamVolume(stream);
    }

    public void setVolume(int stream, int v) {
        try {
            if (am != null) am.setStreamVolume(stream, Math.max(0, Math.min(v, maxVolume(stream))), 0);
        } catch (Throwable ignored) {
        }
    }

    public void setVolume(int v) {
        try {
            if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(v, maxMusicVolume()), 0);
        } catch (Throwable ignored) {
        }
    }

    public synchronized void stopNote() {
        AudioTrack t = track;
        track = null;
        releaseQuietly(t);
    }

    private static void releaseQuietly(AudioTrack t) {
        if (t == null) return;
        try {
            t.pause();
            t.flush();
            t.stop();
            t.release();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 播一個琴音。鋼琴感＝6 個諧波 × 指數衰減包絡。
     *
     * @param freq  頻率 Hz
     * @param durMs 長度 ms
     * @param dev   指定輸出裝置（null = 系統預設／車內）
     */
    public void playNote(final double freq, final int durMs, final AudioDeviceInfo dev,
                         final float gain) {
        stopNote();
        final int rate = 44100;
        final int total = rate * durMs / 1000;

        // ① 先整個波形算好（離線），② 正規化到 −0.5 dBFS → 大聲又唔爆（v1.8）
        final short[] pcm = new short[total];
        final double[] harm = {1.0, 0.5, 0.28, 0.16, 0.09, 0.05};
        int peak = 0;
        for (int i = 0; i < total; i++) {
            double tt = i / (double) rate;
            double env = Math.exp(-3.0 * tt) * (1 - Math.exp(-800 * tt));
            double v = 0;
            for (int h = 0; h < harm.length; h++) {
                v += harm[h] * Math.sin(2 * Math.PI * freq * (h + 1) * tt);
            }
            v = v / 2.6 * env * gain;
            short s = (short) Math.max(-32000, Math.min(32000, v * 32000));
            pcm[i] = s;
            if (Math.abs(s) > peak) peak = Math.abs(s);
        }
        if (peak > 0) {
            double k = 32767.0 * 0.945 / peak;
            for (int i = 0; i < total; i++) {
                pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(pcm[i] * k)));
            }
        }

        new Thread(() -> {
            AudioTrack t = null;
            try {
                t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(rate, 8192))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                if (dev != null) t.setPreferredDevice(dev);
                synchronized (AudioOut.this) {
                    track = t;
                }
                t.play();
                int written = 0;
                while (written < total) {
                    int n = Math.min(2048, total - written);
                    t.write(pcm, written, n);
                    written += n;
                }
                Thread.sleep(80);
            } catch (Throwable ignored) {
            } finally {
                synchronized (AudioOut.this) {
                    if (track == t) {
                        track = null;
                        releaseQuietly(t);
                    }
                }
            }
        }, "piano-note").start();
    }

    /** 俾錄音播放用：MediaPlayer 指定輸出裝置（車外 BUS12 或者車內）。 */
    public static void setPlayerDevice(android.media.MediaPlayer mp, AudioDeviceInfo dev) {
        try {
            if (dev != null) mp.setPreferredDevice(dev);
        } catch (Throwable ignored) {
        }
    }
}
