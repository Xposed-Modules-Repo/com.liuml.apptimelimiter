package com.liuml.apptimelimiter.xposed;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

/** Visible test-only host: OEM background-start rules remain enabled during the test. */
public class CooldownTestHostActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView message = new TextView(this);
        message.setText("时停：正在验证两个隔离测试进程的冷却事务。不会修改管控规则或 PIN。");
        message.setPadding(32, 64, 32, 32);
        setContentView(message);
    }

    @Override public void onResume() {
        super.onResume();
        startService(new Intent(this, CooldownTestService.class));
        startService(new Intent(this, CooldownRemoteTestService.class));
    }

    @Override public void onDestroy() {
        stopService(new Intent(this, CooldownTestService.class));
        stopService(new Intent(this, CooldownRemoteTestService.class));
        super.onDestroy();
    }
}
