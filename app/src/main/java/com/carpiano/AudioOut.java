package com.carpiano;

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
 * ⭐ 車外發聲嘅關鍵（2026-10-05 ZEEKR 7X 實測）：要指定路由去 `BUS12_OUTER_NOTIFY`；
 *    另一條 `BUS11_OUTER_SPEAKER_PLAYBACK` 路由會成功但**完全冇聲**。
 */
public class AudioOut {

    public static final String OUTER_BUS = "BUS12_OUTER_NOTIFY";

    private final Context ctx;
    private final AudioManager am;
    private AudioTrack track;          // 舊：單音用（保留兼容）

    // ================= 複音混音引擎（v2.1，修「多音重疊破音」）=================
    // 根因：舊做法每粒音各自開一條 AudioTrack、各自正規化到 −0.5 dBFS（近滿刻度），
    //       兩粒同時響 = 相加 +6 dB → 硬削波（車內尤其明顯，因為多條 stream 一齊混）。
    // 新做法：一個 route 只用一條 AudioTrack（MODE_STREAM），所有音喺軟件層相加，
    //       再用 tanh 軟限幅 → 多音只會「厚」，唔會「破」。收尾有 20ms 淡出，冇「啪」聲。
    private static final int RATE = 44100;
    private static final int FRAMES = 1024;
    private static final int MAX_VOICES = 8;
    private static final int FADE = RATE / 50;            // 20ms

    private static final class Voice {
        final short[] pcm;
        int pos;
        double amp = 1.0;
        int fadeLeft;                                     // >0 = 淡出中
        Voice(short[] pcm) {
            this.pcm = pcm;
        }
    }

    private final List<Voice> voices = new ArrayList<>();
    private NoteEngine engine;

    private final class NoteEngine {
        final int usage;
        final int devId;
        final AudioTrack t;
        volatile boolean run = true;

        NoteEngine(int usage, AudioDeviceInfo dev) throws Throwable {
            this.usage = usage;
            this.devId = dev == null ? -1 : dev.getId();
            t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(FRAMES * 4 * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (dev != null) {
                try {
                    t.setPreferredDevice(dev);
                } catch (Throwable ignored) {
                }
            }
            t.play();
        }

        void loop() {
            final short[] mix = new short[FRAMES];
            final int[] acc = new int[FRAMES];
            while (run && !voices.isEmpty()) {
                java.util.Arrays.fill(acc, 0);
                synchronized (voices) {
                    java.util.Iterator<Voice> it = voices.iterator();
                    while (it.hasNext()) {
                        Voice v = it.next();
                        boolean done = false;
                        for (int i = 0; i < FRAMES; i++) {
                            if (v.pos >= v.pcm.length) {
                                done = true;
                                break;
                            }
                            double f = 1.0;
                            if (v.fadeLeft > 0) {
                                f = v.fadeLeft / (double) FADE;
                                v.fadeLeft--;
                                if (v.fadeLeft <= 0) done = true;
                            }
                            acc[i] += (int) (v.pcm[v.pos++] * v.amp * f);
                        }
                        if (done && v.pos >= v.pcm.length) it.remove();
                        else if (done) it.remove();
                    }
                }
                for (int i = 0; i < FRAMES; i++) {
                    double x = acc[i] / 32768.0;
                    x = Math.tanh(x * 1.15) * 0.94;          // 軟限幅：多音唔會破
                    mix[i] = (short) Math.max(-32767, Math.min(32767, Math.round(x * 32767)));
                }
                try {
                    t.write(mix, 0, FRAMES, AudioTrack.WRITE_BLOCKING);
                } catch (Throwable e) {
                    run = false;
                }
            }
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            synchronized (AudioOut.this) {
                if (engine == this) engine = null;
            }
        }
    }

    private void ensureEngine(int usage, AudioDeviceInfo dev) {
        int devId = dev == null ? -1 : dev.getId();
        synchronized (AudioOut.this) {
            if (engine != null && engine.usage == usage && engine.devId == devId) return;
            if (engine != null) {
                engine.run = false;
            }
            try {
                final NoteEngine e = new NoteEngine(usage, dev);
                engine = e;
                new Thread(e::loop, "piano-mix").start();
            } catch (Throwable ignored) {
                engine = null;
            }
        }
    }

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
     * ⚠️ 實測教訓（v1.9）：**唔可以靠「預設輸出」**——App Lab 容器裡面
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

    /** 車內各條通道（用戶 2026-10-06 指定試 BUS01_SYS_NOTIFICATION）。 */
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

    /** 停所有琴音（20ms 淡出，唔會「啪」一聲）。 */
    public void stopNote() {
        synchronized (voices) {
            for (Voice v : voices) {
                v.fadeLeft = FADE;
            }
        }
        synchronized (this) {
            AudioTrack t = track;
            track = null;
            if (t != null) releaseQuietly(t);
        }
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
        playNote(freq, durMs, dev, gain, AudioAttributes.USAGE_MEDIA);
    }

    /** 指定 usage（車內 = USAGE_NOTIFICATION；車外 = USAGE_MEDIA）。 */
    public void playNote(final double freq, final int durMs, final AudioDeviceInfo dev,
                         final float gain, final int usage) {
        final int total = RATE * durMs / 1000;

        // ① 離線算波形（6 諧波 × 指數衰減）② 正規化  ③ 套用戶音量
        final short[] pcm = new short[total];
        final double[] harm = {1.0, 0.5, 0.28, 0.16, 0.09, 0.05};
        int peak = 0;
        for (int i = 0; i < total; i++) {
            double tt = i / (double) RATE;
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
        double vol = Pcm.gainPercent() / 100.0;
        if (vol != 1.0) {
            for (int i = 0; i < total; i++) {
                pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(pcm[i] * vol)));
            }
        }

        // ⭐ 加入複音混音（唔再每粒音開一條 AudioTrack）：多音相加後由 tanh 軟限幅護住。
        ensureEngine(usage, dev);
        synchronized (voices) {
            while (voices.size() >= MAX_VOICES) {
                voices.remove(0);
            }
            voices.add(new Voice(pcm));
        }
    }

    /** 俾錄音播放用：MediaPlayer 指定輸出裝置（車外 BUS12 或者車內）。 */
    public static void setPlayerDevice(android.media.MediaPlayer mp, AudioDeviceInfo dev) {
        try {
            if (dev != null) mp.setPreferredDevice(dev);
        } catch (Throwable ignored) {
        }
    }
}
