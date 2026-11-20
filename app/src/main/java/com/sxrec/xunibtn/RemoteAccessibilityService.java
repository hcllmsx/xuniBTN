package com.sxrec.xunibtn;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

public final class RemoteAccessibilityService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static RemoteAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SettingsStore settings;
    private OverlayController overlay;
    private String lastError = "";
    private boolean registeredReceiver;
    private long lastToast;
    private final Runnable refresh = () -> { if (overlay != null) overlay.update(false); };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) overlay.hide();
            else overlay.update(true);
        }
    };

    public static RemoteAccessibilityService getInstance() { return instance; }
    public boolean isOverlayShowing() { return overlay != null && overlay.isShowing(); }
    public String getLastError() { return lastError; }

    public int registeredDirectionCount() {
        return (int) getSystemActions().stream().filter(action -> action.getId() >= GLOBAL_ACTION_DPAD_UP
                && action.getId() <= GLOBAL_ACTION_DPAD_CENTER).count();
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (settings == null) {
            settings = new SettingsStore(this);
            settings.prefs.registerOnSharedPreferenceChangeListener(this);
            overlay = new OverlayController(this, settings);
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registeredReceiver = true;
        }
        overlay.update(true);
    }

    boolean canDisplay() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    void sendRemoteAction(int action) {
        if (!canDisplay()) return;
        boolean supported = action == GLOBAL_ACTION_BACK || (action >= GLOBAL_ACTION_DPAD_UP && action <= GLOBAL_ACTION_DPAD_CENTER);
        if (!supported) return;
        try {
            if (performGlobalAction(action)) lastError = "";
            else reportError("系统未接受按键，请检查无障碍服务或重新开启服务。", null);
        } catch (SecurityException | IllegalStateException error) {
            reportError("按键发送失败，请重新开启无障碍服务。", error);
        }
    }

    void clearOverlayError() { lastError = ""; }

    void reportError(String message, Exception error) {
        lastError = message;
        if (overlay != null) overlay.cancelPresses();
        if (error != null) Log.e("xuniBTN", message, error);
        if (SystemClock.elapsedRealtime() - lastToast > 3500) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            lastToast = SystemClock.elapsedRealtime();
        }
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences shared, String key) {
        if (key != null && (key.endsWith("_x") || key.endsWith("_y"))) return;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (overlay != null) overlay.update(true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { if (overlay != null) overlay.cancelPresses(); }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) overlay.hide();
        if (settings != null) settings.prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (registeredReceiver) unregisterReceiver(screenReceiver);
        registeredReceiver = false;
        settings = null;
        overlay = null;
        if (instance == this) instance = null;
    }

    @Override public boolean onUnbind(Intent intent) {
        release();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        release();
        super.onDestroy();
    }
}
