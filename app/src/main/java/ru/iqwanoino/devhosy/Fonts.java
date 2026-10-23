package ru.iqwanoino.devhosy;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Satu sumber font untuk seluruh app: me.ttf dari assets. Semua teks (Ui, M3, input, toast,
 * sheet, dialog, dan setiap layar) memakai Fonts.get() atau Fonts.apply() agar tampilannya seragam.
 */
final class Fonts {

    private static Typeface base;

    private Fonts() { }

    /** Dipanggil sekali dari Devhosy.onCreate(). */
    static synchronized void init(Context c) {
        if (base != null) return;
        try {
            base = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "me.ttf");
        } catch (Exception e) {
            base = Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    /** Typeface me.ttf dengan gaya NORMAL atau BOLD. */
    static Typeface get(int style) {
        if (base == null) return Typeface.create("sans-serif", style);
        return Typeface.create(base, style);
    }

    /** Terapkan font ke seluruh TextView (termasuk EditText dan Button) di bawah view ini, mempertahankan bold. */
    static void apply(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int style = t.getTypeface() != null ? t.getTypeface().getStyle() : Typeface.NORMAL;
            t.setTypeface(get(style));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
