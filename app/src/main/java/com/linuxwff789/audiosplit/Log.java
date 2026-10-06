package com.linuxwff789.audiosplit;

import de.robv.android.xposed.XposedBridge;

public final class Log {
    public static final String TAG = "AudioSplit";

    private Log() {
    }

    public static void i(String msg) {
        XposedBridge.log(TAG + ": " + msg);
        Status.append(msg);
    }

    public static void e(String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg);
        if (t != null) {
            sb.append(" :: ").append(t);
        }
        XposedBridge.log(TAG + " [E]: " + sb);
        Status.append("[E] " + sb);
        if (t != null) {
            XposedBridge.log(t);
        }
    }
}
