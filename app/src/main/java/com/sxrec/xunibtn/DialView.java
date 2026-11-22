package com.sxrec.xunibtn;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import java.util.function.IntConsumer;

@android.annotation.SuppressLint("ViewConstructor")
public final class DialView extends ViewGroup {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SettingsStore settings;
    private final IntConsumer listener;
    private final KeyButton[] keys = new KeyButton[5];
    private final Path segment = new Path();
    private final RectF outerOval = new RectF();
    private final RectF innerOval = new RectF();

    public DialView(Context context, SettingsStore settings, IntConsumer listener) {
        super(context);
        this.settings = settings;
        this.listener = listener;
        setWillNotDraw(false);
        setClipChildren(false);
        setMotionEventSplittingEnabled(false);
        int[] actions = {AccessibilityService.GLOBAL_ACTION_DPAD_UP,
                AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT,
                AccessibilityService.GLOBAL_ACTION_DPAD_DOWN,
                AccessibilityService.GLOBAL_ACTION_DPAD_LEFT,
                AccessibilityService.GLOBAL_ACTION_DPAD_CENTER};
        String[] names = {"向上", "向右", "向下", "向左", "确定"};
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new KeyButton(context, actions[i], names[i], i);
            addView(keys[i]);
        }
        if (listener == null) setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        refreshAppearance();
    }

    public void refreshAppearance() {
        if (listener == null) setAlpha(settings.opacity() / 100f);
        invalidate();
    }

    public void cancelPresses() {
        for (KeyButton key : keys) key.cancel();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int desired = Ui.dp(getContext(), settings.size());
        int size = Math.min(resolveSize(desired, widthSpec), resolveSize(desired, heightSpec));
        setMeasuredDimension(size, size);
        for (int i = 0; i < keys.length; i++) {
            int width = Math.round(size * (i == 4 ? .34f : (i % 2 == 0 ? .36f : .30f)));
            int height = Math.round(size * (i == 4 ? .34f : (i % 2 == 0 ? .30f : .36f)));
            keys[i].measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float size = Math.min(right - left, bottom - top);
        float[][] centers = {{.5f, .17f}, {.83f, .5f}, {.5f, .83f}, {.17f, .5f}, {.5f, .5f}};
        for (int i = 0; i < keys.length; i++) {
            int w = keys[i].getMeasuredWidth(), h = keys[i].getMeasuredHeight();
            int x = Math.round(size * centers[i][0] - w / 2f);
            int y = Math.round(size * centers[i][1] - h / 2f);
            keys[i].layout(x, y, x + w, y + h);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float center = getWidth() / 2f;
        float radius = center - Ui.dp(getContext(), 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF0212A24);
        canvas.drawCircle(center, center, radius, paint);
        paint.setColor(Ui.ACCENT);
        for (int i = 0; i < 4; i++) {
            if (keys[i].isPressed() || keys[i].isFocused()) drawSegment(canvas, center, radius, i);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(0xFF536347);
        canvas.drawCircle(center, center, radius, paint);
        paint.setColor(0xFF3C493D);
        for (int i = 0; i < 4; i++) {
            double angle = Math.toRadians(45 + 90 * i);
            canvas.drawLine(center + (float) Math.cos(angle) * radius * .43f,
                    center + (float) Math.sin(angle) * radius * .43f,
                    center + (float) Math.cos(angle) * radius * .88f,
                    center + (float) Math.sin(angle) * radius * .88f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSegment(Canvas canvas, float center, float radius, int direction) {
        float inner = radius * .43f;
        float start = 225 + 90 * direction;
        outerOval.set(center - radius, center - radius, center + radius, center + radius);
        innerOval.set(center - inner, center - inner, center + inner, center + inner);
        segment.reset();
        segment.arcTo(outerOval, start, 90);
        segment.arcTo(innerOval, start + 90, -90);
        segment.close();
        canvas.drawPath(segment, paint);
    }

    private final class KeyButton extends Button {
        private final int action;
        private final int direction;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private boolean touching;
        private boolean dispatchedOnDown;
        private int pointer = -1;
        private final Runnable repeatTask = new Runnable() {
            @Override public void run() {
                if (!touching || !isPressed() || listener == null || !settings.repeat()) return;
                listener.accept(action);
                handler.postDelayed(this, settings.interval());
            }
        };

        KeyButton(Context context, int action, String label, int direction) {
            super(context);
            this.action = action;
            this.direction = direction;
            setBackground(null);
            setContentDescription(label);
            setFocusable(listener != null);
            setClickable(listener != null);
            setSoundEffectsEnabled(false);
            setPadding(0, 0, 0, 0);
        }

        void cancel() {
            handler.removeCallbacks(repeatTask);
            touching = false;
            dispatchedOnDown = false;
            pointer = -1;
            setPressed(false);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
            View parent = (View) getParent();
            if (parent != null) parent.invalidate();
        }

        @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previouslyFocusedRect) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect);
            invalidate();
            View parent = (View) getParent();
            if (parent != null) parent.invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - Ui.dp(getContext(), 2);
            boolean active = isPressed() || isFocused();
            keyPaint.setStyle(Paint.Style.FILL);
            if (direction == 4) {
                keyPaint.setColor(active ? Ui.ACCENT : 0xFFD5E4B5);
                canvas.drawCircle(cx, cy, r, keyPaint);
            }
            keyPaint.setColor(direction == 4 || active ? Ui.INK : Ui.TEXT);
            if (direction == 4) {
                keyPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                keyPaint.setTextSize(Math.min(Ui.dp(getContext(), 22), r * .72f));
                keyPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("OK", cx, cy - (keyPaint.ascent() + keyPaint.descent()) / 2f, keyPaint);
            } else {
                canvas.save();
                canvas.rotate(direction * 90f, cx, cy);
                float d = Ui.dp(getContext(), 7);
                arrow.reset();
                arrow.moveTo(cx - d, cy + d / 2);
                arrow.lineTo(cx, cy - d / 2);
                arrow.lineTo(cx + d, cy + d / 2);
                keyPaint.setStyle(Paint.Style.STROKE);
                keyPaint.setStrokeCap(Paint.Cap.ROUND);
                keyPaint.setStrokeJoin(Paint.Join.ROUND);
                keyPaint.setStrokeWidth(Ui.dp(getContext(), 2.5f));
                canvas.drawPath(arrow, keyPaint);
                keyPaint.setStyle(Paint.Style.FILL);
                canvas.restore();
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (listener == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancel();
                    pointer = event.getPointerId(0);
                    touching = true;
                    setPressed(true);
                    if (settings.haptic()) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (direction != 4) {
                        dispatchedOnDown = true;
                        listener.accept(action);
                        if (settings.repeat()) handler.postDelayed(repeatTask, 450);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(pointer);
                    if (index < 0 || event.getX(index) < 0 || event.getY(index) < 0
                            || event.getX(index) >= getWidth() || event.getY(index) >= getHeight()) cancel();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (touching && isPressed()) performClick();
                    cancel();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            if (listener != null && !dispatchedOnDown) listener.accept(action);
            return true;
        }

        @Override protected void onDetachedFromWindow() {
            cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) cancel();
        }
    }
}
