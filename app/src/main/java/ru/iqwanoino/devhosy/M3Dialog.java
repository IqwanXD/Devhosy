package ru.iqwanoino.devhosy;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dialog Material 3 Expressive, pengganti AlertDialog: kartu membulat 28dp dengan tonal surface,
 * judul besar, tombol teks dan tombol pill. Naik otomatis saat keyboard tampil.
 * Pemakaian: M3Dialog.with(activity).title(..).message(..).negative(..).positive(..).show();
 */
final class M3Dialog {

    private static FrameLayout openRoot;

    private final Activity act;
    private final String title, message, positive, negative;
    private final View content;
    private final Runnable onPositive, onNegative;
    private final boolean destructive, cancelable;

    private M3Dialog(Builder b) {
        act = b.act; title = b.title; message = b.message; content = b.content;
        positive = b.positive; negative = b.negative; onPositive = b.onPositive; onNegative = b.onNegative;
        destructive = b.destructive; cancelable = b.cancelable;
    }

    /** Tutup dialog yang sedang terbuka (untuk tombol kembali). Mengembalikan false jika tidak ada. */
    static boolean dismissOpen() {
        if (openRoot == null) return false;
        FrameLayout r = openRoot;
        openRoot = null;
        ViewGroup p = (ViewGroup) r.getParent();
        if (p != null) p.removeView(r);
        return true;
    }

    static final class Builder {
        private final Activity act;
        private String title = "", message = "";
        private View content;
        private String positive, negative;
        private Runnable onPositive, onNegative;
        private boolean destructive, cancelable = true;

        Builder(Activity a) { act = a; }

        Builder title(String t) { title = t; return this; }
        Builder message(String m) { message = m; return this; }
        Builder content(View v) { content = v; return this; }
        Builder positive(String text, Runnable r) { positive = text; onPositive = r; return this; }
        Builder negative(String text, Runnable r) { negative = text; onNegative = r; return this; }
        Builder destructive(boolean d) { destructive = d; return this; }
        Builder cancelable(boolean c) { cancelable = c; return this; }

        void show() { new M3Dialog(this).show(); }
    }

    static Builder with(Activity a) { return new Builder(a); }

    private void show() {
        if (openRoot != null) dismissOpen();
        final FrameLayout root = new FrameLayout(act);
        root.setClickable(true);
        openRoot = root;

        final View scrim = new View(act);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        root.setOnClickListener(v -> { if (cancelable) close(root, onNegative); });

        final LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(M3.shape(Theme.SURFACE_HIGH, 28));
        card.setElevation(M3.dp(6));
        card.setClickable(true);   // menelan sentuhan agar tidak menutup dialog
        card.setPadding(M3.dp(24), M3.dp(24), M3.dp(24), M3.dp(16));

        if (!title.isEmpty()) {
            TextView t = M3.text(act, title, 24, Theme.ON, true);
            card.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        if (!message.isEmpty()) {
            TextView m = M3.text(act, message, 14, Theme.ON_VAR, false);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
            mp.topMargin = M3.dp(title.isEmpty() ? 0 : 8);
            card.addView(m, mp);
        }
        if (content != null) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = M3.dp(16);
            card.addView(content, cp);
        }

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = M3.dp(24);
        if (negative != null) {
            TextView n = pillButton(negative, 0, Theme.PRIMARY);
            n.setOnClickListener(v -> close(root, onNegative));
            buttons.addView(n, new LinearLayout.LayoutParams(-2, M3.dp(48)));
        }
        if (positive != null) {
            int bg = destructive ? (Theme.dark ? 0xFFFFB4AB : 0xFFBA1A1A) : Theme.PRIMARY;
            int fg = destructive ? (Theme.dark ? 0xFF690005 : 0xFFFFFFFF) : Theme.ON_PRIMARY;
            TextView p = pillButton(positive, bg, fg);
            p.setOnClickListener(v -> close(root, onPositive));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, M3.dp(48));
            pl.leftMargin = M3.dp(8);
            buttons.addView(p, pl);
        }
        card.addView(buttons, bp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        clp.leftMargin = clp.rightMargin = M3.dp(24);
        root.addView(card, clp);

        Fonts.apply(root);
        ViewGroup decor = act.findViewById(android.R.id.content);
        decor.addView(root, new FrameLayout.LayoutParams(-1, -1));

        card.setAlpha(0f);
        card.setScaleX(0.9f);
        card.setScaleY(0.9f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        scrim.animate().alpha(1f).setDuration(220).start();

        // Naikkan kartu di atas keyboard: pusat kartu dipindah setengah tinggi IME.
        Ime.watch(root, h -> {
            int visible = Math.max(0, h - Ui.insetBottom);
            card.setTranslationY(-visible / 2f);
        });
    }

    private void close(final FrameLayout root, final Runnable after) {
        if (openRoot == root) openRoot = null;
        root.setClickable(false);
        View card = root.getChildAt(1);
        card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(160).start();
        root.getChildAt(0).animate().alpha(0f).setDuration(160).withEndAction(() -> {
            ViewGroup p = (ViewGroup) root.getParent();
            if (p != null) p.removeView(root);
            if (after != null) after.run();
        }).start();
    }

    private TextView pillButton(String text, int bg, int fg) {
        TextView t = M3.text(act, text, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(M3.dp(20), 0, M3.dp(20), 0);
        t.setMinHeight(M3.dp(48));
        t.setBackground(Ui.ripple(bg, 24));
        t.setClickable(true);
        return t;
    }
}
