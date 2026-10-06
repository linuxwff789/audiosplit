package com.linuxwff789.audiosplit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration, read from a plain world-readable JSON file so that system_server can read it
 * without any IPC:
 *
 * <pre>
 * {
 *   "apps": [
 *     { "pkg": "com.ss.android.ugc.aweme", "device": "speaker" },
 *     { "pkg": "com.tencent.qqmusic",      "device": "a2dp"    }
 *   ]
 * }
 * </pre>
 *
 * Device values: "speaker", "a2dp", "wired", "usb".
 */
public final class Config {

    public static final String[] PATHS = {
            "/data/local/tmp/audiosplit.json",
            "/data/system/audiosplit.json",
            // the UI writes here; system_server reads the real path under /data/media
            "/data/media/0/Android/data/com.linuxwff789.audiosplit/files/audiosplit.json",
    };

    public static final class App {
        public final String pkg;
        public final String device;
        public final int uid;

        App(String pkg, String device, int uid) {
            this.pkg = pkg;
            this.device = device;
            this.uid = uid;
        }

        @Override
        public String toString() {
            return pkg + "->" + device + (uid > 0 ? "(uid " + uid + ")" : "");
        }
    }

    public final List<App> apps = new ArrayList<>();
    public String source = "";
    public long stamp;

    private Config() {
    }

    /** Parse a config JSON string. Returns null if it is unusable. */
    public static Config parse(String json, String source) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(json);
            Config c = new Config();
            c.source = source;
            c.stamp = System.currentTimeMillis();
            JSONArray arr = root.optJSONArray("apps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String pkg = o.optString("pkg", "");
                    if (pkg.isEmpty()) {
                        continue;
                    }
                    String device = o.optString("device", "speaker");
                    if (device.isEmpty()) {
                        device = "speaker";
                    }
                    c.apps.add(new App(pkg, device, o.optInt("uid", -1)));
                }
            }
            return c;
        } catch (Throwable t) {
            Log.e("config parse failed: " + source, t);
            return null;
        }
    }

    public static Config load() {
        for (String path : PATHS) {
            File f = new File(path);
            if (!f.canRead() || f.length() == 0) {
                continue;
            }
            try {
                byte[] buf = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0;
                    int r;
                    while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) {
                        off += r;
                    }
                }
                JSONObject root = new JSONObject(new String(buf, StandardCharsets.UTF_8));
                Config c = new Config();
                c.source = path;
                c.stamp = f.lastModified();
                JSONArray arr = root.optJSONArray("apps");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String pkg = o.optString("pkg", "");
                        if (pkg.isEmpty()) {
                            continue;
                        }
                        String device = o.optString("device", "speaker");
                        if (device.isEmpty()) {
                            device = "speaker";
                        }
                        c.apps.add(new App(pkg, device, o.optInt("uid", -1)));
                    }
                }
                return c;
            } catch (Throwable t) {
                Log.e("config parse failed: " + path, t);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "apps=" + apps + " (from " + source + ")";
    }
}
