package com.liuml.apptimelimiter.xposed;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import android.os.Process;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Java intentionally: a standalone test-APK service cannot rely on target APK Kotlin classes
 * being on its classpath. Load the installed target's exact storage adapter (not a test copy).
 * All files remain in the test APK's noBackupFilesDir; no production path is ever accepted.
 */
public class CooldownTestService extends Service {
    private static final String TARGET = "com.liuml.apptimelimiter";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Messenger endpoint;
    private Class<?> storeType;

    @Override public void onCreate() {
        super.onCreate();
        endpoint = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            try {
                int targetUid = getPackageManager().getApplicationInfo(TARGET, 0).uid;
                if (message.sendingUid != targetUid && message.sendingUid != Process.myUid()) return true;
            } catch (Exception ignored) { return true; }
            Bundle request = new Bundle(message.getData());
            Messenger reply = message.replyTo;
            executor.execute(() -> execute(request, reply));
            return true;
        }));
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private void execute(Bundle request, Messenger reply) {
        Bundle result = new Bundle();
        result.putInt("pid", Process.myPid());
        result.putInt("uid", Process.myUid());
        try {
            String name = request.getString("name", "");
            if (!name.matches("cooldown_mp_[a-f0-9-]{36}")) throw new IllegalArgumentException("test name");
            File file = new File(getNoBackupFilesDir(), name + ".sqlite");
            if (request.getBoolean("cleanup")) {
                SQLiteDatabase.deleteDatabase(file);
                deleteSharedPreferences(name);
                result.putBoolean("ok", true);
            } else {
                if (storeType == null) {
                    ApplicationInfo target = getPackageManager().getApplicationInfo(TARGET, 0);
                    ClassLoader loader = new PathClassLoader(target.sourceDir, getClassLoader());
                    storeType = loader.loadClass("com.liuml.apptimelimiter.xposed.LocalCooldownStore");
                }
                Class<?> type = storeType;
                Constructor<?> constructor = null;
                for (Constructor<?> candidate : type.getConstructors()) {
                    if (candidate.getParameterTypes().length == 6) constructor = candidate;
                }
                if (constructor == null) throw new IllegalStateException("Store default constructor missing");
                Object store = constructor.newInstance(this, file, name, null, 8, null);
                if (request.getBoolean("prepare")) {
                    type.getMethod("read", String.class, long.class).invoke(store, "app:mp:60000", 60000L);
                    result.putBoolean("ok", true);
                } else {
                long wait = request.getLong("startElapsed") - SystemClock.elapsedRealtime();
                if (wait > 0) Thread.sleep(Math.min(wait, 5000));
                Object claim = type.getMethod("claim", String.class, long.class, String.class, String.class, long.class)
                    .invoke(store, "app:mp:60000", 60000L, "same-incident", TARGET, request.getLong("occurredAt"));
                Object record = claim.getClass().getMethod("getRecord").invoke(claim);
                result.putBoolean("new", (Boolean) claim.getClass().getMethod("isNewIncident").invoke(claim));
                result.putBoolean("started", (Boolean) claim.getClass().getMethod("getCooldownStarted").invoke(claim));
                result.putLong("endWall", (Long) record.getClass().getMethod("getEndsAtMillis").invoke(record));
                result.putLong("endElapsed", (Long) record.getClass().getMethod("getEndsAtElapsedMillis").invoke(record));
                result.putBoolean("ok", true);
                }
            }
        } catch (Throwable failure) {
            result.putString("error", failure.toString() + " / " + String.valueOf(failure.getCause()));
        }
        try {
            Message response = Message.obtain(); response.setData(result); reply.send(response);
        } catch (RemoteException ignored) { /* Test timeout reports the missing response. */ }
    }
}
