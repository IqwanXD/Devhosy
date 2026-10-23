package ru.iqwanoino.devhosy;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Bottom sheet modal (izin, informasi). Handle bisa diseret untuk menutup. */
final class Sheet extends FrameLayout {

    final LinearLayout panel;
    private final Activity act;
    private final View scrim;
    private final boolean cancelable;
    private Runnable onDismiss;
    private boolean closing;
    private float downY;

    Sheet(Activity a, boolean cancelable) {
        super(a);
        this.act = a;
        this.cancelable = cancelable;
        setClickable(true);

        scrim = new View(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> {
            if (cancelable) dismiss();
        });
        addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.topRounded(Theme.SURFACE_HIGH, 28));
        panel.setPadding(Ui.dp(24), 0, Ui.dp(24), Ui.dp(24) + Ui.insetBottom);
        panel.setElevation(Ui.dp(12));
        panel.setClickable(true);

        LinearLayout handleArea = new LinearLayout(a);
        handleArea.setGravity(Gravity.CENTER);
        handleArea.setPadding(0, Ui.dp(12), 0, Ui.dp(16));
        View handle = new View(a);
        handle.setBackground(Ui.rect(Theme.OUTLINE, 2));
        handleArea.addView(handle, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(4)));
        handleArea.setOnTouchListener(this::onDrag);
        panel.addView(handleArea, new LinearLayout.LayoutParams(-1, -2));

        addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Saat keyboard tampil (mis. kolom Nama), panel naik sebesar tinggi keyboard.
        Ime.watch(this, h -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            int bottom = Math.max(0, h - Ui.insetBottom);
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom;
                panel.setLayoutParams(lp);
            }
        });
    }

    void setOnDismiss(Runnable r) {
        onDismiss = r;
    }

    void show() {
        Fonts.apply(panel);
        ViewGroup decor = act.findViewById(android.R.id.content);
        decor.addView(this, new FrameLayout.LayoutParams(-1, -1));
        // Inset bisa belum terbaca saat sheet dibuat (mis. dari onCreate): atur padding setelah layout pertama.
        panel.post(() -> panel.setPadding(Ui.dp(24), 0, Ui.dp(24), Ui.dp(24) + Ui.insetBottom));
        panel.setTranslationY(Ui.dp(900));
        panel.animate().translationY(0).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        scrim.animate().alpha(1f).setDuration(260).start();
    }

    void dismiss() {
        if (closing) return;
        closing = true;
        panel.animate().translationY(panel.getHeight() + Ui.dp(40)).setDuration(220)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    ViewGroup p = (ViewGroup) getParent();
                    if (p != null) p.removeView(this);
                    if (onDismiss != null) onDismiss.run();
                }).start();
        scrim.animate().alpha(0f).setDuration(200).start();
    }

    private boolean onDrag(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = Math.max(0f, e.getRawY() - downY);
                panel.setTranslationY(dy);
                scrim.setAlpha(Math.max(0f, 1f - dy / Math.max(1f, panel.getHeight())));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (cancelable && panel.getTranslationY() > panel.getHeight() * 0.25f) {
                    dismiss();
                } else {
                    panel.animate().translationY(0).setDuration(180).start();
                    scrim.animate().alpha(1f).setDuration(180).start();
                }
                return true;
            default:
                return false;
        }
    }
}
