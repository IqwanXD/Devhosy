package ru.iqwanoino.devhosy;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * File manager FTP.
 * - Navigasi memakai koneksi utama. Jika putus (pipe broken), otomatis disambung ulang.
 * - Transfer (zip, unduh, unggah) memakai koneksi sendiri di latar belakang dan punya progres,
 *   bisa dibatalkan, dan tercatat di Riwayat.
 */
public class ClientManager extends Activity {

    private static final int REQ_UPLOAD = 811, REQ_DOWNLOAD = 812, REQ_STORAGE = 813, REQ_NOTIF = 814;
    private static final String TITLE = "Remote files";
    private static final String CHANNEL = "devhosy_transfer";

    private interface QuickJob { void run(FtpClient ftp) throws Exception; }
    private interface TransferJob { void run(FtpClient ftp, TransferTask t) throws Exception; }

    /** Tampilan satu proses yang diperbarui berkala tanpa membangun ulang daftar. */
    private static final class ProcessView {
        TransferTask task;
        InoProgressBar bar;
        TextView pct, size;
    }

    private Typeface meTypeface;
    private LinearLayout topBar, listHost;
    private ScrollView scroll;
    private FrameLayout overlay;
    private LinearLayout fabItems;
    private View fabScrim;
    private boolean fabOpen;
    private TextView subText;
    private String currentPath = "/";
    private volatile String loadedPath = "/";
    private volatile List<FtpClient.Entry> loadedEntries = new ArrayList<>();
    private String pendingDownloadName = "";
    private long pendingDownloadSize = -1;
    private boolean busy;            // hanya untuk operasi cepat di koneksi utama
    private int activeTransfers;
    private boolean resumedOnce;
    private int doneSeq = 9000;      // id notifikasi hasil

