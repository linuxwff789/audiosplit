package com.linuxwff789.audiosplit;

/**
 * Logging that works in both worlds: inside system_server (XposedBridge + /data/system log)
 * and inside this app's own process, where de.robv.android.xposed may not even be on the
 * classpath - every bridge call is therefore optional and failure-proof.
 */
public final class Log {
    public static final String TAG = "AudioSplit";

    private Log() {
    }

    public static void i(String msg) {
        bridge(TAG + ": " + msg);
        Status.append(msg);
    }

    public static void e(String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg);
        if (t != null) {
            sb.append(" :: ").append(t);
        }
        bridge(TAG + " [E]: " + sb);
        Status.append("[E] " + sb);
        if (t != null) {
            bridge(String.valueOf(t));
        }
    }

    private static void bridge(String line) {
        try {
            de.robv.android.xposed.XposedBridge.log(line);
        } catch (Throwable ignored) {
            // not running inside a hooked process
        }
    }
}
