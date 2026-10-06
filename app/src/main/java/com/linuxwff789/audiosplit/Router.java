package com.linuxwff789.audiosplit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedHelpers;

/**
 * UID device affinity router.
 *
 * How the framework lets us do this (Android 12+):
 *  - a privileged client registers an {@code AudioPolicy} carrying one RENDER {@code AudioMix}
 *    per device it may steer audio to (here: the A2DP device and the built-in speaker),
 *  - {@code AudioPolicy.setUidDeviceAffinity(uid, devices)} then teaches the audio policy that
 *    this UID may only be routed to those devices: the uid is excluded from the other mixes.
 *
 * Mixes are matched on audio attributes, so the first mix whose usage rules match an app wins.
 * We therefore register the "default" device (A2DP) mix FIRST: apps without an affinity keep
 * playing on Bluetooth exactly as before, while an app pinned to the speaker is excluded from
 * that mix and lands on the speaker mix instead.
 */
public final class Router {

    // hidden constants, values verified against android-14.0.0_r1
    private static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    private static final int ROUTE_FLAG_RENDER = 0x1;

    private static final long TICK_MS = 20000L;

    private static ClassLoader sCl;
    private static Class<?> cRuleBuilder;
    private static Class<?> cRule;
    private static Class<?> cMixBuilder;
    private static Class<?> cPolicyBuilder;
    private static Class<?> cPolicy;

    private static Object sPolicy;
    private static String sDeviceSig = "";
    private static long sCfgStamp = -1;
    private static final Map<Integer, String> sApplied = new LinkedHashMap<>();

    private static Handler sHandler;
    private static boolean sRunning;

    private Router() {
    }

    static void attachClassLoader(ClassLoader cl) {
        sCl = cl;
        try {
            cRuleBuilder = XposedHelpers.findClass(
                    "android.media.audiopolicy.AudioMixingRule$Builder", cl);
            cRule = XposedHelpers.findClass("android.media.audiopolicy.AudioMixingRule", cl);
            cMixBuilder = XposedHelpers.findClass("android.media.audiopolicy.AudioMix$Builder", cl);
            cPolicyBuilder = XposedHelpers.findClass(
                    "android.media.audiopolicy.AudioPolicy$Builder", cl);
            cPolicy = XposedHelpers.findClass("android.media.audiopolicy.AudioPolicy", cl);
            Log.i("audiopolicy classes resolved");
        } catch (Throwable t) {
            Log.e("audiopolicy class lookup failed", t);
        }
    }

    public static synchronized void start(final Context ctx) {
        sRunning = true;
        if (sHandler == null) {
            sHandler = new Handler(Looper.getMainLooper());
        }
        tick(ctx);
    }

