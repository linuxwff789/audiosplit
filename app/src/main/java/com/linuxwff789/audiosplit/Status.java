package com.linuxwff789.audiosplit;

import java.io.File;
import java.io.FileWriter;

/** Best-effort status log readable from a root shell: /data/system/audiosplit.log */
public final class Status {
    private static final String PATH = "/data/system/audiosplit.log";
    private static final long MAX_BYTES = 256 * 1024;
    private static final java.util.ArrayDeque<String> MEM = new java.util.ArrayDeque<>();
    private static final int MEM_MAX = 200;

    private Status() {
    }

    /** Last lines of the log, for the UI status pane. */
    public static synchronized String tail(int lines) {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> all = new java.util.ArrayList<>(MEM);
        int from = Math.max(0, all.size() - lines);
        for (int i = from; i < all.size(); i++) {
            sb.append(all.get(i)).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("(no log yet)\n");
        }
        return sb.toString();
    }

    public static synchronized void append(String line) {
        MEM.addLast(line);
        while (MEM.size() > MEM_MAX) {
            MEM.removeFirst();
        }
        try {
            File f = new File(PATH);
            if (f.exists() && f.length() > MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(String.valueOf(System.currentTimeMillis()));
                w.write(' ');
                w.write(line);
                w.write('\n');
            }
        } catch (Throwable ignored) {
            // logging must never break routing
        }
    }
}
