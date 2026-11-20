package com.sxrec.xunibtn;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class OverlayController {
    private final RemoteAccessibilityService service;
    private final SettingsStore settings;
    private final WindowManager manager;
    private View root;
    private DialView dial;
    private WindowManager.LayoutParams params;
    private Rect safeBounds;
    private String orientation;
    private boolean collapsed;
    private int size;
    private int revision;
    private boolean added;

    OverlayController(RemoteAccessibilityService service, SettingsStore settings) {
        this.service = service;
        this.settings = settings;
        manager = service.getSystemService(WindowManager.class);
    }

    boolean isShowing() { return root != null && root.isAttachedToWindow(); }

    // Drag positions use physical screen coordinates, independent of text direction.
    @android.annotation.SuppressLint("RtlHardcoded")
    void update(boolean forceRebuild) {
        if (!settings.enabled() || !service.canDisplay()) {
            hide();
            return;
        }
        int nextRevision = settings.prefs.getInt("position_revision", 0);
        if (!forceRebuild && added && collapsed == settings.collapsed()
                && size == settings.size() && revision == nextRevision) {
            root.setAlpha(settings.opacity() / 100f);
            cancelPresses();
            return;
        }
        hide();
        collapsed = settings.collapsed();
        size = settings.size();
        revision = nextRevision;
        readBounds();
        int diameter = Math.min(Ui.dp(service, size), Math.min(safeBounds.width(),
                safeBounds.height() - Ui.dp(service, 104)));
        diameter = Math.max(Ui.dp(service, 96), diameter);
        int width = collapsed ? Ui.dp(service, 56) : diameter;
        int height = collapsed ? width : diameter + Ui.dp(service, 104);
        params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.setTitle("虚拟按钮方向盘");
        float x = settings.prefs.getFloat(orientation + "_x", 1f);
        float y = settings.prefs.getFloat(orientation + "_y", .6f);
        params.x = safeBounds.left + Math.round(Math.max(0, safeBounds.width() - width) * x);
        params.y = safeBounds.top + Math.round(Math.max(0, safeBounds.height() - height) * y);
        clampPosition();
        root = collapsed ? buildBubble() : buildPanel(diameter);
        root.setAlpha(settings.opacity() / 100f);
        try {
            manager.addView(root, params);
            added = true;
            service.clearOverlayError();
        } catch (WindowManager.BadTokenException | SecurityException | IllegalArgumentException error) {
            root = null;
            dial = null;
            service.reportError("系统未能显示悬浮按钮，请重新开启无障碍服务。", error);
        }
    }

    private void readBounds() {
        WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        safeBounds = new Rect(insets.left + Ui.dp(service, 6), insets.top + Ui.dp(service, 6),
                bounds.width() - insets.right - Ui.dp(service, 6),
                bounds.height() - insets.bottom - Ui.dp(service, 6));
        orientation = bounds.width() > bounds.height() ? "landscape" : "portrait";
    }

    private View buildBubble() {
        Button bubble = Ui.button(service, "OK", true);
        bubble.setTextSize(16);
        bubble.setPadding(0, 0, 0, 0);
        bubble.setMinWidth(0);
        bubble.setMinimumWidth(0);
        bubble.setBackground(Ui.shape(service, Ui.ACCENT, 28, false));
        bubble.setContentDescription("展开虚拟按钮，按住可拖动");
        bubble.setOnClickListener(v -> settings.putBoolean("collapsed", false));
        bindDrag(bubble, true);
        return bubble;
    }

    private View buildPanel(int diameter) {
        LinearLayout panel = Ui.column(service);
        panel.setMotionEventSplittingEnabled(false);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView handle = Ui.text(service, "⠿  拖动位置", 13, Ui.TEXT, false);
        handle.setGravity(Gravity.CENTER);
        handle.setBackground(Ui.shape(service, 0xE6212A24, 24, true));
        handle.setContentDescription("拖动方向盘位置");
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(diameter, Ui.dp(service, 48));
        handleParams.bottomMargin = Ui.dp(service, 4);
        panel.addView(handle, handleParams);
        bindDrag(handle, false);
        dial = new DialView(service, settings, service::sendRemoteAction);
        panel.addView(dial, new LinearLayout.LayoutParams(diameter, diameter));
        LinearLayout toolbar = new LinearLayout(service);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setBackground(Ui.shape(service, 0xF0212A24, 24, true));
        addTool(toolbar, "返回", "返回上一级", () -> service.sendRemoteAction(AccessibilityService.GLOBAL_ACTION_BACK));
        addTool(toolbar, "收起", "收起为悬浮球", () -> settings.putBoolean("collapsed", true));
        addTool(toolbar, "设置", "打开虚拟按钮设置", () -> {
            settings.putBoolean("collapsed", true);
            Intent intent = new Intent(service, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            service.startActivity(intent);
        });
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(diameter, Ui.dp(service, 48));
        toolbarParams.topMargin = Ui.dp(service, 4);
        panel.addView(toolbar, toolbarParams);
        return panel;
    }

    private void addTool(LinearLayout row, String text, String description, Runnable action) {
        Button button = Ui.button(service, text, false);
        button.setContentDescription(description);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x335F7942), null,
                Ui.shape(service, Ui.TEXT, 24, false)));
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
    }

    private void bindDrag(View view, boolean clickable) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private int windowX, windowY;
            private int pointer = -1;
            private boolean dragging;
            private final int slop = ViewConfiguration.get(service).getScaledTouchSlop();

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelPresses();
                        pointer = event.getPointerId(0);
                        startX = event.getRawX();
                        startY = event.getRawY();
                        windowX = params.x;
                        windowY = params.y;
                        dragging = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (pointer < 0) return true;
                        int index = event.findPointerIndex(pointer);
                        if (index < 0) return true;
                        float dx = event.getRawX(index) - startX, dy = event.getRawY(index) - startY;
                        if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                        if (dragging && isShowing()) {
                            v.setPressed(false);
                            params.x = windowX + Math.round(dx);
                            params.y = windowY + Math.round(dy);
                            clampPosition();
                            manager.updateViewLayout(root, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (pointer >= 0) {
                            if (dragging) savePosition();
                            else if (clickable) v.performClick();
                        }
                        pointer = -1;
                        v.setPressed(false);
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) savePosition();
                        pointer = -1;
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void clampPosition() {
        params.x = SettingsStore.clamp(params.x, safeBounds.left, Math.max(safeBounds.left, safeBounds.right - params.width));
        params.y = SettingsStore.clamp(params.y, safeBounds.top, Math.max(safeBounds.top, safeBounds.bottom - params.height));
    }

    private void savePosition() {
        float x = (params.x - safeBounds.left) / (float) Math.max(1, safeBounds.width() - params.width);
        float y = (params.y - safeBounds.top) / (float) Math.max(1, safeBounds.height() - params.height);
        settings.prefs.edit().putFloat(orientation + "_x", x).putFloat(orientation + "_y", y).apply();
    }

    void cancelPresses() { if (dial != null) dial.cancelPresses(); }

    void hide() {
        cancelPresses();
        if (added) manager.removeViewImmediate(root);
        added = false;
        root = null;
        dial = null;
    }
}
