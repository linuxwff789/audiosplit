package com.linuxwff789.audiosplit;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** App side of the config channel: prefs + external file + direct broadcast to system_server. */
public final class AppConfigStore {

    private static final String PREFS = "audiosplit";
    private static final String KEY_JSON = "config_json";

    private AppConfigStore() {
    }

    public static String load(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null);
    }

    public static void save(Context ctx, String json) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, json).apply();
        writeFile(ctx, json);
    }

    /** system_server can read this path directly (it holds WRITE_MEDIA_STORAGE). */
    public static File externalFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "audiosplit.json");
    }

    private static void writeFile(Context ctx, String json) {
        try {
            File f = externalFile(ctx);
            if (f == null) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable t) {
            Log.e("writing config file failed", t);
        }
    }

    /** Implicit broadcast: the receiver lives inside system_server, so no setPackage here. */
    public static void push(Context ctx, String json) {
        Intent i = new Intent(Protocol.ACTION_CONFIG);
        i.putExtra(Protocol.EXTRA_JSON, json);
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        ctx.sendBroadcast(i);
    }

    /** Status ping (no payload) - system_server replies with ACTION_STATUS. */
    public static void ping(Context ctx) {
        Intent i = new Intent(Protocol.ACTION_CONFIG);
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        ctx.sendBroadcast(i);
    }
}
