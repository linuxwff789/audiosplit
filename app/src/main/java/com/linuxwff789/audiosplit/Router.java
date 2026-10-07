package com.linuxwff789.audiosplit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Build;
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
    private static Config sPushed;
    private static boolean sReceiverRegistered;
    private static Object sAudioService;
    private static final java.util.Set<String> sMissingLogged = new java.util.HashSet<>();
    private static Context sContext;
    private static String sLastRoute = "not run yet";
    private static final String PERM_MODIFY_AUDIO_ROUTING =
            "android.permission.MODIFY_AUDIO_ROUTING";

    static void setAudioService(Object audioService) {
        sAudioService = audioService;
    }

    /** uid currently pinned by the UI (device available or not) - used by the focus hooks. */
    static boolean isPinned(int uid) {
        return sApplied.containsKey(uid);
    }

    private static String sFocusInfo = "not installed";
    private static String sLastFocusLog = "";
    private static long sLastFocusLogAt;
    private static String sLastState = "";

    static void setFocusInfo(String info) {
        sFocusInfo = info;
    }

    /** Rate-limited focus logging: repeated identical events must not flood the status log. */
    static void logFocus(String msg) {
        long now = System.currentTimeMillis();
        if (msg.equals(sLastFocusLog) && now - sLastFocusLogAt < 5000) {
            return;
        }
        sLastFocusLog = msg;
        sLastFocusLogAt = now;
        Log.i("[focus] " + msg);
    }

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
        sContext = ctx;
        if (sHandler == null) {
            sHandler = new Handler(Looper.getMainLooper());
        }
        registerConfigReceiver(ctx);
        tick(ctx);
    }

    /**
     * Everything the UI needs to judge whether routing can work here: our own permissions as seen
     * by system_server, policy state, last routing attempt and the devices we can steer to.
     */
    static String selfCheck() {
        StringBuilder sb = new StringBuilder();
        sb.append("module: injected into system_server, receiver=")
                .append(sReceiverRegistered).append('\n');
        sb.append("uid ").append(android.os.Process.myUid()).append(' ')
                .append(PERM_MODIFY_AUDIO_ROUTING).append(": ")
                .append(permName(checkCallingPermission(PERM_MODIFY_AUDIO_ROUTING)))
                .append(" -> permission gate bypassed for uid 0/1000\n");
        sb.append("audiopolicy: ")
                .append(sPolicy == null ? "not registered" : "registered").append('\n');
        sb.append("last route: ").append(sLastRoute).append('\n');
        sb.append("focus: ").append(sFocusInfo).append('\n');
        sb.append("pinned: ").append(sApplied.isEmpty() ? "(none)" : sApplied.toString())
                .append('\n');
        sb.append("devices: ").append(deviceSummary()).append('\n');
        sb.append("playing: ").append(playingSummary()).append('\n');
        return sb.toString();
    }

    /** Which uids the framework currently sees as playing (anonymised on locked-down builds). */
    private static String playingSummary() {
        if (sContext == null) {
            return "(no context)";
        }
        try {
            AudioManager am = (AudioManager) sContext.getSystemService(Context.AUDIO_SERVICE);
            Object list = XposedHelpers.callMethod(am, "getActivePlaybackConfigurations");
            if (!(list instanceof java.util.List)) {
                return "?";
            }
            StringBuilder sb = new StringBuilder();
            for (Object conf : (java.util.List<?>) list) {
                Object uid = XposedHelpers.callMethod(conf, "getClientUid");
                Object state = XposedHelpers.callMethod(conf, "getPlayerState");
                sb.append(uid).append('/').append(state).append(' ');
            }
            return sb.length() == 0 ? "(nothing)" : sb.toString();
        } catch (Throwable t) {
            return "(failed: " + t + ")";
        }
    }

    /** Compact device list for the periodic state line. */
    private static String devicesForLog() {
        if (sPolicy == null) {
            return " | no policy";
        }
        return " | mixes=" + sDeviceSig;
    }

    private static String deviceSummary() {
        if (sContext == null) {
            return "(no context)";
        }
        try {
            AudioManager am = (AudioManager) sContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return "(no AudioManager)";
            }
            StringBuilder sb = new StringBuilder();
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                sb.append("0x").append(Integer.toHexString(d.getType()))
                        .append("#").append(d.getId()).append(' ');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(failed: " + t + ")";
        }
    }

    private static String permName(int result) {
        return result == 0 ? "GRANTED" : "DENIED(" + result + ")";
    }

    private static int checkCallingPermission(String permission) {
        if (sContext == null) {
            return -1;
        }
        try {
            Object r = XposedHelpers.callMethod(sContext, "checkCallingPermission",
                    new Class<?>[]{String.class}, permission);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Throwable t) {
            Log.e("checkCallingPermission(" + permission + ") failed", t);
            return -1;
        }
    }

    /** The UI pushes its config straight into system_server, no root and no file permissions. */
    private static void registerConfigReceiver(final Context ctx) {
        if (sReceiverRegistered) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        String json = intent.getStringExtra(Protocol.EXTRA_JSON);
                        if (json == null) {
                            Log.i("status ping from UI");
                        } else {
                            Config cfg = Config.parse(json, "push");
                            if (cfg == null) {
                                Log.e("pushed config rejected", null);
                            } else {
                                sPushed = cfg;
                                Log.i("config pushed from UI: " + cfg);
                                tick(ctx);
                            }
                        }
                        sendStatus(ctx);
                    } catch (Throwable t) {
                        Log.e("config broadcast handling failed", t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Protocol.ACTION_CONFIG);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            sReceiverRegistered = true;
            Log.i("config receiver registered");
        } catch (Throwable t) {
            Log.e("registerReceiver failed", t);
        }
    }

    private static void sendStatus(Context ctx) {
        try {
            Intent out = new Intent(Protocol.ACTION_STATUS);
            out.setPackage(Protocol.PKG);
            out.putExtra(Protocol.EXTRA_TEXT, selfCheck() + "---- log ----\n" + Status.tail(20));
            ctx.sendBroadcast(out);
        } catch (Throwable t) {
            Log.e("status broadcast failed", t);
        }
    }

    private static synchronized void tick(final Context ctx) {
        try {
            Config cfg = sPushed;
            if (cfg == null) {
                cfg = Config.load();
                if (cfg == null) {
                    Log.i("no config (no UI push, no file) - idle");
                }
            }
            if (cfg != null) {
                if (cfg.stamp != sCfgStamp) {
                    sCfgStamp = cfg.stamp;
                    Log.i("applying config: " + cfg);
                }
                apply(ctx, cfg);
            }
            String state = playingSummary() + devicesForLog();
            if (!state.equals(sLastState)) {
                sLastState = state;
                Log.i("state: " + state);
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
            sLastRoute = "no usable output device";
            Log.e("no usable output device to build mixes for", null);
            return;
        }

        String sig = deviceSig(mixDevices);
        if (sPolicy == null || !sig.equals(sDeviceSig)) {
            unregister(am);
            register(am, ctx, mixDevices);
            sDeviceSig = sig;
            sApplied.clear();
            sLastRoute = "policy registered for " + mixDevices.size() + " device(s)";
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
                // Keep the uid pinned even when its device is gone: fall back to the default
                // device so that focus protection still applies to it.
                dev = defaultDev;
                String key = e.getValue();
                if (dev != null && sMissingLogged.add(key)) {
                    Log.e("device '" + key + "' not connected, uid " + e.getKey()
                            + " stays on the default device for now", null);
                }
            }
            if (dev == null) {
                continue;
            }
            if (applyAffinity(e.getKey(), dev)) {
                sApplied.put(e.getKey(), e.getValue());
            }
        }
        sLastRoute = "rules=" + desired.size() + " pinned=" + sApplied.size()
                + (sPolicy == null ? " (no policy)" : "");
    }

    /**
     * Pin a uid onto a device. The framework's own AudioPolicy API is gated by
     * {@code AudioService.checkUpdateForPolicy()} which asks for MODIFY_AUDIO_ROUTING from the
     * *calling* uid - system_server (uid 1000) does not hold it on this ROM, so the direct call
     * returns false. We therefore keep two fallbacks: call the already resolved AudioPolicyProxy
     * directly, then the hidden AudioSystem entry point.
     */
    private static boolean applyAffinity(int uid, AudioDeviceInfo dev) {
        try {
            Object res = call(sPolicy, "setUidDeviceAffinity",
                    new Class<?>[]{int.class, List.class}, uid,
                    Collections.singletonList(dev));
            if (Boolean.TRUE.equals(res)) {
                Log.i("setUidDeviceAffinity uid=" + uid + " -> dev " + dev.getId()
                        + " via AudioPolicy API");
                return true;
            }
            Log.i("AudioPolicy API refused uid=" + uid + " (result " + res + "), trying proxy");
        } catch (Throwable t) {
            Log.e("AudioPolicy API threw for uid " + uid, t);
        }

        Object internal = XposedHelpers.callStaticMethod(AudioDeviceInfo.class,
                "convertDeviceTypeToInternalDevice", dev.getType());
        int internalType = internal instanceof Integer ? (Integer) internal : 0;
        int[] types = {internalType};
        String[] addrs = {dev.getAddress() == null ? "" : dev.getAddress()};

        Object proxy = findPolicyProxy();
        if (proxy != null) {
            Object r2 = call(proxy, "setUidDeviceAffinities",
                    new Class<?>[]{int.class, int[].class, String[].class}, uid, types, addrs);
            Log.i("AudioPolicyProxy.setUidDeviceAffinities uid=" + uid + " types=0x"
                    + Integer.toHexString(internalType) + " -> " + r2);
            if (Integer.valueOf(0).equals(r2)) {
                return true;
            }
        } else {
            Log.e("no AudioPolicyProxy found for our policy", null);
        }

        try {
            Class<?> audioSystem = XposedHelpers.findClass("android.media.AudioSystem", sCl);
            Object r3 = XposedHelpers.callStaticMethod(audioSystem, "setUidDeviceAffinities",
                    uid, types, addrs);
            Log.i("AudioSystem.setUidDeviceAffinities uid=" + uid + " -> " + r3);
            return Integer.valueOf(0).equals(r3);
        } catch (Throwable t) {
            Log.e("AudioSystem fallback failed", t);
            return false;
        }
    }

    private static Object findPolicyProxy() {
        if (sAudioService == null || sPolicy == null) {
            return null;
        }
        try {
            Object pcb = call(sPolicy, "cb", new Class<?>[]{});
            if (pcb == null) {
                return null;
            }
            Object binder = null;
            try {
                binder = pcb.getClass().getMethod("asBinder").invoke(pcb);
            } catch (Throwable t) {
                Log.e("asBinder() failed", t);
            }
            Object map = XposedHelpers.getObjectField(sAudioService, "mAudioPolicies");
            if (map instanceof java.util.Map && binder != null) {
                return ((java.util.Map<?, ?>) map).get(binder);
            }
        } catch (Throwable t) {
            Log.e("findPolicyProxy failed", t);
        }
        return null;
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
            StringBuilder names = new StringBuilder();
            for (AudioDeviceInfo d : devices) {
                names.append("type=0x").append(Integer.toHexString(d.getType()))
                        .append(" id=").append(d.getId()).append(' ');
            }
            Log.i("registerAudioPolicy -> " + res + " mixes=" + mixes.size() + " [" + names
                    + "]");
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
