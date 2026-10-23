package ru.iqwanoino.devhosy;

import android.app.Activity;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Helper pembuat view tanpa XML layout, plus pengaturan edge-to-edge. */
final class Ui {
    static float density = 1f;
    /** Inset sistem terakhir (status bar atas, navigasi bawah), diisi dari WindowInsets. */
    static int insetTop, insetBottom;

    private Ui() { }

    static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable rect(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static GradientDrawable topRounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float r = dp(radiusDp);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return g;
    }

    /** Ripple. bg = 0 berarti latar transparan. */
    static RippleDrawable ripple(int bg, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE),
                bg == 0 ? null : rect(bg, radiusDp),
                rect(0xFFFFFFFF, radiusDp));
    }

    static void round(View v, final float radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.get(bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    /** Drawable dari res/drawable, diwarnai lewat color filter (copy supaya tidak saling mengubah). */
    static Drawable tinted(Context c, int res, int color) {
        Drawable d = c.getResources().getDrawable(res).mutate();
        d.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return d;
    }

    static ImageView icon(Context c, int res, int color) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(tinted(c, res, color));
        return iv;
    }

    /** Tombol ikon dengan ripple. sizeDp = area sentuh. */
    static ImageView iconButton(Context c, int res, int color, int sizeDp, Runnable action) {
        ImageView iv = new ImageView(c);
        int pad = dp(sizeDp * 0.24f);
        iv.setPadding(pad, pad, pad, pad);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(tinted(c, res, color));
        iv.setBackground(ripple(0, sizeDp / 2f));
        iv.setClickable(true);
        if (action != null) iv.setOnClickListener(v -> action.run());
        return iv;
    }

    static TextView button(Context c, String label, int bg, int fg, Runnable action) {
        TextView t = text(c, label, 16, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(ripple(bg, 26));
        t.setClickable(true);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static String time(int ms) {
        int s = Math.max(0, ms) / 1000;
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Gambar sampai ke belakang status bar dan navigasi. Ikon bar mengikuti tema (M3.styleBars). */
    static void setupEdge(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(0x00000000);
        // Transparan: konten (drawer & pemutar) tergambar sampai ke belakang navigation bar.
        w.setNavigationBarColor(0x00000000);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Theme.BG));
        cutoutShortEdges(w);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        M3.styleBars(w);
    }

    /** Efek fade di tepi atas dan bawah list/grid saat di-scroll. */
    static void fade(android.widget.AbsListView v) {
        v.setVerticalFadingEdgeEnabled(true);
        v.setFadingEdgeLength(dp(56));
    }

    /** Warna navigation bar: sama dengan latar pemutar agar mini bar menyatu dengan bar bawah. */
    static void setNav(Activity a, int color) {
        a.getWindow().setNavigationBarColor(color);
    }

    /** Konten boleh tampil di area poni/lubang kamera, sama seperti Pinpic. */
    static void cutoutShortEdges(Window w) {
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }

    /** Baca inset sistem (dipanggil dari listener insets). */
    static void readInsets(WindowInsets ins) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = ins.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            insetTop = i.top;
            insetBottom = i.bottom;
        } else {
            insetTop = ins.getSystemWindowInsetTop();
            insetBottom = ins.getSystemWindowInsetBottom();
        }
    }
}
