package ru.iqwanoino.devhosy;

import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Memantau tinggi keyboard (IME) untuk view yang menutupi layar penuh.
 * Android 11+ memakai insets IME; versi lama memakai tinggi area layar yang terlihat.
 */
final class Ime {

    interface Listener { void onHeight(int px); }

    private Ime() { }

    static void watch(final View root, final Listener listener) {
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                listener.onHeight(ins.getInsets(WindowInsets.Type.ime()).bottom);
                return ins;
            });
        } else {
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect r = new Rect();
                root.getWindowVisibleDisplayFrame(r);
                int kb = root.getRootView().getHeight() - r.bottom;
                listener.onHeight(kb > Ui.dp(120) ? kb : 0);
            });
        }
    }
}
