package ru.iqwanoino.devhosy;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pengaturan, mengikuti SettingsActivity di Music: judul besar yang mengecil saat di-scroll,
 * kartu dengan MaterialSwitch, slider port, dan pemilih warna aksen. Tema dibangun ulang di tempat.
 */
public class SettingsActivity extends Activity {

    private interface Toggle { void onToggle(boolean on); }
    private interface IntSink { void set(int v); }

    private static final float END_SCALE = 18f / 34f;   // judul 34sp mengecil jadi 18sp
    private static final int PORT_MIN = 50000, PORT_MAX = 60000;
    private static final String KEY_SCROLL = "scrollY";
    private static final long THEME_STEP_MS = 120;

    private SharedPreferences p;
    private Typeface meTypeface;

    private int expandedH, collapsedH, maxScroll, lastY;
    private int topInset, bottomInset;
    private FrameLayout rootFrame, header;
    private ImageView backBtn;
    private TextView title;
    private LinearLayout col;
    private long lastThemeAt;

    private final Runnable rebuildRunnable = this::rebuildTheme;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Prefs.init(this);
        Theme.load(this);
        p = getSharedPreferences("devhosy", MODE_PRIVATE);
        try { meTypeface = Typeface.createFromAsset(getAssets(), "me.ttf"); }
        catch (Exception e) { meTypeface = null; }
        int restoreY = state == null ? 0 : state.getInt(KEY_SCROLL, 0);
        buildScreen(restoreY, null);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(KEY_SCROLL, lastY);
    }

    /**
     * Bangun seluruh isi layar. Dipakai saat dibuka dan saat tema/warna berubah.
     * Scroll dipulihkan dan tampilan lama dipudarkan (snapshot).
     */
    private void buildScreen(final int restoreY, Bitmap snapshot) {
        final int viewportH = rootFrame != null ? rootFrame.getHeight() : 0;
        computeHeights();

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(M3.dp(16), expandedH, M3.dp(16), bottomInset + M3.dp(24));
        if (viewportH > 0) col.setMinimumHeight(viewportH + maxScroll);

        addBlock(section("Tampilan"));
        addBlock(displayCard());

        addBlock(section("Unduhan"));
        addBlock(card(
                switchRow("Simpan ke folder Download", "Tersimpan di Download/Devhosy", "Pilih lokasi setiap unduh",
                        Prefs.downloadToSystem(), on -> Prefs.setDownloadToSystem(on))));

        addBlock(section("Jaringan"));
        int min = p.getInt("pasv_min", 50000), max = p.getInt("pasv_max", 50100);
        addBlock(card(
                sliderRow("Port data awal", min, v -> {
                    int hi = p.getInt("pasv_max", 50100);
                    p.edit().putInt("pasv_min", v).putInt("pasv_max", Math.max(v, hi)).apply();
                }),
                sliderRow("Port data akhir", max, v -> {
                    int lo = p.getInt("pasv_min", 50000);
                    p.edit().putInt("pasv_max", v).putInt("pasv_min", Math.min(v, lo)).apply();
                })));
        TextView note = M3.text(this,
                "Atur rentang port data untuk mode pasif. Izinkan port ini di firewall router jika perlu.",
                13, Theme.ON_VAR, false);
        note.setPadding(M3.dp(4), M3.dp(8), M3.dp(4), 0);
        addBlock(note);

        addBlock(section("Tentang"));
        addBlock(card(
                infoRow("Versi", versionName()),
                infoRow("Keamanan", "FTP tidak terenkripsi")));

        final ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(col);
        sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int min2 = (b - t) + maxScroll;
            if (col.getMinimumHeight() != min2) col.setMinimumHeight(min2);
        });
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> collapse(y));

        header = buildHeader();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(header, new FrameLayout.LayoutParams(-1, expandedH, Gravity.TOP));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.readInsets(ins);
            topInset = Ui.insetTop;
            bottomInset = Ui.insetBottom;
            applyInsets();
            return ins;
        });

        rootFrame = root;
        setContentView(root);
        Ui.setupEdge(this);
        applyFont(root);
        collapse(restoreY);

        if (restoreY > 0) {
            sv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    sv.getViewTreeObserver().removeOnPreDrawListener(this);
                    sv.scrollTo(0, restoreY);
                    collapse(restoreY);
                    return true;
                }
            });
        }

        if (snapshot != null) fadeOutSnapshot(root, snapshot);
    }

    private void fadeOutSnapshot(final FrameLayout host, final Bitmap bmp) {
        final ImageView cover = new ImageView(this);
        cover.setImageBitmap(bmp);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        host.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        cover.animate().alpha(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(() -> {
                    host.removeView(cover);
                    cover.setImageDrawable(null);
                    bmp.recycle();
                }).start();
    }

    /** Hentikan ripple yang masih berjalan sebelum snapshot diambil. */
    private void endRipples(View v) {
        if (v == null) return;
        Drawable bg = v.getBackground();
        if (bg instanceof RippleDrawable) {
            v.setPressed(false);
            bg.setState(new int[0]);
            bg.jumpToCurrentState();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) endRipples(g.getChildAt(i));
        }
    }

    private Bitmap snapshotOf(View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        try {
            Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            return b;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void computeHeights() {
        expandedH = topInset + M3.dp(176);
        collapsedH = topInset + M3.dp(56);
        maxScroll = expandedH - collapsedH;
    }

    private void applyInsets() {
        computeHeights();
        if (col != null) col.setPadding(M3.dp(16), expandedH, M3.dp(16), bottomInset + M3.dp(24));
        if (backBtn != null) {
            FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) backBtn.getLayoutParams();
            bl.topMargin = topInset + (M3.dp(56) - M3.dp(48)) / 2;
            backBtn.setLayoutParams(bl);
        }
        if (header != null) collapse(lastY);
    }

    /** Header overlay: tombol kembali dan judul yang dianimasikan saat scroll. */
    private FrameLayout buildHeader() {
        FrameLayout h = new FrameLayout(this);
        h.setBackgroundColor(Theme.BG);

        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageDrawable(Ui.tinted(this, R.drawable.ic_back, Theme.ON));
        RippleDrawable br = new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), null,
                new ColorDrawable(Color.WHITE));
        br.setRadius(M3.dp(20));
        back.setBackground(br);
        back.setClickable(true);
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(M3.dp(48), M3.dp(48),
                Gravity.TOP | Gravity.START);
        bl.topMargin = (collapsedH - M3.dp(48)) / 2;
        bl.leftMargin = M3.dp(4);
        h.addView(back, bl);
        backBtn = back;

        title = M3.text(this, "Pengaturan", 34, Theme.ON, true);
        title.setLetterSpacing(-0.02f);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tl.leftMargin = M3.dp(16);
        h.addView(title, tl);

        h.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> collapse(lastY));
        return h;
    }

    /** Dipanggil setiap scroll. scrollY = jarak konten yang sudah di-scroll. */
    private void collapse(int scrollY) {
        lastY = scrollY;
        float prog = Math.max(0f, Math.min(1f, scrollY / (float) maxScroll));

        int h = Math.max(collapsedH, expandedH - scrollY);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) header.getLayoutParams();
        if (lp.height != h) {
            lp.height = h;
            header.setLayoutParams(lp);
        }

        float th = title.getHeight();
        if (th == 0) {
            title.measure(0, 0);
            th = title.getMeasuredHeight();
        }
        float s = 1f + (END_SCALE - 1f) * prog;
        float topExpanded = expandedH - M3.dp(16) - th;
        float topCollapsed = topInset + (M3.dp(56) - th * END_SCALE) / 2f;

        title.setPivotX(0f);
        title.setPivotY(0f);
        title.setScaleX(s);
        title.setScaleY(s);
        title.setTranslationX(M3.dp(48) * prog);
        title.setTranslationY(topExpanded + (topCollapsed - topExpanded) * prog);
    }

    // ------------------------------------------------------------ bagian & kartu

    private TextView section(String label) {
        TextView t = M3.text(this, label, 14, Theme.PRIMARY, true);
        t.setPadding(M3.dp(8), M3.dp(20), M3.dp(8), M3.dp(4));
        return t;
    }

    private void addBlock(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = M3.dp(8);
        col.addView(v, lp);
    }

    /**
     * Kartu berisi baris-baris. Kartu di-clip ke outline-nya sendiri, sehingga ripple setiap baris
     * ikut melengkung mengikuti sudut kartu dan tidak keluar dari kartu.
     */
    private LinearLayout card(View... rows) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rect(Theme.SURFACE, 24));
        c.setClipToOutline(true);
        for (int i = 0; i < rows.length; i++) {
            c.addView(rows[i], new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < rows.length - 1) c.addView(divider(), dividerParams());
        }
        return c;
    }

    // ------------------------------------------------------------ tampilan

    /** Kartu Tampilan: tema, warna dinamis, warna aksen, dan tampilan file. */
    private LinearLayout displayCard() {
        LinearLayout themeRow = switchRow("Tema gelap", "Palet gelap aktif", "Palet terang aktif",
                Prefs.darkTheme(), on -> {
                    Prefs.setDarkTheme(on);
                    applyTheme();
                });
        LinearLayout dynRow = switchRow("Warna dinamis", "Ikuti warna wallpaper", "Palet bawaan Devhosy",
                Prefs.dynamicColor(), on -> {
                    Prefs.setDynamicColor(on);
                    applyTheme();
                });
        LinearLayout accent = accentRow();
        LinearLayout filesRow = switchRow("Tampilan file", "Grid (kotak ikon)", "List (baris)",
                Prefs.filesGrid(), on -> Prefs.setFilesGrid(on));

        return card(themeRow, dynRow, accent, filesRow);
    }

    /** Baris dengan MaterialSwitch. Saklar hanya tampilan; baris yang menangani ketukan. */
    private LinearLayout switchRow(String label, final String onText, final String offText, boolean on,
                                   final Toggle toggle) {
        final LinearLayout row = baseRow();
        final TextView sub = M3.text(this, on ? onText : offText, 13, Theme.ON_VAR, false);
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setEnabled(false);
        sw.setFocusable(false);
        sw.setActiveColor(Theme.PRIMARY);
        sw.setInactiveColor(Color.TRANSPARENT);
        sw.setStrokeActiveColor(Theme.PRIMARY);
        sw.setStrokeInactiveColor(Theme.OUTLINE);
        sw.setThumbActiveColor(Theme.ON_PRIMARY);
        sw.setThumbInactiveColor(Theme.OUTLINE);
        sw.setCheckedImmediate(on);

        row.addView(textColumn(M3.text(this, label, 16, Theme.ON, false), sub),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw, new LinearLayout.LayoutParams(M3.dp(54), M3.dp(42)));

        row.setOnClickListener(v -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next);
            sub.setText(next ? onText : offText);
            toggle.onToggle(next);
        });
        return row;
    }

    /** Baris "App color": ketuk untuk membuka pemilih warna. */
    private LinearLayout accentRow() {
        final LinearLayout row = baseRow();
        final int saved = Prefs.appColor();
        final int current = saved != 0 ? saved : Theme.PRIMARY;

        TextView sub = M3.text(this, saved == 0 ? "Ungu bawaan" : hex(saved), 13, Theme.ON_VAR, false);
        row.addView(textColumn(M3.text(this, "App color", 16, Theme.ON, false), sub),
                new LinearLayout.LayoutParams(0, -2, 1f));

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(current);
        View dotView = new View(this);
        dotView.setBackground(dot);
        row.addView(dotView, new LinearLayout.LayoutParams(M3.dp(28), M3.dp(28)));

        row.setOnClickListener(v -> ColorPicker.show(this, current, color -> {
            Prefs.setAppColor(color);
            applyTheme();
        }));
        return row;
    }

    /** Slider port (SliderRange) dengan label dan nilai di atasnya. Rentang 50000-60000. */
    private LinearLayout sliderRow(String label, int value, final IntSink sink) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(M3.dp(16), M3.dp(14), M3.dp(16), M3.dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(M3.text(this, label, 16, Theme.ON, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView val = M3.text(this, String.valueOf(value), 14, Theme.PRIMARY, true);
        head.addView(val, new LinearLayout.LayoutParams(-2, -2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        SliderRange slider = new SliderRange(this);
        slider.setTrackActiveColor(Theme.PRIMARY);
        slider.setTrackInactiveColor((0x4D << 24) | (Theme.PRIMARY & 0x00FFFFFF));
        slider.setThumbColor(Theme.PRIMARY);
        slider.setDotActiveColor(Theme.ON_PRIMARY);
        slider.setDotInactiveColor(Theme.OUTLINE);
        slider.setMax(PORT_MAX - PORT_MIN);
        slider.setProgress(value - PORT_MIN);
        slider.setOnSliderChangeListener(new SliderRange.SliderListener() {
            @Override
            public void onValueChanged(SliderRange s, int v, boolean fromUser) {
                int real = v + PORT_MIN;
                val.setText(String.valueOf(real));
                if (fromUser) sink.set(real);
            }

            @Override public void onStartTracking(SliderRange s) { }

            @Override public void onStopTracking(SliderRange s) { }
        });
        box.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /** Baris informasi: tidak bisa diketuk, jadi tanpa ripple. */
    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(M3.dp(16), M3.dp(14), M3.dp(16), M3.dp(14));
        row.addView(M3.text(this, label, 16, Theme.ON, false), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(M3.text(this, value, 13, Theme.ON_VAR, false), new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /**
     * Dasar setiap baris yang bisa diketuk. Ripple berada di baris itu sendiri, memenuhi seluruh
     * lebar dan tinggi baris, dengan mask putih polos seperti di Music.
     */
    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(M3.dp(16), M3.dp(14), M3.dp(16), M3.dp(14));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), null,
                new ColorDrawable(Color.WHITE)));
        row.setClickable(true);
        return row;
    }

    private LinearLayout textColumn(TextView top, TextView bottom) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(top);
        texts.addView(bottom);
        return texts;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(M3.alpha(Theme.OUTLINE, 0.25f));
        return v;
    }

    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, M3.dp(1)));
        dl.leftMargin = M3.dp(16);
        dl.rightMargin = M3.dp(16);
        return dl;
    }

    /** Tema dibangun ulang setelah jeda singkat supaya saklar yang diketuk selesai bergeser. */
    private void applyTheme() {
        rootFrame.removeCallbacks(rebuildRunnable);
        long wait = Math.max(0, lastThemeAt + THEME_STEP_MS - SystemClock.uptimeMillis());
        rootFrame.postDelayed(rebuildRunnable, wait);
    }

    private void rebuildTheme() {
        if (isFinishing() || isDestroyed()) return;
        lastThemeAt = SystemClock.uptimeMillis();
        final int y = lastY;
        endRipples(rootFrame);
        final Bitmap snap = snapshotOf(rootFrame);
        Theme.load(this);   // palet dihitung ulang dari Prefs terbaru
        buildScreen(y, snap);
    }

    private static String hex(int argb) {
        return String.format("#%06X", 0xFFFFFF & argb);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "-";
        }
    }

    private void applyFont(View v) {
        Fonts.apply(v);
    }


}
