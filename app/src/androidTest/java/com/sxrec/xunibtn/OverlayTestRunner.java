package com.sxrec.xunibtn;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

public final class OverlayTestRunner extends Instrumentation {
    private final StringBuilder results = new StringBuilder();
    private SettingsStore settings;
    private InputProbeActivity probe;
    private int tests;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(UpdateChecker.compareVersions("26.11.19", "26.9.30") > 0, "version comparison is numeric");
            check(UpdateChecker.compareVersions("26.11.19.0", "26.11.19") == 0, "trailing version zeros compare equally");
            check(UpdateChecker.extractNotes("### 更新内容\n- a---b\n---\n- private download guide").contains("a---b"), "notes preserve inline hyphens");
            check(!UpdateChecker.extractNotes("### 更新内容\n- visible\n---\n- download guide").contains("download guide"), "notes exclude download section");
            UpdateChecker.Result release = UpdateChecker.parseRelease("{\"tag_name\":\"v26.11.20\",\"draft\":false,\"prerelease\":false,\"body\":\"### 更新内容\\n- test\"}", "26.11.19");
            check(release.newer && release.successful, "valid stable release is accepted");
            boolean rejected = false;
            try {
                UpdateChecker.parseRelease("{\"tag_name\":\"v99.1.1\",\"draft\":true,\"prerelease\":false,\"body\":\"test\"}", "26.11.19");
            } catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "draft releases are rejected");
            settings = new SettingsStore(getTargetContext());
            probe = (InputProbeActivity) startActivitySync(new Intent(getTargetContext(), InputProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            // Starting instrumentation force-stops the target package, which also drops the
            // accessibility service. The service cannot be re-enabled from inside the app
            // (secure settings are writable from the shell domain only), so the harness enables
            // it from the host while this wait is running, then binds to this process.
            await(() -> RemoteAccessibilityService.getInstance() != null, "service connected");
            runOnMainSync(() -> {
                settings.putBoolean("enabled", true);
                settings.putBoolean("collapsed", false);
                settings.putInt("size", 220);
                settings.putInt("opacity", 80);
                settings.putBoolean("repeat", true);
                settings.resetPosition();
            });
            settle();
            await(() -> RemoteAccessibilityService.getInstance().isOverlayShowing(), "overlay attached");
            check(RemoteAccessibilityService.getInstance().registeredDirectionCount() == 5, "all five system actions registered");
            int[] position = panelPosition();
            int x = position[0], y = position[1], d = position[2], handle = dp(52);
            float[][] points = {{.5f, .17f}, {.83f, .5f}, {.5f, .83f}, {.17f, .5f}, {.5f, .5f}};
            int[] codes = {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER};
            for (int i = 0; i < codes.length; i++) {
                int before = InputProbeActivity.DOWN.get(codes[i]);
                tap(x + d * points[i][0], y + handle + d * points[i][1], 60);
                final int code = codes[i];
                await(() -> InputProbeActivity.DOWN.get(code) == before + 1, "key down " + code);
                await(() -> InputProbeActivity.UP.get(code) == InputProbeActivity.DOWN.get(code), "balanced key up " + code);
            }
            check(InputProbeActivity.CLICKS.get() > 0, "center activates focused target");
            int centerBefore = InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_CENTER);
            tap(x + d * .5f, y + handle + d * .5f, 950);
            check(InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_CENTER) == centerBefore + 1, "holding center confirms only once");
            int beforeCancel = InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_UP);
            long cancelledAt = SystemClock.uptimeMillis();
            pointer(cancelledAt, cancelledAt, MotionEvent.ACTION_DOWN, x + d * .5f, y + handle + d * .17f);
            pointer(cancelledAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x + d * .5f, y + handle + d * .17f);
            SystemClock.sleep(650);
            check(InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_UP) == beforeCancel + 1, "cancel event stops direction repeats");
            int beforeRepeat = InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_RIGHT);
            tap(x + d * .83f, y + handle + d * .5f, 950);
            check(InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_RIGHT) >= beforeRepeat + 3, "hold repeats directions");
            int stopped = InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_RIGHT);
            SystemClock.sleep(450);
            check(InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_RIGHT) == stopped, "release cancels repeat");
            runOnMainSync(() -> settings.putBoolean("repeat", false));
            settle();
            int noRepeat = InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_RIGHT);
            tap(x + d * .83f, y + handle + d * .5f, 950);
            check(InputProbeActivity.DOWN.get(KeyEvent.KEYCODE_DPAD_RIGHT) == noRepeat + 1, "repeat disabled means one press");
            int[] target = new int[2];
            runOnMainSync(() -> probe.touchTarget.getLocationOnScreen(target));
            tap(target[0] + dp(35), target[1] + dp(20), 60);
            check(InputProbeActivity.TOUCHES.get() == 1, "touch outside overlay reaches foreground");
            int beforeDrag = totalKeys();
            drag(x + d / 2f, y + dp(24), dp(90), dp(240));
            check(totalKeys() == beforeDrag, "drag does not inject keys");
            check(settings.prefs.contains("portrait_x") || settings.prefs.contains("landscape_x"), "drag position persisted");
            runOnMainSync(() -> settings.putBoolean("collapsed", true));
            settle();
            check(RemoteAccessibilityService.getInstance().isOverlayShowing(), "collapsed bubble attached");
            int[] bubble = bubblePosition();
            tap(bubble[0], bubble[1], 60);
            check(!settings.collapsed(), "tapping bubble expands the controls");
            check(RemoteAccessibilityService.getInstance().isOverlayShowing(), "expanded controls remain attached");
            runOnMainSync(() -> settings.putBoolean("enabled", false));
            settle();
            check(!RemoteAccessibilityService.getInstance().isOverlayShowing(), "hide removes overlay window");
            runOnMainSync(() -> {
                settings.putBoolean("enabled", true);
                settings.resetPosition();
                settings.putInt("opacity", 25);
                settings.putInt("size", 320);
            });
            settle();
            check(RemoteAccessibilityService.getInstance().isOverlayShowing(), "maximum size and minimum opacity render");
            runOnMainSync(() -> {
                settings.putInt("size", 220);
                settings.putInt("opacity", 80);
                settings.putBoolean("repeat", true);
                settings.putBoolean("enabled", false);
                probe.finish();
            });
            result.putString("stream", "\nPASS: " + tests + " checks\n" + results);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + tests + " checks\n" + results + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private int[] panelPosition() {
        WindowMetrics metrics = getTargetContext().getSystemService(WindowManager.class).getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int left = insets.left + dp(6), top = insets.top + dp(6);
        int width = bounds.width() - insets.right - dp(6) - left;
        int height = bounds.height() - insets.bottom - dp(6) - top;
        int d = Math.min(dp(220), Math.min(width, height - dp(104)));
        return new int[]{left + width - d, top + Math.round((height - d - dp(104)) * .6f), d};
    }

    private int[] bubblePosition() {
        WindowMetrics metrics = getTargetContext().getSystemService(WindowManager.class).getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        String orientation = bounds.width() > bounds.height() ? "landscape" : "portrait";
        float x = settings.prefs.getFloat(orientation + "_x", 1f);
        float y = settings.prefs.getFloat(orientation + "_y", .6f);
        int left = insets.left + dp(6), top = insets.top + dp(6);
        int width = bounds.width() - insets.right - dp(6) - left;
        int height = bounds.height() - insets.bottom - dp(6) - top;
        return new int[]{left + Math.round((width - dp(56)) * x) + dp(28),
                top + Math.round((height - dp(56)) * y) + dp(28)};
    }

    private int totalKeys() {
        int total = 0;
        for (int i = KeyEvent.KEYCODE_DPAD_UP; i <= KeyEvent.KEYCODE_DPAD_CENTER; i++) total += InputProbeActivity.DOWN.get(i);
        return total;
    }

    private void tap(float x, float y, int duration) {
        long time = SystemClock.uptimeMillis();
        pointer(time, time, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(duration);
        pointer(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
        settle();
    }

    private void drag(float x, float y, float endX, float endY) {
        long time = SystemClock.uptimeMillis();
        pointer(time, time, MotionEvent.ACTION_DOWN, x, y);
        for (int step = 1; step <= 12; step++) {
            SystemClock.sleep(20);
            pointer(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    x + (endX - x) * step / 12, y + (endY - y) * step / 12);
        }
        pointer(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, endY);
        settle();
    }

    private void pointer(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // Shell identity is required to reach the accessibility overlay window.
        getUiAutomation().injectInputEvent(event, true);
        event.recycle();
    }

    private void settle() { waitForIdleSync(); SystemClock.sleep(200); }
    private int dp(float value) { return Ui.dp(getTargetContext(), value); }
    private void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        tests++;
        results.append("  OK ").append(name).append('\n');
    }
    private void await(java.util.function.BooleanSupplier condition, String name) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        check(condition.getAsBoolean(), name);
    }
}