    private Sheet openSheet;         // sheet yang sedang terbuka (untuk tombol back)
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout historyList;
    private String historySig = "";
    private final Map<Integer, ProcessView> processViews = new HashMap<>();
    private final Runnable historyTick = new Runnable() {
        @Override public void run() {
            if (historyList == null) return;
            refreshHistory();
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Prefs.init(this);
        Theme.load(this);
        try { meTypeface = Typeface.createFromAsset(getAssets(), "me.ttf"); }
        catch (Exception e) { meTypeface = null; }
        if (!ClientSession.connected()) {
            toast("Belum terhubung");
            finish();
            return;
        }
        createChannel();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
        buildUi();
        Ui.setupEdge(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Setelah app kembali dari background, muat ulang (sekaligus menyambung ulang bila perlu)
        if (resumedOnce && !busy && ClientSession.get() != null) refresh();
        resumedOnce = true;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(historyTick);
        super.onDestroy();
    }

    /** Back: tutup sheet yang terbuka, lalu tutup FAB, lalu naik folder; di root muncul sheet keluar. */
    @Override
    public void onBackPressed() {
        if (M3Dialog.dismissOpen()) return;
        if (openSheet != null) { openSheet.dismiss(); return; }
        if (fabOpen) { setFabOpen(false); return; }
        if (busy) { toast("Tunggu sebentar, sedang memuat"); return; }
        if ("/".equals(currentPath)) showExitSheet();
        else goParent();
    }

    /** Tampilkan sheet dan catat sebagai sheet yang terbuka. */
    private void present(final Sheet s, final Runnable extra) {
        openSheet = s;
        s.setOnDismiss(() -> {
            if (openSheet == s) openSheet = null;
            if (extra != null) extra.run();
        });
        s.show();
    }

    // ------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(Ui.iconButton(this, R.drawable.ic_back, Theme.ON, 48, this::onBackPressed),
                Ui.lp(Ui.dp(48), Ui.dp(48)));
        LinearLayout titles = vertical();
        TextView title = Ui.text(this, TITLE, 22, Theme.ON, true);
        title.setSingleLine(true);
        subText = Ui.text(this, currentPath, 13, Theme.ON_VAR, false);
        subText.setSingleLine(true);
        subText.setEllipsize(TextUtils.TruncateAt.START);
        titles.addView(title, Ui.lp(-2, -2));
        titles.addView(subText, Ui.lp(-2, -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Ui.dp(8);
        topBar.addView(titles, tl);
        topBar.addView(Ui.iconButton(this, R.drawable.ic_history, Theme.ON, 48, this::showHistorySheet),
                Ui.lp(Ui.dp(48), Ui.dp(48)));
        topBar.addView(Ui.iconButton(this, R.drawable.ic_refresh, Theme.PRIMARY, 48, this::refresh),
                Ui.lp(Ui.dp(48), Ui.dp(48)));
        root.addView(topBar, Ui.lp(-1, -2));

        FrameLayout content = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        listHost = vertical();
        scroll.addView(listHost, new FrameLayout.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // Scrim: menutup FAB saat diketuk di luar
        fabScrim = new View(this);
        fabScrim.setBackgroundColor(0x66000000);
        fabScrim.setVisibility(View.GONE);
        fabScrim.setClickable(true);
        fabScrim.setOnClickListener(v -> setFabOpen(false));
        content.addView(fabScrim, new FrameLayout.LayoutParams(-1, -1));

        // FAB toggle: ikon folder; saat dibuka muncul item Upload dan Buat (ukuran sama dengan FAB utama)
        overlay = new FrameLayout(this);
        LinearLayout column = vertical();
        column.setGravity(Gravity.END);
        fabItems = vertical();
        fabItems.setGravity(Gravity.END);
        fabItems.setVisibility(View.GONE);
        fabItems.addView(fabItem("Upload", R.drawable.ic_upload, () -> { setFabOpen(false); pickUpload(); }),
                Ui.lp(-2, -2));
        LinearLayout.LayoutParams second = Ui.lp(-2, -2);
        second.topMargin = Ui.dp(12);
        fabItems.addView(fabItem("Buat", R.drawable.ic_add, () -> { setFabOpen(false); showCreateSheet(); }), second);
        LinearLayout.LayoutParams itemsLp = Ui.lp(-2, -2);
        itemsLp.bottomMargin = Ui.dp(14);
        column.addView(fabItems, itemsLp);
        column.addView(fabMainButton(), Ui.lp(Ui.dp(56), Ui.dp(56)));
        overlay.addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        content.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.readInsets(ins);
            topBar.setPadding(Ui.dp(8), Ui.insetTop + Ui.dp(8), Ui.dp(12), Ui.dp(8));
            scroll.setPadding(Ui.dp(8), 0, Ui.dp(8), Ui.dp(120) + Ui.insetBottom);
            overlay.setPadding(0, 0, Ui.dp(16), Ui.dp(16) + Ui.insetBottom);
            return ins;
        });
        Fonts.apply(root);
        setContentView(root);
    }

    /** Tombol utama FAB: ikon folder. Ketuk untuk membuka atau menutup item. */
    private View fabMainButton() {
        LinearLayout fab = new LinearLayout(this);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(Ui.ripple(Theme.PRIM_CONT, 18));
        fab.setClickable(true);
        fab.addView(Ui.icon(this, R.drawable.ic_folder, Theme.PRIMARY), Ui.lp(Ui.dp(24), Ui.dp(24)));
        fab.setOnClickListener(v -> setFabOpen(!fabOpen));
        return fab;
    }

    /** Item FAB: label pill dan tombol berukuran sama dengan FAB utama (56dp). */
    private View fabItem(String label, int icon, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView pill = Ui.text(this, label, 14, Theme.ON, true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(16), Ui.dp(10));
        pill.setBackground(Ui.ripple(Theme.SURFACE_HIGH, 16));
        pill.setClickable(true);
        pill.setOnClickListener(v -> action.run());
        row.addView(pill, Ui.lp(-2, -2));

        LinearLayout fab = new LinearLayout(this);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(Ui.ripple(Theme.PRIM_CONT, 18));
        fab.setClickable(true);
        fab.setOnClickListener(v -> action.run());
        fab.addView(Ui.icon(this, icon, Theme.PRIMARY), Ui.lp(Ui.dp(24), Ui.dp(24)));
        LinearLayout.LayoutParams fp = Ui.lp(Ui.dp(56), Ui.dp(56));
        fp.leftMargin = Ui.dp(12);
        row.addView(fab, fp);
        return row;
    }

    /** Buka atau tutup item FAB dengan animasi singkat. */
    private void setFabOpen(boolean open) {
        if (fabOpen == open) return;
        fabOpen = open;
        fabScrim.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            fabItems.setVisibility(View.VISIBLE);
            fabItems.setAlpha(0f);
            fabItems.setTranslationY(Ui.dp(24));
            fabItems.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            fabItems.animate().alpha(0f).translationY(Ui.dp(24)).setDuration(150)
                    .withEndAction(() -> { if (!fabOpen) fabItems.setVisibility(View.GONE); })
                    .start();
        }
    }

    // ------------------------------------------------------------ operasi

    /**
     * Operasi cepat di koneksi utama. Satu sekaligus.
     * Jika gagal karena koneksi putus, sambung ulang lalu coba sekali lagi di folder yang sama.
     */
    private void runQuick(final QuickJob job, final Runnable after) {
        if (ClientSession.get() == null) { finish(); return; }
        if (busy) { toast("Tunggu sebentar, sedang memuat"); return; }
        busy = true;
        subText.setText("Memuat…");
        final String keepPath = currentPath;
        new Thread(() -> {
            String err = null;
            try {
                runWithReconnect(job, keepPath);
            } catch (Exception e) {
                err = msg(e);
            }
            final String fe = err;
            runOnUiThread(() -> {
                busy = false;
                updateSubtitle();
                if (fe != null) toast("Gagal: " + fe);
                if (after != null) after.run();
            });
        }, "Devhosy-Ftp-Quick").start();
    }

