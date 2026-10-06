package com.carpiano;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 🚗 開車自動開啟／熄車自動關閉（v1.5）。
 *
 * <h3>點解唔用開機廣播</h3>
 * 部分車機（虛擬化容器環境）**唔會派** BOOT_COMPLETED，所以唔可以靠開機廣播。
 * 但同一份筆記證明：**進程挨得過深睡**（深睡前後 pid 一樣）→
 * 所以做法係一個**前台服務長期輪詢點火值**（{@link VehicleRead#ID_IGNITION}，只讀、免權限）。
 *
 * <h3>唔猜編碼，用「學」</h3>
 * 點火值嘅編碼（ACC/ON/DRIVING）唔喺車上驗過就唔敢靠估 → app 讓用戶
 * **停車時記住一個值**、**行車時記住另一個值**；之後比較兩個值就知狀態。
 * 未教過之前，服務只監察、唔會亂開閂。
 *
 * 狀態一去到「行車」= 開 app ＋ 懸浮掣；一去到「泊車」= 閂 app ＋ 收懸浮掣。
 */
public class AutoService extends Service {

    public static final String ACTION_STOP = "com.carpiano.AUTO_OFF";
    /** 人手模擬：extra `sim`=true/false 強制狀態；extra `clear`=true 交返自動。 */
    public static final String ACTION_SIM = "com.carpiano.AUTO_SIM";
    public static final String ACTION_TICK = "com.carpiano.AUTO_TICK";
    /** 服務 → 介面：extra raw(int)、driving(bool)、note(String)。 */
    public static final String BC_STATE = "com.carpiano.STATE";
    /** 服務 → 介面：熄車，叫 app 自己收埋。 */
    public static final String BC_CLOSE = "com.carpiano.CLOSE";

    private static final String CH = "piano_auto";
    private static final int NOTIF_ID = 41;
    private static final long POLL_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    private boolean driving;
    private int lastRaw = Integer.MIN_VALUE;
    private String note = "等信號";
    private Boolean sim;                     // 非 null = 人手模擬中

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            int raw = VehicleRead.read(AutoService.this, VehicleRead.ID_IGNITION);
            lastRaw = raw;
            boolean want = sim != null ? sim : decide(raw);
            if (want != driving) {
                driving = want;
                if (driving) onDriving();
                else onParked();
            }
            updateNotif();
            sendState();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("piano", MODE_PRIVATE);
        ensureChannel();
        startForeground(NOTIF_ID, buildNotif());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SIM.equals(a) && intent != null) {
            if (intent.getBooleanExtra("clear", false)) {
                sim = null;
                note = "交返自動（睇點火值）";
            } else {
                sim = intent.getBooleanExtra("sim", false);
                note = "人手模擬：" + (sim ? "開車" : "熄車");
            }
            handler.removeCallbacks(tick);
            handler.post(tick);
        } else if (ACTION_TICK.equals(a)) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        return START_STICKY;
    }

    /** 判斷而家係唔係「行車」——用學到嘅值比較，唔猜編碼。 */
    private boolean decide(int raw) {
        int parked = sp.getInt("park_raw", Integer.MIN_VALUE);
        int driveRaw = sp.getInt("drive_raw", Integer.MIN_VALUE);
        if (raw == Integer.MIN_VALUE) return driving;              // 讀唔到 → 維持現狀
        if (driveRaw != Integer.MIN_VALUE && raw == driveRaw) return true;
        if (parked != Integer.MIN_VALUE && raw == parked) return false;
        if (parked == Integer.MIN_VALUE) return driving;           // 未教過 → 唔動
        return raw != parked;                                      // 同泊車值唔同 = 車著
    }

    private void onDriving() {
        note = "🚗 車著：開 app ＋ 懸浮掣";
        try {
            startService(new Intent(this, FloatService.class));
        } catch (Throwable ignored) {
        }
        try {
            Intent ui = new Intent(this, MainActivity.class);
            ui.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ui.putExtra("auto_open", true);
            startActivity(ui);
        } catch (Throwable ignored) {
        }
    }

    private void onParked() {
        note = "🅿️ 泊車：閂 app ＋ 收懸浮掣";
        sendBroadcast(new Intent(BC_CLOSE).setPackage(getPackageName()));
        try {
            stopService(new Intent(this, FloatService.class));
        } catch (Throwable ignored) {
        }
    }

    private void sendState() {
        Intent i = new Intent(BC_STATE);
        i.setPackage(getPackageName());
        i.putExtra("raw", lastRaw);
        i.putExtra("driving", driving);
        i.putExtra("note", note + (sim != null ? "［模擬］" : ""));
        sendBroadcast(i);
    }

    // ------------------------------------------------------------- 通知

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                NotificationChannel c = new NotificationChannel(CH, "開車自動",
                        NotificationManager.IMPORTANCE_LOW);
                c.setDescription("開車自動開啟／熄車自動關閉嘅狀態");
                nm.createNotificationChannel(c);
            }
        }
    }

    private String text() {
        return "點火 " + VehicleRead.hex(lastRaw) + " ｜ "
                + (driving ? "行車中" : "泊車") + " ｜ " + note;
    }

    private Notification buildNotif() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        b.setContentTitle("Car Piano 開車自動")
                .setContentText(text())
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void updateNotif() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotif());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            stopService(new Intent(this, FloatService.class));
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