    private static synchronized void tick(final Context ctx) {
        try {
            Config cfg = Config.load();
            if (cfg == null) {
                Log.i("no config at " + Config.PATHS[0] + " or " + Config.PATHS[1] + " - idle");
            } else {
                if (cfg.stamp != sCfgStamp) {
                    sCfgStamp = cfg.stamp;
                    Log.i("config loaded: " + cfg);
                }
                apply(ctx, cfg);
            }
        } catch (Throwable t) {
            Log.e("tick failed", t);
        }
        if (sRunning && sHandler != null) {
            sHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tick(ctx);
                }
            }, TICK_MS);
        }
    }

    private static void apply(Context ctx, Config cfg) {
        if (cPolicyBuilder == null || cPolicy == null) {
            Log.e("audiopolicy classes unavailable, cannot apply", null);
            return;
        }
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Log.e("no AudioManager", null);
            return;
        }
        List<AudioDeviceInfo> outs = new ArrayList<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            outs.add(d);
        }
        AudioDeviceInfo speaker = firstOfType(outs, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        AudioDeviceInfo a2dp = firstOfType(outs, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        AudioDeviceInfo wired = deviceFor("wired", outs);
        // Where apps that are NOT pinned already play. The mix for this device is registered
        // FIRST: an unpinned app matches the first mix whose usage rules fit, so it keeps its
        // current route instead of being pulled to the speaker.
        AudioDeviceInfo defaultDev = a2dp != null ? a2dp : (wired != null ? wired : speaker);
        List<AudioDeviceInfo> mixDevices = new ArrayList<>();
        if (defaultDev != null) {
            mixDevices.add(defaultDev);
        }
        for (Config.App app : cfg.apps) {
            AudioDeviceInfo d = deviceFor(app.device, outs);
            if (d != null && !containsDevice(mixDevices, d)) {
                mixDevices.add(d);
            }
        }
        if (mixDevices.isEmpty()) {
            Log.e("no usable output device to build mixes for", null);
            return;
        }

        String sig = deviceSig(mixDevices);
        if (sPolicy == null || !sig.equals(sDeviceSig)) {
            unregister(am);
            register(am, ctx, mixDevices);
            sDeviceSig = sig;
            sApplied.clear();
        }
        if (sPolicy == null) {
            return;
        }

        Map<Integer, String> desired = new LinkedHashMap<>();
        for (Config.App app : cfg.apps) {
            int uid = app.uid > 0 ? app.uid : uidOf(ctx, app.pkg);
            if (uid <= 0) {
                Log.e("unknown uid for " + app.pkg, null);
                continue;
            }
            desired.put(uid, app.device);
        }

        for (Integer uid : new ArrayList<>(sApplied.keySet())) {
            if (!desired.containsKey(uid)) {
                Object res = call(sPolicy, "removeUidDeviceAffinity",
                        new Class<?>[]{int.class}, uid);
                sApplied.remove(uid);
                Log.i("affinity removed uid=" + uid + " -> " + res);
            }
        }

        for (Map.Entry<Integer, String> e : desired.entrySet()) {
            String already = sApplied.get(e.getKey());
            if (already != null && already.equals(e.getValue())) {
                continue;
            }
            AudioDeviceInfo dev = deviceFor(e.getValue(), outs);
            if (dev == null) {
                Log.e("device '" + e.getValue() + "' unavailable for uid " + e.getKey(), null);
                continue;
            }
            Object res = call(sPolicy, "setUidDeviceAffinity",
                    new Class<?>[]{int.class, List.class}, e.getKey(),
                    Collections.singletonList(dev));
            Log.i("setUidDeviceAffinity uid=" + e.getKey() + " -> " + e.getValue()
                    + "(devId " + dev.getId() + ") = " + res);
            if (Boolean.TRUE.equals(res)) {
                sApplied.put(e.getKey(), e.getValue());
            }
        }
    }

    private static void register(AudioManager am, Context ctx, List<AudioDeviceInfo> devices) {
        try {
            ArrayList<Object> mixes = new ArrayList<>();
            for (AudioDeviceInfo d : devices) {
                mixes.add(buildMix(d));
            }

            Object pb = cPolicyBuilder.getConstructor(Context.class).newInstance(ctx);
            for (Object mix : mixes) {
                XposedHelpers.callMethod(pb, "addMix", mix);
            }
            Object policy = XposedHelpers.callMethod(pb, "build");
            Object res = call(am, "registerAudioPolicy", new Class<?>[]{cPolicy}, policy);
            Log.i("registerAudioPolicy -> " + res + " (mixes=" + mixes.size() + ")");
            sPolicy = policy;
        } catch (Throwable t) {
            Log.e("registering audio policy failed", t);
            sPolicy = null;
        }
    }

    private static void unregister(AudioManager am) {
        if (sPolicy == null) {
            return;
        }
        try {
            Object res = call(am, "unregisterAudioPolicyAsync", new Class<?>[]{cPolicy}, sPolicy);
            Log.i("unregisterAudioPolicyAsync -> " + res);
        } catch (Throwable t) {
            Log.e("unregister failed", t);
        }
        sPolicy = null;
    }

    private static Object buildMix(AudioDeviceInfo dev) throws Throwable {
        Object rule = buildRule();
        Object mb = cMixBuilder.getConstructor(cRule).newInstance(rule);
        call(mb, "setRouteFlags", new Class<?>[]{int.class}, ROUTE_FLAG_RENDER);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        call(mb, "setFormat", new Class<?>[]{AudioFormat.class}, fmt);
        // must come after setRouteFlags(ROUTE_FLAG_RENDER)
        call(mb, "setDevice", new Class<?>[]{AudioDeviceInfo.class}, dev);
        return call(mb, "build", new Class<?>[]{});
    }

    /** One broad usage rule (media playback) so that the mix matches normal app audio. */
    private static Object buildRule() throws Throwable {
        Object rb = cRuleBuilder.getConstructor().newInstance();
        int[] usages = {
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
        };
        for (int usage : usages) {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(usage).build();
            call(rb, "addRule", new Class<?>[]{AudioAttributes.class, int.class},
                    attrs, RULE_MATCH_ATTRIBUTE_USAGE);
        }
        return call(rb, "build", new Class<?>[]{});
    }

    private static AudioDeviceInfo deviceFor(String name, List<AudioDeviceInfo> outs) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "speaker":
                return firstOfType(outs, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            case "a2dp":
            case "bt":
                return firstOfType(outs, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
            case "wired":
                AudioDeviceInfo headset = firstOfType(outs, AudioDeviceInfo.TYPE_WIRED_HEADSET);
                return headset != null
                        ? headset
                        : firstOfType(outs, AudioDeviceInfo.TYPE_WIRED_HEADPHONES);
            case "usb":
                AudioDeviceInfo usb = firstOfType(outs, AudioDeviceInfo.TYPE_USB_HEADSET);
                return usb != null ? usb : firstOfType(outs, AudioDeviceInfo.TYPE_USB_DEVICE);
            case "ble":
                AudioDeviceInfo ble = firstOfType(outs, AudioDeviceInfo.TYPE_BLE_HEADSET);
                return ble != null
                        ? ble
                        : firstOfType(outs, AudioDeviceInfo.TYPE_BLE_SPEAKER);
            default:
                return null;
        }
    }

    private static AudioDeviceInfo firstOfType(List<AudioDeviceInfo> outs, int type) {
        for (AudioDeviceInfo d : outs) {
            if (d.getType() == type) {
                return d;
            }
        }
        return null;
    }

    private static boolean containsDevice(List<AudioDeviceInfo> list, AudioDeviceInfo dev) {
        for (AudioDeviceInfo d : list) {
            if (d.getId() == dev.getId()) {
                return true;
            }
        }
        return false;
    }

    private static String deviceSig(List<AudioDeviceInfo> devices) {
        StringBuilder sb = new StringBuilder();
        for (AudioDeviceInfo d : devices) {
            sb.append(d.getType()).append(':').append(d.getId()).append(',');
        }
        return sb.toString();
    }

    private static int uidOf(Context ctx, String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = XposedHelpers.findMethodExact(target.getClass(), name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            Log.e("call " + name + " failed", t);
            return null;
        }
    }
}