    /**
     * Jalankan job di koneksi utama. Jika gagal karena koneksi putus (mis. setelah app lama di background),
     * sambung ulang dengan jenis koneksi yang sama, kembali ke folder semula, lalu coba sekali lagi.
     * Pesan error dibedakan: putus dan gagal menyambung ulang tidak lagi tercampur.
     */
    private void runWithReconnect(QuickJob job, String keepPath) throws Exception {
        FtpClient c = ClientSession.get();
        if (c == null) throw new IOException("Belum terhubung");
        try {
            job.run(c);
        } catch (IOException ioe) {
            if (ClientSession.isAlive()) throw ioe;   // error dari server, bukan koneksi putus
            try {
                ClientSession.reconnect();
            } catch (IOException re) {
                throw new IOException("Koneksi terputus, gagal menyambung ulang: " + re.getMessage(), re);
            }
            FtpClient fresh = ClientSession.get();
            if (fresh == null) throw new IOException("Belum terhubung");
            fresh.cwd(keepPath);
            job.run(fresh);
        }
    }

    /**
     * Transfer di latar belakang dengan koneksi sendiri. Tidak memblokir navigasi.
     * dir = folder remote tempat transfer dimulai.
     */
    private void runTransfer(final String label, final String okMsg, final String dir,
                             final boolean refreshAfter, final TransferJob job) {
        final TransferTask task = new TransferTask(TransferHistory.nextId(), label);
        TransferHistory.add(task);
        activeTransfers++;
        updateSubtitle();
        final int nid = 4000 + task.id;
        notifyActive(nid, label + "…");
        toast(label + " berjalan di latar belakang");
        new Thread(() -> {
            FtpClient ftp = null;
            String err = null;
            try {
                ftp = ClientSession.openJobConnection();
                task.ftp = ftp;
                task.checkCancelled();          // dibatalkan sebelum sempat berjalan
                ftp.cwd(dir);
                job.run(ftp, task);
                task.detail = okMsg != null ? okMsg : label + " selesai";
                task.state = TransferTask.DONE;
            } catch (Exception e) {
                if (task.cancelRequested) {
                    task.detail = "Dibatalkan";
                    task.state = TransferTask.CANCELLED;
                } else {
                    err = msg(e);
                    task.detail = label + " gagal: " + err;
                    task.state = TransferTask.FAILED;
                }
            } finally {
                task.ftp = null;
                if (ftp != null) ftp.close();
            }
            final int st = task.state;
            final String fe = err;
            runOnUiThread(() -> {
                activeTransfers--;
                updateSubtitle();
                cancelNotif(nid);
                notifyDone(task.detail);
                if (st == TransferTask.DONE) toast(task.detail);
                else if (st == TransferTask.FAILED) toast(label + " gagal: " + fe);
                else toast("Dibatalkan");
                if (refreshAfter && st == TransferTask.DONE && !isFinishing()) refresh();
            });
        }, "Devhosy-Ftp-Transfer").start();
    }

    private void updateSubtitle() {
        if (subText == null) return;
        subText.setText(activeTransfers > 0
                ? currentPath + "  ·  " + activeTransfers + " transfer berjalan"
                : currentPath);
    }

    private void refresh() {
        runQuick(ftp -> {
            loadedPath = ftp.pwd();
            loadedEntries = ftp.list(null);
        }, () -> {
            currentPath = loadedPath;
            render();
        });
    }

    private void goParent() {
        runQuick(ftp -> ftp.cwd(".."), this::refresh);
    }

    private void enterDirectory(final String name) {
        runQuick(ftp -> ftp.cwd(name), this::refresh);
    }

    // ------------------------------------------------------------ riwayat

    private void showHistorySheet() {
        if (historyList != null) return;     // sudah terbuka
        final Sheet s = new Sheet(this, true);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Riwayat", 22, Theme.ON, true), new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Ui.iconButton(this, R.drawable.ic_close, Theme.ON_VAR, 40, s::dismiss),
                Ui.lp(Ui.dp(40), Ui.dp(40)));
        s.panel.addView(head, Ui.lp(-1, -2, 0, 0, 0, Ui.dp(8)));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        historyList = vertical();
        sv.addView(historyList, new FrameLayout.LayoutParams(-1, -2));
        s.panel.addView(sv, Ui.lp(-1, Ui.dp(420)));

