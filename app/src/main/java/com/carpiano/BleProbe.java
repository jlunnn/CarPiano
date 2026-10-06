package com.carpiano;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * **BLE 探索器**（v11.32）。
 *
 * <p>用戶要求：「可否連接 BLE 藍牙？」——HID 路線已經通；呢個係**自訂 BLE 裝置**用：</p>
 * <ol>
 *   <li>BLE 掃描（見名／MAC／RSSI／廣播 service UUID）</li>
 *   <li>連接 → 探索全部 service／characteristic（UUID ＋ 權限）</li>
 *   <li>自動訂閱 NOTIFY／INDICATE → 記錄收到嘅 hex bytes（按鈕協議）</li>
 *   <li>上載完整報告 {@code ble-*.txt}</li>
 * </ol>
 */
final class BleProbe {

    interface L {
        void onDevice(String name, String mac, int rssi, String uuids, int txPower);

        void onLog(String line);

        void onDone(String summary);
    }

    /** 由 entry point 記住 application context（原版靠 App.ctx）。 */
    static Context appCtx;
    private static volatile boolean scanning;
    private static volatile int seenCount;
    private static ScanCallback currentCb;
    private static volatile boolean connected;
    private static volatile String connState = "未連接";
    /** v11.46：最近一次 notify（撳掣會見到 hex）。 */
    static volatile String nUuid = "";
    static volatile String nHex = "";
    static volatile long nAt = 0;
    static volatile int nCount = 0;
    /** v11.52：自檢用（已訂閱通知數 / service 數 / char 數）。 */
    static volatile int notifySubs = 0;
    static volatile int svcCount = 0;
    static volatile int charCount = 0;
    /** v11.55：GATT 表（俾報告分析：有冇 0x1812 HID）。 */
    static volatile String gattSummary = "";
    private static BluetoothGattCharacteristic pingChar;
    private static BluetoothLeScanner scanner;
    private static BluetoothGatt gatt;
    private static final StringBuilder REPORT = new StringBuilder();
    private static final StringBuilder READ = new StringBuilder();
    private static final List<String> NOTIF = new ArrayList<>();
    private static String current = "";

    static boolean scanning() {
        return scanning;
    }

    /** 每 4 秒讀一次（保持連線，唔好咁快被裝置 timeout 斷）。 */
    static void keepAlive() {
        try {
            if (gatt != null && connected && pingChar != null) {
                gatt.readCharacteristic(pingChar);
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean connected() {
        return connected;
    }

    static String currentMac() {
        return current;
    }

    static String connState() {
        return connState;
    }

    private static final String PF = "ble_known";

    /** 記錄「連接過」嘅 BLE 裝置（之後喺已配對清單顯示）。 */
    static void remember(Context ctx, String mac, String name) {
        try {
            ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).edit()
                    .putString(mac, name == null ? "（無名）" : name).apply();
        } catch (Throwable ignored) {
        }
    }

    static java.util.Map<String, String> known(Context ctx) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            java.util.Map<String, ?> all = ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).getAll();
            for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static void forget(Context ctx, String mac) {
        try {
            ctx.getSharedPreferences(PF, Context.MODE_PRIVATE).edit().remove(mac).apply();
        } catch (Throwable ignored) {
        }
    }

    static String current() {
        return current;
    }

    static String readLog() {
        return READ.toString();
    }

    static String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("BLE ").append(scanning ? "掃描中" : "已停")
                .append("｜連接：").append(current.isEmpty() ? "無" : current).append('\n');
        synchronized (NOTIF) {
            if (!NOTIF.isEmpty()) {
                sb.append("最近通知：\n");
                int n = 0;
                for (String s : NOTIF) {
                    sb.append("  ").append(s).append('\n');
                    if (++n >= 20) {
                        break;
                    }
                }
            }
        }
        return sb.toString();
    }

