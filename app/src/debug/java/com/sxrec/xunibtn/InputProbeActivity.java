package com.sxrec.xunibtn;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public final class InputProbeActivity extends Activity {
    public static final AtomicIntegerArray DOWN = new AtomicIntegerArray(300);
    public static final AtomicIntegerArray UP = new AtomicIntegerArray(300);
    public static final AtomicInteger CLICKS = new AtomicInteger();
    public static final AtomicInteger TOUCHES = new AtomicInteger();
    public final Button[] buttons = new Button[9];
    public Button touchTarget;
    private TextView events;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(24, 24, 24, 24);
        root.addView(Ui.text(this, "按键验证 · 仅调试构建", 22, Ui.ACCENT, true));
        events = Ui.text(this, "等待虚拟方向键", 18, Ui.TEXT, false);
        root.addView(events);
        for (int y = 0; y < 3; y++) {
            LinearLayout row = new LinearLayout(this);
            for (int x = 0; x < 3; x++) {
                int index = y * 3 + x;
                Button button = new Button(this);
                button.setId(View.generateViewId());
                button.setText(String.format(java.util.Locale.ROOT, "选项 %d", index + 1));
                button.setOnClickListener(v -> CLICKS.incrementAndGet());
                row.addView(button, new LinearLayout.LayoutParams(0, 90, 1));
                buttons[index] = button;
            }
            root.addView(row);
        }
        touchTarget = Ui.button(this, "窗口外触摸验证", false);
        touchTarget.setOnClickListener(v -> TOUCHES.incrementAndGet());
        root.addView(touchTarget);
        setContentView(root);
        buttons[4].requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= 0 && code < DOWN.length()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) DOWN.incrementAndGet(code);
            if (event.getAction() == KeyEvent.ACTION_UP) UP.incrementAndGet(code);
            events.setText(String.format(java.util.Locale.ROOT, "收到 %s / %d", KeyEvent.keyCodeToString(code), event.getAction()));
        }
        return super.dispatchKeyEvent(event);
    }
}