        historySig = "";
        present(s, () -> {
            handler.removeCallbacks(historyTick);
            historyList = null;
            processViews.clear();
            historySig = "";
        });
        historyTick.run();
    }

    private void refreshHistory() {
        List<TransferTask> list = TransferHistory.snapshot();
        StringBuilder sig = new StringBuilder();
        for (TransferTask t : list) sig.append(t.id).append(':').append(t.state).append(';');
        if (!sig.toString().equals(historySig)) {
            historySig = sig.toString();
            rebuildHistory(list);
        } else {
            for (ProcessView pv : processViews.values()) updateProcess(pv);
        }
    }

    private void rebuildHistory(List<TransferTask> list) {
        historyList.removeAllViews();
        processViews.clear();
        if (list.isEmpty()) {
            TextView empty = Ui.text(this, "Belum ada proses", 14, Theme.ON_VAR, false);
            empty.setPadding(0, Ui.dp(12), 0, Ui.dp(12));
            historyList.addView(empty);
            applyFont(historyList);
            return;
        }
        boolean anyRunning = false;
        for (TransferTask t : list) if (t.isRunning()) anyRunning = true;
        if (anyRunning) historyList.addView(sectionLabel("Sedang berjalan"));
        for (TransferTask t : list) if (t.isRunning()) historyList.addView(processCard(t), cardParams());

        boolean anyDone = false;
        for (TransferTask t : list) {
            if (t.isRunning()) continue;
            if (!anyDone) { historyList.addView(sectionLabel("Riwayat")); anyDone = true; }
            historyList.addView(historyCard(t), cardParams());
        }
        applyFont(historyList);
    }

    /** Kartu proses berjalan: judul kiri, ikon close kanan atas, progress bar, persen dan ukuran. */
    private View processCard(final TransferTask t) {
        LinearLayout card = cardBase();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, t.title, 16, Theme.ON, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Ui.iconButton(this, R.drawable.ic_close, Theme.ON_VAR, 40, () -> confirmCancel(t)),
                Ui.lp(Ui.dp(40), Ui.dp(40)));
        card.addView(head, Ui.lp(-1, -2));

        ProcessView pv = new ProcessView();
        pv.task = t;
        pv.bar = new InoProgressBar(this);
        pv.bar.setTrackActiveColor(Theme.PRIMARY);
        pv.bar.setTrackInactiveColor((0x4D << 24) | (Theme.PRIMARY & 0x00FFFFFF));
        LinearLayout.LayoutParams bp = Ui.lp(-1, Ui.dp(12));
        bp.topMargin = Ui.dp(8);
        card.addView(pv.bar, bp);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pv.pct = Ui.text(this, "0%", 13, Theme.ON_VAR, false);
        pv.size = Ui.text(this, "", 13, Theme.ON_VAR, false);
        pv.size.setGravity(Gravity.END);
        row.addView(pv.pct, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(pv.size, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rp = Ui.lp(-1, -2);
        rp.topMargin = Ui.dp(6);
        card.addView(row, rp);

        processViews.put(t.id, pv);
        updateProcess(pv);
        return card;
    }

    private void updateProcess(ProcessView pv) {
        TransferTask t = pv.task;
        long done = t.done, total = t.total;
        if (total > 0) {
            pv.bar.setIndeterminate(false);
            pv.bar.setMax(100);
            int pct = (int) Math.min(100, done * 100 / total);
            pv.bar.setProgress(pct);
            pv.pct.setText(pct + "%");
            pv.size.setText(humanSize(done) + " / " + humanSize(total));
        } else {
            pv.bar.setIndeterminate(true);
            pv.pct.setText("Memproses…");
            pv.size.setText(humanSize(done));
        }
    }

    /** Kartu proses yang sudah selesai, dibatalkan, atau gagal. */
    private View historyCard(TransferTask t) {
        LinearLayout card = cardBase();
        TextView title = Ui.text(this, t.title, 15, Theme.ON, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title, Ui.lp(-1, -2));

        String st = t.state == TransferTask.DONE ? "Selesai"
                : t.state == TransferTask.CANCELLED ? "Dibatalkan" : "Gagal";
        int color = t.state == TransferTask.FAILED ? 0xffba1a1a
                : t.state == TransferTask.DONE ? Theme.PRIMARY : Theme.ON_VAR;
        TextView status = Ui.text(this, st + "  ·  " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(t.started)),
                12, color, true);
        LinearLayout.LayoutParams sp = Ui.lp(-1, -2);
        sp.topMargin = Ui.dp(4);
        card.addView(status, sp);

        if (!t.detail.isEmpty()) {
            TextView detail = Ui.text(this, t.detail, 13, Theme.ON_VAR, false);
            LinearLayout.LayoutParams dp = Ui.lp(-1, -2);
            dp.topMargin = Ui.dp(2);
            card.addView(detail, dp);
        }
        return card;
    }

    private void confirmCancel(final TransferTask t) {
        M3Dialog.with(this).title("Batalkan proses?").message(t.title)
                .negative("Tidak", null)
                .positive("Batalkan", t::cancel)
                .destructive(true)
                .show();
    }

    private LinearLayout cardBase() {
        LinearLayout card = vertical();
        card.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14));
        card.setBackground(Ui.rect(Theme.SURFACE_HIGH, 20));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = Ui.lp(-1, -2);
        p.bottomMargin = Ui.dp(10);
        return p;
    }

    private TextView sectionLabel(String text) {
        TextView t = Ui.text(this, text, 13, Theme.PRIMARY, true);
        t.setPadding(Ui.dp(2), Ui.dp(8), 0, Ui.dp(8));
        return t;
    }

    // ------------------------------------------------------------ notifikasi

    private NotificationManager nm() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Transfer FTP", NotificationManager.IMPORTANCE_LOW);
            nm().createNotificationChannel(ch);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    }

    private void notifyActive(int id, String text) {
        Notification n = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Devhosy")
                .setContentText(text)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build();
        nm().notify(id, n);
    }

    private void cancelNotif(int id) {
        nm().cancel(id);
    }

    private void notifyDone(String text) {
        Notification n = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Devhosy")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        nm().notify(doneSeq++, n);
    }

    // ------------------------------------------------------------ render

    private void render() {
        List<FtpClient.Entry> es = new ArrayList<>(loadedEntries);
        Collections.sort(es, (a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        updateSubtitle();
        listHost.removeAllViews();
        if (!"/".equals(currentPath)) listHost.addView(upRow());
        if (es.isEmpty()) {
            TextView empty = Ui.text(this, "Folder ini kosong", 13, Theme.ON_VAR, false);
            empty.setPadding(Ui.dp(16), Ui.dp(18), 0, Ui.dp(18));
            listHost.addView(empty);
        } else {
            addEntries(es);
        }
        applyFont(listHost);
    }

    private View upRow() {
        LinearLayout row = rowBase();
        row.addView(Ui.icon(this, R.drawable.ic_folder, Theme.PRIMARY), Ui.lp(Ui.dp(32), Ui.dp(32)));
        LinearLayout details = vertical();
        details.addView(Ui.text(this, "..", 15, Theme.ON, true));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
        dl.leftMargin = Ui.dp(16);
        row.addView(details, dl);
        row.setOnClickListener(v -> goParent());
        return row;
    }

    private void addEntries(List<FtpClient.Entry> es) {
        if (!Prefs.filesGrid()) {
            for (FtpClient.Entry e : es) listHost.addView(entryRow(e));
            return;
        }
        for (int i = 0; i < es.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2; j++) {
                if (j < es.size()) row.addView(entryTile(es.get(j)), new LinearLayout.LayoutParams(0, -2, 1f));
                else row.addView(new View(this), new LinearLayout.LayoutParams(0, -2, 1f));
            }
            LinearLayout.LayoutParams rp = Ui.lp(-1, -2);
            rp.bottomMargin = Ui.dp(8);
            listHost.addView(row, rp);
        }
    }

    private LinearLayout rowBase() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(4), Ui.dp(10));
        row.setBackground(Ui.ripple(0, 12));
        row.setClickable(true);
        return row;
    }

    private View entryRow(final FtpClient.Entry e) {
        LinearLayout row = rowBase();
        row.addView(Ui.icon(this, iconFor(e), e.directory ? Theme.PRIMARY : Theme.ON_VAR),
                Ui.lp(Ui.dp(32), Ui.dp(32)));
        LinearLayout details = vertical();
        TextView name = Ui.text(this, e.name, 15, Theme.ON, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(name);
        details.addView(Ui.text(this, e.directory ? "Folder" : humanSize(e.size), 12, Theme.ON_VAR, false));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
        dl.leftMargin = Ui.dp(16);
        row.addView(details, dl);
        row.addView(Ui.iconButton(this, R.drawable.ic_more_vert, Theme.ON_VAR, 40, () -> showFileActions(e)),
                Ui.lp(Ui.dp(40), Ui.dp(40)));
        row.setOnClickListener(v -> { if (e.directory) enterDirectory(e.name); else showFileActions(e); });
        row.setOnLongClickListener(v -> { showFileActions(e); return true; });
        return row;
    }

    private View entryTile(final FtpClient.Entry e) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(Ui.dp(8), Ui.dp(14), Ui.dp(8), Ui.dp(12));
        tile.setBackground(Ui.ripple(Theme.SURFACE_HIGH, 18));
        tile.setClickable(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMargins(Ui.dp(4), 0, Ui.dp(4), 0);
        tile.setLayoutParams(tp);
        tile.addView(Ui.icon(this, iconFor(e), e.directory ? Theme.PRIMARY : Theme.ON_VAR),
                Ui.lp(Ui.dp(36), Ui.dp(36)));
        TextView name = Ui.text(this, e.name, 13, Theme.ON, true);
        name.setMaxLines(2);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = Ui.lp(-1, -2);
        np.topMargin = Ui.dp(8);
        tile.addView(name, np);
        TextView size = Ui.text(this, e.directory ? "Folder" : humanSize(e.size), 11, Theme.ON_VAR, false);
        size.setGravity(Gravity.CENTER);
        tile.addView(size);
        tile.setOnClickListener(v -> { if (e.directory) enterDirectory(e.name); else showFileActions(e); });
        tile.setOnLongClickListener(v -> { showFileActions(e); return true; });
        return tile;
    }

    /** Ikon sesuai ekstensi file. */
    private int iconFor(FtpClient.Entry e) {
        if (e.directory) return R.drawable.ic_folder;
        String n = e.name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        switch (ext) {
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2": case "xz":
                return R.drawable.ic_zip;
            case "mp4": case "mkv": case "avi": case "mov": case "webm": case "3gp":
                return R.drawable.ic_video;
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic":
                return R.drawable.ic_image;
            case "mp3": case "wav": case "flac": case "m4a": case "ogg": case "aac":
                return R.drawable.ic_audio;
            case "apk":
                return R.drawable.ic_apk;
            case "txt": case "md": case "log": case "csv": case "json": case "xml": case "html": case "css":
            case "js": case "java": case "kt": case "py": case "gradle": case "properties": case "sh":
                return R.drawable.ic_text;
            default:
                return R.drawable.ic_file;
        }
    }

    // ------------------------------------------------------------ aksi file

    private void showFileActions(final FtpClient.Entry e) {
        final Sheet s = new Sheet(this, true);
        s.panel.addView(Ui.text(this, e.name, 18, Theme.ON, true), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(10)));
        if (e.directory) {
            addSheetAction(s, "Make zip", () -> { s.dismiss(); makeZip(e); });
            addSheetAction(s, "Download", () -> { s.dismiss(); downloadFolder(e); });
        } else {
            addSheetAction(s, "Download file", () -> { s.dismiss(); downloadFile(e); });
        }
        addSheetAction(s, "Rename", () -> { s.dismiss(); renameEntry(e); });
        addSheetAction(s, e.directory ? "Delete folder" : "Delete file", () -> { s.dismiss(); confirmDelete(e); });
        addSheetAction(s, "Cancel", s::dismiss);
        present(s, null);
    }

    private void addSheetAction(Sheet s, String text, Runnable action) {
        TextView b = Ui.text(this, text, 15, Theme.ON, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
        b.setBackground(Ui.ripple(0, 16));
        s.panel.addView(b, Ui.lp(-1, Ui.dp(48)));
        b.setOnClickListener(v -> action.run());
    }

    /** Sheet Buat: input nama (InoInputLayout), pilihan Folder atau File di bawahnya, lalu tombol Buat. */
    private void showCreateSheet() {
        final Sheet s = new Sheet(this, true);
        s.panel.addView(Ui.text(this, "Buat baru", 22, Theme.ON, true), Ui.lp(-2, -2, 0, 0, 0, Ui.dp(12)));

        final InoInputLayout input = new InoInputLayout(this);
        input.setLabelText("Nama");
        input.setIsMultiline(false);
        input.setText("");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(Theme.ON);
        input.setColors(Theme.PRIMARY, Theme.OUTLINE);
        input.setCornerRadius(14);
        input.setTextSize(15);
        input.setLabelSize(12);
        input.setVerticalMargins(10, 4);
        s.panel.addView(input, Ui.lp(-1, -2, 0, 0, 0, Ui.dp(12)));

        final boolean[] asFolder = {true};
        final TextView pFolder = pill("Folder", true);
        final TextView pFile = pill("File", false);
        pFolder.setOnClickListener(v -> { asFolder[0] = true; setPill(pFolder, true); setPill(pFile, false); });
        pFile.setOnClickListener(v -> { asFolder[0] = false; setPill(pFolder, false); setPill(pFile, true); });
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.addView(pFolder, Ui.lp(-2, Ui.dp(40), 0, 0, Ui.dp(8), 0));
        pills.addView(pFile, Ui.lp(-2, Ui.dp(40)));
        s.panel.addView(pills, Ui.lp(-2, -2, 0, 0, 0, Ui.dp(20)));

        s.panel.addView(Ui.button(this, "Buat", Theme.PRIMARY, Theme.ON_PRIMARY, () -> {
            String n = input.getText().trim();
            if (n.isEmpty()) { toast("Nama wajib diisi"); return; }
            s.dismiss();
            if (asFolder[0]) createFolder(n);
            else createFile(n);
        }), Ui.lp(-1, Ui.dp(52)));
        present(s, null);
    }

    private InoInputLayout nameInput(String label, String value) {
        InoInputLayout input = new InoInputLayout(this);
        input.setLabelText(label);
        input.setIsMultiline(false);
        input.setText(value == null ? "" : value);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(Theme.ON);
        input.setColors(Theme.PRIMARY, Theme.OUTLINE);
        input.setCornerRadius(14);
        input.setTextSize(15);
        input.setLabelSize(12);
        input.setVerticalMargins(10, 4);
        return input;
    }

    private TextView pill(String text, boolean selected) {
        TextView t = Ui.text(this, text, 14, selected ? Theme.ON_PRIMARY : Theme.ON, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        setPill(t, selected);
        return t;
    }

    private void setPill(TextView t, boolean selected) {
        t.setBackground(Ui.rect(selected ? Theme.PRIMARY : Theme.SURFACE_HIGH, 20));
        t.setTextColor(selected ? Theme.ON_PRIMARY : Theme.ON);
    }

    private void createFolder(final String n) {
        runQuick(ftp -> ftp.mkdir(n), () -> { toast("Folder dibuat"); refresh(); });
    }

    private void createFile(final String n) {
        runQuick(ftp -> ftp.upload(n, new ByteArrayInputStream(new byte[0])), () -> { toast("File dibuat"); refresh(); });
    }

    private void confirmDelete(final FtpClient.Entry e) {
        M3Dialog.with(this).title(e.directory ? "Hapus folder kosong?" : "Hapus file?").message(e.name)
                .negative("Batal", null)
                .positive("Hapus", () -> runQuick(ftp -> {
                    if (e.directory) ftp.rmdir(e.name);
                    else ftp.delete(e.name);
                }, () -> { toast(e.directory ? "Folder dihapus" : "File dihapus"); refresh(); }))
                .destructive(true)
                .show();
    }

    private void renameEntry(final FtpClient.Entry e) {
        final InoInputLayout input = nameInput("Nama baru", e.name);
        M3Dialog.with(this).title("Ubah nama").content(input)
                .negative("Batal", null)
                .positive("Simpan", () -> {
                    final String n = input.getText().trim();
                    if (n.isEmpty() || n.equals(e.name)) return;
                    runQuick(ftp -> ftp.rename(e.name, n), () -> { toast("Nama diubah menjadi " + n); refresh(); });
                })
                .show();
        input.getEditText().setSelectAllOnFocus(true);
        input.getEditText().requestFocus();
    }

    // ------------------------------------------------------------ zip & download (latar belakang)

    private File downloadsDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Devhosy");
    }

    private void prepareDownloads() throws IOException {
        File d = downloadsDir();
        if (!d.exists() && !d.mkdirs()) throw new IOException("Folder Download tidak bisa dibuat");
    }

    /** Total ukuran semua file di folder aktif (rekursif). Dipakai untuk progres zip dan unduh folder. */
    private long scanSize(FtpClient ftp) throws Exception {
        long sum = 0;
        for (FtpClient.Entry c : ftp.list(null)) {
            if (c.directory) {
                ftp.cwd(c.name);
                try { sum += scanSize(ftp); }
                finally { ftp.cwd(".."); }
            } else {
                sum += c.size;
            }
        }
        return sum;
    }

    /** Pack folder remote menjadi zip di Download/Devhosy. Zip dibuat di perangkat (FTP tidak punya perintah zip). */
    private void makeZip(final FtpClient.Entry e) {
        if (!ensureStorage()) return;
        final File out = new File(downloadsDir(), e.name + ".zip");
        runTransfer("Membuat zip " + e.name, "Zip tersimpan di Download/Devhosy/" + out.getName(),
                currentPath, false, (ftp, t) -> {
                    prepareDownloads();
                    boolean ok = false;
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
                        ftp.cwd(e.name);
                        t.total = scanSize(ftp);
                        zipDir(ftp, zos, e.name + "/", t);
                        ok = true;
                    } finally {
                        if (!ok) out.delete();
                    }
                });
    }

    private void zipDir(FtpClient ftp, ZipOutputStream zos, String prefix, TransferTask t) throws Exception {
        List<FtpClient.Entry> items = ftp.list(null);
        if (items.isEmpty()) {
            zos.putNextEntry(new ZipEntry(prefix));
            zos.closeEntry();
            return;
        }
        for (FtpClient.Entry c : items) {
            if (c.directory) {
                ftp.cwd(c.name);
                try { zipDir(ftp, zos, prefix + c.name + "/", t); }
                finally { ftp.cwd(".."); }
            } else {
                zos.putNextEntry(new ZipEntry(prefix + c.name));
                ftp.download(c.name, t.wrap(zos));
                zos.closeEntry();
            }
        }
    }

    /** Unduh folder remote apa adanya (struktur folder dipertahankan) ke Download/Devhosy. */
    private void downloadFolder(final FtpClient.Entry e) {
        if (!ensureStorage()) return;
        final File base = new File(downloadsDir(), e.name);
        runTransfer("Mengunduh " + e.name, "Folder tersimpan di Download/Devhosy/" + e.name,
                currentPath, false, (ftp, t) -> {
                    prepareDownloads();
                    ftp.cwd(e.name);
                    t.total = scanSize(ftp);
                    downloadDir(ftp, base, t);
                });
    }

    private void downloadDir(FtpClient ftp, File local, TransferTask t) throws Exception {
        if (!local.exists() && !local.mkdirs()) throw new IOException("Tidak bisa membuat " + local.getName());
        for (FtpClient.Entry c : ftp.list(null)) {
            File target = new File(local, c.name);
            if (c.directory) {
                ftp.cwd(c.name);
                try { downloadDir(ftp, target, t); }
                finally { ftp.cwd(".."); }
            } else {
                try (OutputStream os = new FileOutputStream(target)) {
                    ftp.download(c.name, t.wrap(os));
                }
            }
        }
    }

    /** File: langsung ke Download/Devhosy jika pengaturan aktif, jika tidak lewat pemilih lokasi. */
    private void downloadFile(final FtpClient.Entry e) {
        if (Prefs.downloadToSystem()) {
            if (!ensureStorage()) return;
            final File out = new File(downloadsDir(), e.name);
            runTransfer("Mengunduh " + e.name, "Tersimpan di Download/Devhosy/" + e.name,
                    currentPath, false, (ftp, t) -> {
                        prepareDownloads();
                        t.total = e.size;
                        try (OutputStream os = new FileOutputStream(out)) {
                            ftp.download(e.name, t.wrap(os));
                        } catch (Exception ex) {
                            out.delete();
                            throw ex;
                        }
                    });
        } else {
            pendingDownloadName = e.name;
            pendingDownloadSize = e.size;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, e.name);
            startActivityForResult(i, REQ_DOWNLOAD);
        }
    }

    private void pickUpload() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_UPLOAD);
        } catch (Exception ex) {
            toast("File picker tidak tersedia");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == REQ_UPLOAD) {
            final String name = displayName(u);
            final long size = sizeOf(u);
            runTransfer("Mengunggah " + name, "Upload selesai: " + name, currentPath, true, (ftp, t) -> {
                t.total = size;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    if (in == null) throw new IOException("Tidak dapat membaca file");
                    ftp.upload(name, t.wrap(in));
                }
            });
        } else if (req == REQ_DOWNLOAD) {
            final String name = pendingDownloadName;
            final long size = pendingDownloadSize;
            runTransfer("Mengunduh " + name, "Download selesai: " + name, currentPath, false, (ftp, t) -> {
                t.total = size;
                try (OutputStream os = getContentResolver().openOutputStream(u)) {
                    if (os == null) throw new IOException("Tidak dapat menulis file");
                    ftp.download(name, t.wrap(os));
                }
            });
        }
    }

    private String displayName(Uri uri) {
        String result = "upload.bin";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) result = c.getString(i);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    // ------------------------------------------------------------ izin penyimpanan

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureStorage() {
        if (hasStorageAccess()) return true;
        showStorageSheet();
        return false;
    }

    private void showStorageSheet() {
        final Sheet s = new Sheet(this, true);
        LinearLayout c = s.panel;
        c.addView(Ui.text(this, "Izinkan akses penyimpanan", 22, Theme.ON, true),
                Ui.lp(-2, -2, 0, 0, 0, Ui.dp(8)));
        String m = Build.VERSION.SDK_INT >= 30
                ? "Untuk menyimpan unduhan dan zip ke Download/Devhosy, aktifkan \"Izinkan akses ke semua file\" di layar berikutnya."
                : "Untuk menyimpan unduhan dan zip ke Download/Devhosy, izinkan akses penyimpanan.";
        c.addView(Ui.text(this, m, 15, Theme.ON_VAR, false), Ui.lp(-1, -2, 0, 0, 0, Ui.dp(20)));
        c.addView(Ui.button(this, "Izinkan", Theme.PRIMARY, Theme.ON_PRIMARY, () -> { s.dismiss(); requestStorage(); }),
                Ui.lp(-1, Ui.dp(52)));
        c.addView(Ui.button(this, "Nanti", 0, Theme.PRIMARY, s::dismiss),
                Ui.lp(-1, Ui.dp(52), 0, Ui.dp(8), 0, 0));
        present(s, null);
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("Buka pengaturan izin semua file secara manual");
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_STORAGE) {
            toast(hasStorageAccess() ? "Izin diberikan. Ulangi aksi tadi." : "Izin penyimpanan diperlukan.");
        }
    }

    // ------------------------------------------------------------ keluar

    /** Keluar memutus koneksi utama. Transfer yang berjalan tetap lanjut dan memberi notifikasi. */
    private void showExitSheet() {
        final Sheet s = new Sheet(this, true);
        s.panel.addView(Ui.text(this, "Keluar dari Client?", 22, Theme.ON, true),
                Ui.lp(-2, -2, 0, Ui.dp(8), 0, Ui.dp(8)));
        String info = activeTransfers > 0
                ? "Koneksi utama akan diputus. " + activeTransfers + " transfer tetap berjalan di latar belakang."
                : "Koneksi FTP akan diputus. Kamu bisa menyambung lagi dari halaman Client.";
        s.panel.addView(Ui.text(this, info, 14, Theme.ON_VAR, false), Ui.lp(-1, -2, 0, 0, 0, Ui.dp(16)));
        s.panel.addView(Ui.button(this, "Keluar", Theme.PRIMARY, Theme.ON_PRIMARY, () -> {
            s.dismiss();
            ClientSession.close();
            finish();
        }), Ui.lp(-1, Ui.dp(52)));
        s.panel.addView(Ui.button(this, "Batal", 0, Theme.PRIMARY, s::dismiss),
                Ui.lp(-1, Ui.dp(52), 0, Ui.dp(8), 0, 0));
        present(s, null);
    }

    // ------------------------------------------------------------ util

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private int dialogTheme() {
        return Theme.dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? "Gagal" : e.getMessage();
    }

    private String humanSize(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void toast(String text) {
        if (text == null || text.trim().isEmpty()) text = "Selesai";
        M3.toast(this, text);
    }

    private void applyFont(View v) {
        Fonts.apply(v);
    }
}
