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
 * <p>兩條路（先本地、唔得就 server）：
 * <ol>
 *   <li><b>本地 Android TTS</b>（{@link TextToSpeech#synthesizeToFile}）→ `rec-&lt;名&gt;.wav`；
 *       完全離線、唔使上網。</li>
 *   <li><b>Server 後備</b>：`https://hesheeat.cc/download/tts`（本機 edge-tts 服務）→ base64 MP3 →
 *       `rec-&lt;名&gt;.mp3`（車機冇裝 TTS engine 時用）。</li>
 * </ol>
 * 出嚟嘅檔名開頭係 `rec-`，所以**自動出現喺錄音清單同懸浮窗第 2 頁 preset** ✓
 */
public final class Tts {

    public static final String SERVER = "https://hesheeat.cc/download/tts";

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
                try {
                    out = tryServer(ctx, text, name);
                    msg = out != null ? "已用 server TTS（edge-tts）" : "兩種 TTS 都失敗";
                } catch (Throwable t) {
                    msg = "server TTS 失敗：" + t.getClass().getSimpleName();
                }
            }
            final File f = out;
            final String m = f == null ? msg : (msg + "｜" + f.getName() + " " + (f.length() / 1024) + "KB");
            h.post(() -> cb.done(f, m));
        }, "piano-tts").start();
    }

    public static String safeName(String s) {
        String n = (s == null ? "" : s.trim()).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (n.isEmpty()) n = "TTS-" + System.currentTimeMillis() / 1000;
        if (n.length() > 28) n = n.substring(0, 28);
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

    // ---------------------------------------------------------------- ② Server 後備

    private static File tryServer(Context ctx, String text, String name) throws Exception {
        String body = "text=" + URLEncoder.encode(text, "UTF-8")
                + "&voice=" + URLEncoder.encode("en-US-AriaNeural", "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        int code = c.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        String json = sb.toString();
        if (!json.contains("\"ok\": true") && !json.contains("\"ok\":true")) {
            throw new IllegalStateException("server 話失敗");
        }
        int i = json.indexOf("\"base64\":\"");
        if (i < 0) i = json.indexOf("\"base64\": \"");
        if (i < 0) throw new IllegalStateException("冇 base64");
        int from = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
        int to = json.indexOf('"', from);
        String b64 = json.substring(from, to);
        byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);

        File out = new File(ctx.getFilesDir(), "rec-" + safeName(name) + ".mp3");
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(raw);
        }
        return out;
    }
}
