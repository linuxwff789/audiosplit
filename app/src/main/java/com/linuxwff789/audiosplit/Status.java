package com.linuxwff789.audiosplit;

import java.io.File;
import java.io.FileWriter;

/** Best-effort status log readable from a root shell: /data/system/audiosplit.log */
public final class Status {
    private static final String PATH = "/data/system/audiosplit.log";
    private static final long MAX_BYTES = 256 * 1024;

    private Status() {
    }

    public static synchronized void append(String line) {
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
