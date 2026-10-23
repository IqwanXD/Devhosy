package ru.iqwanoino.devhosy;

import java.util.ArrayList;
import java.util.List;

/** Riwayat proses transfer selama proses aplikasi berjalan. Yang masih berjalan tidak pernah dihapus. */
final class TransferHistory {
    private static final int MAX = 60;
    private static final List<TransferTask> items = new ArrayList<>();
    private static int seq = 1;

    private TransferHistory() { }

    static synchronized int nextId() { return seq++; }

    static synchronized void add(TransferTask t) {
        items.add(0, t);
        for (int i = items.size() - 1; i >= 0 && items.size() > MAX; i--) {
            if (!items.get(i).isRunning()) items.remove(i);
        }
    }

    static synchronized List<TransferTask> snapshot() {
        return new ArrayList<>(items);
    }
}
