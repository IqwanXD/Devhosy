package ru.iqwanoino.devhosy;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Satu proses transfer (zip, unduh, unggah) beserta progres dan status batalnya. */
final class TransferTask {
    static final int RUNNING = 0, DONE = 1, FAILED = 2, CANCELLED = 3;

    final int id;
    final String title;
    final long started = System.currentTimeMillis();
    volatile int state = RUNNING;
    volatile long done;
    volatile long total = -1;          // -1 = belum diketahui
    volatile String detail = "";
    volatile boolean cancelRequested;
    volatile FtpClient ftp;

    TransferTask(int id, String title) {
        this.id = id;
        this.title = title;
    }

    boolean isRunning() { return state == RUNNING; }

    void checkCancelled() throws IOException {
        if (cancelRequested) throw new IOException("Dibatalkan");
    }

    /** Batalkan: tandai, lalu tutup koneksi agar operasi yang sedang menunggu langsung gagal. */
    void cancel() {
        cancelRequested = true;
        FtpClient f = ftp;
        if (f != null) f.close();
    }

    /** Hitung byte yang keluar dan cek pembatalan setiap kali menulis. */
    OutputStream wrap(final OutputStream os) {
        return new FilterOutputStream(os) {
            @Override public void write(int b) throws IOException {
                checkCancelled();
                out.write(b);
                done++;
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                checkCancelled();
                out.write(b, off, len);
                done += len;
            }
        };
    }

    /** Hitung byte yang masuk dan cek pembatalan setiap kali membaca. */
    InputStream wrap(final InputStream is) {
        return new FilterInputStream(is) {
            @Override public int read() throws IOException {
                checkCancelled();
                int r = in.read();
                if (r >= 0) done++;
                return r;
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                checkCancelled();
                int n = in.read(b, off, len);
                if (n > 0) done += n;
                return n;
            }
        };
    }
}
