package com.linuxwff789.audiosplit;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.media.AudioAttributes;
import android.provider.Settings;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs inside the apps the user pinned to the phone speaker.
 *
 * Routing per app is impossible through the policy-mix path on this ROM (both mixes end up on the
 * same output), but the framework already contains a strategy that is hard-wired to the speaker:
 * STRATEGY_TRANSMITTED_THROUGH_SPEAKER, selected for attributes { usage=UNKNOWN, flags=0x8 }
 * (AUDIO_FLAG_BEACON) - visible in `dumpsys media.audio_policy` as id 24 with
 * "Selected Device: {AUDIO_DEVICE_OUT_SPEAKER}".
 *
 * So we rewrite every AudioAttributes built in this process to that combination: the app keeps
 * playing normally, but on the speaker, while every other app stays on Bluetooth.
 */
public class HookApp implements IXposedHookLoadPackage {

    /** Written by the system_server half of the module. */
    private static final String SETTING_SPEAKER_PKGS = "audiosplit_speaker_pkgs";
    private static final int FLAG_BEACON = 0x8;
    private static final long CACHE_MS = 15000L;

    private String pkg;
    private volatile boolean enabled;
    private volatile long cacheAt;
    private int rewrites;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            return; // system side is HookSystem's job
        }
        pkg = lpparam.packageName;
        try {
            XposedHelpers.findAndHookMethod("android.media.AudioAttributes$Builder",
                    lpparam.classLoader, "build", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isTarget()) {
                                    return;
                                }
                                AudioAttributes original = (AudioAttributes) param.getResult();
                                if (original == null) {
                                    return;
                                }
                                if (original.getUsage() == AudioAttributes.USAGE_UNKNOWN
                                        && (original.getFlags() & FLAG_BEACON) != 0) {
                                    return; // already the speaker strategy
                                }
                                AudioAttributes replaced = new AudioAttributes.Builder(original)
                                        .setUsage(AudioAttributes.USAGE_UNKNOWN)
                                        .setFlags(FLAG_BEACON)
                                        .build();
                                param.setResult(replaced);
                                if (++rewrites == 1 || rewrites % 25 == 0) {
                                    Log.i("speaker rewrite in " + pkg + " (#" + rewrites
                                            + ", was usage=" + original.getUsage() + ")");
                                }
                            } catch (Throwable t) {
                                Log.e("speaker rewrite failed in " + pkg, t);
                            }
                        }
                    });
            Log.i("app hook installed in " + pkg);
        } catch (Throwable t) {
            Log.e("app hook failed in " + pkg, t);
        }
    }

    /** True when the module config lists this package as pinned to the speaker. */
    private boolean isTarget() {
        long now = System.currentTimeMillis();
        if (now - cacheAt < CACHE_MS) {
            return enabled;
        }
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return enabled; // keep the previous answer until a context exists
            }
            String value = Settings.Global.getString(ctx.getContentResolver(),
                    SETTING_SPEAKER_PKGS);
            boolean nowEnabled = value != null && contains(value, pkg);
            if (nowEnabled != enabled) {
                Log.i(pkg + " speaker target = " + nowEnabled);
            }
            enabled = nowEnabled;
            cacheAt = now;
        } catch (Throwable t) {
            Log.e("reading speaker target list failed", t);
            cacheAt = now;
        }
        return enabled;
    }

    private static boolean contains(String csv, String pkg) {
        for (String part : csv.split(",")) {
            if (part.trim().equals(pkg)) {
                return true;
            }
        }
        return false;
    }
}
