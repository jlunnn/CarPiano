package com.carpiano;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 英文 TTS → 儲存成 preset 語音（用戶 2026-10-07 要求）。
 *
 * <p>只用<b>內建 Android TTS</b>（{@link TextToSpeech#synthesizeToFile}）→ `rec-&lt;名&gt;.wav`；
 * 完全離線、唔使上網。裝置冇 TTS 引擎就產生唔到（會喺畫面講明原因）。
 * 出嚟嘅檔名開頭係 `rec-`，所以**自動出現喺錄音清單同懸浮窗第 2 頁 preset** ✓
 */
public final class Tts {


    public interface Cb {
        void done(File f, String msg);
    }

    private Tts() {
    }

    /** 產生語音檔（背景 thread 做，callback 返主 thread）。 */
    public static void synth(final Context ctx, final String text, final String name, final Cb cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File out = null;
            String msg;
            try {
                out = tryLocal(ctx, text, name);
                msg = out != null ? "本地 TTS 成功（Android 引擎）" : null;
            } catch (Throwable t) {
                msg = null;
            }
            if (out == null) {
                msg = "呢部機冇可用嘅 TTS 引擎（只用內建引擎，唔會連網）";
            }
            final File f = out;
            final String m = f == null ? msg : (msg + "｜" + f.getName() + " " + (f.length() / 1024) + "KB");
            h.post(() -> cb.done(f, m));
        }, "piano-tts").start();
    }

    /** 用戶要求：檔名自動 = TTS + 日期 + 時間 + 秒（例：TTS-20261007-215930）。 */
    public static String autoName() {
        return "TTS-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    public static String safeName(String s) {
        String n = (s == null ? "" : s.trim()).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (n.isEmpty()) n = autoName();
        if (n.length() > 32) n = n.substring(0, 32);
        return n;
    }

    // ---------------------------------------------------------------- ① 本地 TTS

    private static File tryLocal(Context ctx, String text, String name) {
        File out = new File(ctx.getFilesDir(), "rec-" + safeName(name) + ".wav");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        TextToSpeech tts = null;
        try {
            final TextToSpeech[] holder = new TextToSpeech[1];
            tts = new TextToSpeech(ctx, status -> {
                TextToSpeech t = holder[0];
                if (t == null) return;
                if (status != TextToSpeech.SUCCESS) {
                    latch.countDown();
                    return;
                }
                try {
                    int avail = t.setLanguage(Locale.US);
                    if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
                        latch.countDown();
                        return;
                    }
                    t.setPitch(1.0f);
                    t.setSpeechRate(0.95f);
                    int r = t.synthesizeToFile(text, null, out, "carPianoTts");
                    if (r != TextToSpeech.SUCCESS) latch.countDown();
                } catch (Throwable e) {
                    latch.countDown();
                }
            });
            holder[0] = tts;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    ok.set(true);
                    latch.countDown();
                }

                @Override
                public void onError(String id) {
                    latch.countDown();
                }
            });
            latch.await(15, TimeUnit.SECONDS);
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                if (tts != null) tts.shutdown();
            } catch (Throwable ignored) {
            }
        }
        if (ok.get() && out.exists() && out.length() > 1000) return out;
        if (out.exists()) out.delete();
        return null;
    }

}
