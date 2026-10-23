package ru.iqwanoino.devhosy;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferensi aplikasi (SharedPreferences "music"). Nama fungsi mengikuti Pinpic supaya M3 bisa dipakai langsung. */
final class Prefs {
    private static SharedPreferences sp;

    private Prefs() { }

    static void init(Context c) {
        if (sp == null) sp = c.getApplicationContext().getSharedPreferences("music", Context.MODE_PRIVATE);
    }

    static SharedPreferences prefs(Context c) {
        init(c);
        return sp;
    }

    /** Tema gelap (default). false = terang. */
    static boolean darkTheme() { return sp == null || sp.getBoolean("dark", true); }

    static void setDarkTheme(boolean on) { if (sp != null) sp.edit().putBoolean("dark", on).apply(); }

    /** Warna dari wallpaper (Material You, Android 12+). Default aktif, sama seperti Pinpic. */
    static boolean dynamicColor() { return sp == null || sp.getBoolean("dynamic_color", true); }

    static void setDynamicColor(boolean on) { if (sp != null) sp.edit().putBoolean("dynamic_color", on).apply(); }

    /** Warna aksen pilihan sendiri (ARGB). 0 = palet bawaan. */
    static int appColor() { return sp == null ? 0 : sp.getInt("app_color", 0); }

    static void setAppColor(int argb) { if (sp != null) sp.edit().putInt("app_color", argb).apply(); }

    /** Mode grid di daftar lagu. */
    static boolean grid() { return sp != null && sp.getBoolean("grid", false); }

    static void setGrid(boolean on) { if (sp != null) sp.edit().putBoolean("grid", on).apply(); }

    /** Sembunyikan file audio kecil (di bawah Library.SMALL_BYTES). */
    static boolean hideSmall() { return sp != null && sp.getBoolean("hide_small", false); }

    static void setHideSmall(boolean on) { if (sp != null) sp.edit().putBoolean("hide_small", on).apply(); }

    /** Tampilkan tab Storage (daftar folder) di bar atas daftar lagu. */
    static boolean showStorageTab() { return sp != null && sp.getBoolean("show_storage", false); }

    static void setShowStorageTab(boolean on) { if (sp != null) sp.edit().putBoolean("show_storage", on).apply(); }

    /** Unduhan langsung ke folder Download/Devhosy (tanpa memilih lokasi). */
    static boolean downloadToSystem() { return sp == null || sp.getBoolean("dl_system", true); }

    static void setDownloadToSystem(boolean on) { if (sp != null) sp.edit().putBoolean("dl_system", on).apply(); }

    /** Daftar file ditampilkan sebagai grid (true) atau list (false). */
    static boolean filesGrid() { return sp != null && sp.getBoolean("files_grid", false); }

    static void setFilesGrid(boolean on) { if (sp != null) sp.edit().putBoolean("files_grid", on).apply(); }
}
