package com.sxrec.xunibtn;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
    public static final int MIN_SIZE = 160;
    public static final int MAX_SIZE = 320;
    public final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences("remote_settings", Context.MODE_PRIVATE);
    }

    public boolean enabled() { return prefs.getBoolean("enabled", false); }
    public boolean collapsed() { return prefs.getBoolean("collapsed", false); }
    public boolean repeat() { return prefs.getBoolean("repeat", true); }
    public boolean haptic() { return prefs.getBoolean("haptic", true); }
    public int size() { return clamp(prefs.getInt("size", 220), MIN_SIZE, MAX_SIZE); }
    public int opacity() { return clamp(prefs.getInt("opacity", 80), 25, 100); }
    public int interval() { return clamp(prefs.getInt("interval", 160), 100, 350); }
    public void putBoolean(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    public void putInt(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    public void resetPosition() {
        prefs.edit().remove("portrait_x").remove("portrait_y")
                .remove("landscape_x").remove("landscape_y").putBoolean("collapsed", false)
                .putInt("position_revision", prefs.getInt("position_revision", 0) + 1).apply();
    }
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