    private static BluetoothAdapter adapter(Context c) {
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm == null ? BluetoothAdapter.getDefaultAdapter() : bm.getAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    // ============================================================ 掃描

    static void startScan(final Context ctx, final L l) {
        appCtx = ctx.getApplicationContext();
        startScan(ctx, l, 10);
    }

    static void startScan(final Context ctx, final L l, final int seconds) {
        appCtx = ctx.getApplicationContext();
        if (scanning) {
            return;
        }
        try {
            BluetoothAdapter ad = adapter(ctx);
            if (ad == null || !ad.isEnabled()) {
                l.onLog("❌ 藍牙未開");
                return;
            }
            seenCount = 0;
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) {
                l.onLog("❌ 拎唔到 BLE scanner");
                return;
            }
            scanning = true;
            l.onLog("📡 BLE 掃描中（" + seconds + " 秒，低延遲）…");
            android.bluetooth.le.ScanSettings st = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build();
            currentCb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult r) {
                    if (r == null || r.getDevice() == null) {
                        return;
                    }
                    BluetoothDevice d = r.getDevice();
                    String nm;
                    try {
                        nm = d.getName();
                    } catch (Throwable t) {
                        nm = null;
                    }
                    StringBuilder uu = new StringBuilder();
                    try {
                        if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) {
                            for (android.os.ParcelUuid pu : r.getScanRecord().getServiceUuids()) {
                                if (pu != null && pu.getUuid() != null) {
                                    uu.append(pu.getUuid().toString()).append(' ');
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    seenCount++;
                    l.onDevice(nm == null ? "（無名）" : nm, d.getAddress(), r.getRssi(),
                            uu.toString().trim(), r.getTxPower());
                }

                @Override
                public void onScanFailed(int errorCode) {
                    scanning = false;
                    l.onLog("❌ 掃描失敗 code=" + errorCode);
                }
            };
            scanner.startScan(null, st, currentCb);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                stopScan();
                l.onDone("🔎 BLE 掃描完（" + seconds + " 秒）：收到 " + seenCount + " 個廣播"
                        + (seenCount == 0 ? "（可能全部已連接／唔係 BLE）" : ""));
            }, seconds * 1000L);
        } catch (Throwable t) {
            scanning = false;
            l.onLog("❌ " + t.getClass().getSimpleName() + "：" + t.getMessage());
        }
    }

    interface R {
        void onAdvert(String mac, String name, int rssi, String rawHex, long at);
    }

    private static ScanCallback rawCb;

    /** 持續監聽廣播（唔連接）：用嚟睇「撳掣」會唔會令廣播內容改變。 */
    static void startRaw(final Context ctx, final String filterMac, final int seconds, final R r) {
        appCtx = ctx.getApplicationContext();
        stopRaw();
        try {
            BluetoothAdapter ad = adapter(ctx);
            if (ad == null || !ad.isEnabled()) {
                return;
            }
            android.bluetooth.le.BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            if (sc == null) {
                return;
            }
            android.bluetooth.le.ScanSettings st = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build();
            rawCb = new ScanCallback() {
                @Override
                public void onScanResult(int type, ScanResult res) {
                    if (res == null || res.getDevice() == null) {
                        return;
                    }
                    String mac = res.getDevice().getAddress();
                    if (filterMac != null && !filterMac.isEmpty()
                            && !mac.equalsIgnoreCase(filterMac)) {
                        return;
                    }
                    String nm;
                    try {
                        nm = res.getDevice().getName();
                    } catch (Throwable t) {
                        nm = null;
                    }
                    byte[] raw = res.getScanRecord() == null ? null : res.getScanRecord().getBytes();
                    r.onAdvert(mac, nm == null ? "" : nm, res.getRssi(), hex(raw),
                            System.currentTimeMillis());
                }
            };
            sc.startScan(null, st, rawCb);
            if (seconds > 0) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        BleProbe::stopRaw, seconds * 1000L);
            }
        } catch (Throwable ignored) {
        }
    }

    static void stopRaw() {
        try {
            BluetoothAdapter ad = adapter(appCtx);
            if (ad != null && rawCb != null && ad.getBluetoothLeScanner() != null) {
                ad.getBluetoothLeScanner().stopScan(rawCb);
            }
        } catch (Throwable ignored) {
        } finally {
            rawCb = null;
        }
    }

    /** 停止掃描（一定要用返同一個 ScanCallback，否則停唔到）。 */
    static void stopScan() {
        scanning = false;
        try {
            if (scanner != null && currentCb != null) {
                scanner.stopScan(currentCb);
            }
        } catch (Throwable ignored) {
        } finally {
            currentCb = null;
        }
    }

    // ============================================================ 連接 + GATT

    static void connect(final Context ctx, final String mac, final L l) {
        appCtx = ctx.getApplicationContext();
        stopScan();                       // 掃描中連接一定失敗 → 先停
        disconnect();
        connState = "連線中…（" + mac + "）";
        notifySubs = 0;
        svcCount = 0;
        charCount = 0;
        try {
            BluetoothAdapter ad = adapter(ctx);
            if (ad == null) {
                l.onLog("❌ 冇藍牙");
                return;
            }
            final BluetoothDevice d = ad.getRemoteDevice(mac);
            current = mac;
            REPORT.setLength(0);
            REPORT.append("=== BLE GATT 探索 ").append(new java.util.Date()).append(" ===\n");
            REPORT.append("device=").append(mac).append(" name=").append(safeName(d)).append('\n');
            l.onLog("🔗 連接 " + mac + " …（已停掃描）");
            gatt = d.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    String st = newState == BluetoothProfile.STATE_CONNECTED ? "已連接"
                            : newState == BluetoothProfile.STATE_CONNECTING ? "連線中" : "已斷開";
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connState = "✅ 已連接（保持中）";
                    } else if (status != 0) {
                        connState = "❌ 失敗 status=" + status + statusHint(status);
                    } else {
                        connState = "已斷開";
                    }
                    l.onLog("狀態：" + st + "（status=" + status + statusHint(status) + "）");
                    connected = newState == BluetoothProfile.STATE_CONNECTED;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            BluetoothDevice dd = g.getDevice();
                            remember(ctx, mac, dd == null ? "?" : safeName(dd));
                            l.onLog("💾 已加入「已配對裝置」：" + mac);
                        } catch (Throwable ignored) {
                        }
                        try {
                            g.discoverServices();
                        } catch (Throwable t) {
                            l.onLog("❌ discoverServices：" + t.getClass().getSimpleName());
                        }
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    dumpServices(g, status, l);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                 byte[] v, int status) {
                    handleRead(c, v, status, l);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                 int status) {
                    handleRead(c, c.getValue(), status, l);
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    handleNotify(c, c.getValue(), l);
                }

                /** Android 13+ 走呢個 overload（車機 Android 16 一定要有）。 */
                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                    byte[] value) {
                    handleNotify(c, value, l);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            l.onLog("❌ 連接失敗 " + t.getClass().getSimpleName() + "：" + t.getMessage());
        }
    }

    /** 共用：dump GATT 表 ＋ 訂閱全部 notify／indicate。 */
    static void dumpServices(BluetoothGatt g, int status, L l) {
        StringBuilder sb = new StringBuilder("服務探索 status=" + status + "\n");
        try {
            for (BluetoothGattService s : g.getServices()) {
                svcCount++;
                sb.append("SERVICE ").append(s.getUuid()).append(" type=").append(s.getType()).append('\n');
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    charCount++;
                    sb.append("   CHAR ").append(c.getUuid())
                            .append(" props=0x").append(Integer.toHexString(c.getProperties()))
                            .append(propNames(c.getProperties())).append('\n');
                    if ((c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        enableNotify(g, c, l);
                    }
                    if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        String cu = c.getUuid().toString();
                        if (pingChar == null || cu.startsWith("00002a19")
                                || cu.startsWith("0000fff5")) {
                            pingChar = c;
                        }
                        try {
                            g.readCharacteristic(c);      // 讀晒可讀值（協議分析用）
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("❌ ").append(t.getClass().getSimpleName()).append('\n');
        }
        REPORT.append(sb).append('\n');
        gattSummary = sb.toString();
        l.onLog("📋 GATT 表已dump（" + (sb.toString().split("\n").length - 1) + " 行）");
    }

    /** 讀值記錄（協議分析：睇每個 char 嘅內容）。 */
    private static void handleRead(BluetoothGattCharacteristic c, byte[] v, int status, L l) {
        String us = c.getUuid().toString();
        String u = us.length() > 8 ? us.substring(4, 8) : us;
        String hex = v == null ? "null" : hex(v);
        READ.append("READ ").append(u).append(" status=").append(status).append(" = ").append(hex).append('\n');
        l.onLog("📖 " + u + " = " + hex);
    }

    private static void handleNotify(BluetoothGattCharacteristic c, byte[] v, L l) {
        String hex = hex(v);
        String u = c.getUuid().toString();
        String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new java.util.Date()) + "  "
                + (u.length() > 8 ? u.substring(4, 8) : u) + "  " + hex;
        synchronized (NOTIF) {
            NOTIF.add(0, line);
            while (NOTIF.size() > 60) {
                NOTIF.remove(NOTIF.size() - 1);
            }
        }
        REPORT.append("NOTIFY ").append(line).append('\n');
        nUuid = u.length() > 8 ? u.substring(4, 8) : u;
        nHex = hex;
        nAt = System.currentTimeMillis();
        nCount++;
        l.onLog("🔔 " + hex);
    }

    private static void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic c, L l) {
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(
                    java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (d != null) {
                notifySubs++;
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
                l.onLog("✅ 已訂閱 " + c.getUuid().toString().substring(4, 8));
            }
        } catch (Throwable t) {
            l.onLog("⚠️ 訂閱失敗 " + t.getClass().getSimpleName());
        }
    }

    static void disconnect() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Throwable ignored) {
        }
        gatt = null;
        current = "";
        connected = false;
        autoPending = false;
        connState = "未連接";
    }

    static void upload(Context ctx, L l) {
        String name = "ble-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                Locale.US).format(new java.util.Date()) + ".txt";
        String up = "（本機記錄）";
        Diag.send(ctx, "bt-ble", REPORT + "\n\n" + text());
        l.onDone("📤 已上載：" + up);
    }

    // ============================================================ helpers

    /** GATT status 解釋（133 = 常見 generic error）。 */
    static String statusHint(int status) {
        switch (status) {
            case 0: return " OK";
            case 133: return " = GATT_ERROR（通常：裝置拒絕／距離遠／已被連接）";
            case 8: return " = 連線超時";
            case 19: return " = 對端斷開";
            case 22: return " = 本地斷開";
            case 137: return " = 已連接（重複）";
            default: return "";
        }
    }

    /** 用 autoConnect 再試一次（部分裝置只接受呢種）。 */
    static volatile boolean autoPending = false;

    static void connectAuto(final Context ctx, final String mac, final L l) {
        if (autoPending) {
            return;                      // 已經常駐等待，唔好重複發
        }
        try {
            BluetoothAdapter ad = adapter(ctx);
            if (ad == null) {
                l.onLog("❌ 冇藍牙");
                return;
            }
            final BluetoothDevice d = ad.getRemoteDevice(mac);
            current = mac;
            autoPending = true;
            connState = "⏳ 常駐等待（autoConnect）…撳一下掣就會連";
            l.onLog("⏳ autoConnect 常駐：裝置一出現就自動連（唔使再撳掣）");
            gatt = d.connectGatt(ctx, true, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    l.onLog("狀態：" + (newState == BluetoothProfile.STATE_CONNECTED ? "已連接" : "已斷開")
                            + "（status=" + status + statusHint(status) + "）");
                    connected = newState == BluetoothProfile.STATE_CONNECTED;
                    connected = newState == BluetoothProfile.STATE_CONNECTED;
                    connState = connected ? "✅ 已連接（autoConnect 保持中）"
                            : (status != 0 ? "⏳ 等緊裝置（status=" + status + "）" : "⏳ 等緊裝置…");
                    if (connected) {
                        try {
                            BluetoothDevice dd = g.getDevice();
                            remember(ctx, mac, dd == null ? "?" : safeName(dd));
                        } catch (Throwable ignored) {
                        }
                        g.discoverServices();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    dumpServices(g, status, l);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                 byte[] v, int status) {
                    handleRead(c, v, status, l);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                 int status) {
                    handleRead(c, c.getValue(), status, l);
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    handleNotify(c, c.getValue(), l);
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                    byte[] value) {
                    handleNotify(c, value, l);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            autoPending = false;
            l.onLog("❌ autoConnect 失敗 " + t.getClass().getSimpleName());
        }
    }

    private static String propNames(int p) {
        StringBuilder sb = new StringBuilder(" [");
        if ((p & 0x02) != 0) sb.append("READ ");
        if ((p & 0x08) != 0) sb.append("WRITE ");
        if ((p & 0x04) != 0) sb.append("WRITE_NO_RESP ");
        if ((p & 0x10) != 0) sb.append("NOTIFY ");
        if ((p & 0x20) != 0) sb.append("INDICATE ");
        return sb.append(']').toString();
    }

    private static String hex(byte[] v) {
        if (v == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : v) {
            sb.append(String.format(Locale.US, "%02X ", b));
        }
        return sb.toString().trim();
    }

    private static String safeName(BluetoothDevice d) {
        try {
            return d.getName() == null ? "?" : d.getName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
