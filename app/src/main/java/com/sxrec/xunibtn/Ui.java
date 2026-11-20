package com.sxrec.xunibtn;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(16, 20, 18);
    public static final int SURFACE = Color.rgb(27, 34, 29);
    public static final int BORDER = Color.rgb(51, 62, 53);
    public static final int TEXT = Color.rgb(242, 246, 238);
    public static final int MUTED = Color.rgb(160, 177, 163);
    public static final int ACCENT = Color.rgb(213, 245, 129);
    public static final int INK = Color.rgb(29, 43, 22);

    private Ui() {}
    public static int dp(Context c, float n) {
        return Math.round(n * c.getResources().getDisplayMetrics().density);
    }
    public static GradientDrawable shape(Context c, int color, int radius, boolean border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        if (border) d.setStroke(dp(c, 1), BORDER);
        return d;
    }
    public static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setFontFeatureSettings("kern");
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return v;
    }
    public static Button button(Context c, String label, boolean primary) {
        Button v = new Button(c);
        v.setText(label);
        v.setTextSize(16);
        v.setAllCaps(false);
        v.setTextColor(primary ? INK : TEXT);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setMinHeight(dp(c, 52));
        v.setMinimumHeight(dp(c, 52));
        v.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x335F7942),
                shape(c, primary ? ACCENT : SURFACE, 16, !primary), null));
        return v;
    }
    public static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
}
