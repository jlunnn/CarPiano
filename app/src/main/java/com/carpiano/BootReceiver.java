package com.carpiano;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 開機／更新後嘅重啟入口。
 *
 * ⚠️ 部分車機（虛擬化容器環境）唔會派開機廣播
 * → 所以呢個 receiver 只係「萬一收到」嘅保險。
 *
 * 真正嘅「開車自動開啟」係 AutoService 輪詢點火值（進程挨得過深睡，實測 pid 不變）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (context.getSharedPreferences("piano", Context.MODE_PRIVATE)
                    .getBoolean("auto_enabled", false)) {
                Intent svc = new Intent(context, AutoService.class);
                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(svc);
                    } else {
                        context.startService(svc);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
