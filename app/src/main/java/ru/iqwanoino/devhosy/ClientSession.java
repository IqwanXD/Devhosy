package ru.iqwanoino.devhosy;

import java.io.IOException;

/**
 * Koneksi FTP bersama. Koneksi utama dipakai untuk navigasi.
 * Transfer membuka koneksi sendiri lewat openJobConnection().
 * Jika koneksi utama putus (mis. "pipe broken" setelah app lama di background), reconnect() menyambung ulang.
 */
final class ClientSession {
    private static final Object LOCK = new Object();
    private static volatile FtpClient client;
    private static String host, user, pass;
    private static int port;

    private ClientSession() { }

    static void set(FtpClient c, String h, int p, String u, String pw) {
        synchronized (LOCK) {
            if (client != null && client != c) client.close();
            client = c;
            host = h;
            port = p;
            user = u;
            pass = pw;
        }
    }

    static FtpClient get() { return client; }

    static boolean connected() {
        FtpClient c = client;
        return c != null && c.isConnected();
    }

    /** True jika koneksi utama masih menjawab NOOP. */
    static boolean isAlive() {
        FtpClient c = client;
        return c != null && c.ping();
    }

    /** Sambung ulang dengan kredensial terakhir. Pemanggil harus berada di background thread. */
    static void reconnect() throws IOException {
        synchronized (LOCK) {
            if (host == null) throw new IOException("Belum terhubung");
            if (client != null) client.close();
            FtpClient c = new FtpClient();
            try {
                c.connect(host, port, user, pass);
            } catch (IOException e) {
                c.close();
                client = null;
                throw e;
            }
            client = c;
        }
    }

    /** Koneksi baru untuk satu transfer. Pemanggil wajib menutupnya. */
    static FtpClient openJobConnection() throws IOException {
        String h, u, pw;
        int p;
        synchronized (LOCK) {
            if (host == null) throw new IOException("Belum terhubung");
            h = host;
            p = port;
            u = user;
            pw = pass;
        }
        FtpClient c = new FtpClient();
        try {
            c.connect(h, p, u, pw);
            return c;
        } catch (IOException e) {
            c.close();
            throw e;
        }
    }

    static void close() {
        synchronized (LOCK) {
            if (client != null) client.close();
            client = null;
        }
    }
}
