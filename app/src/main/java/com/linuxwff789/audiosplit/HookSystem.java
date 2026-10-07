package com.linuxwff789.audiosplit;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks system_server only ("android" scope).
 *
 * Two jobs:
 *  1. {@link Router} - register an AudioPolicy with one RENDER mix per steerable device and pin
 *     chosen UIDs onto a device with UID device affinity.
 *  2. Neutralise the two framework gates that stop "two sounds at once":
 *     - AudioService.checkUpdateForPolicy() demands MODIFY_AUDIO_ROUTING from the calling uid,
 *       which system_server itself does not hold on this ROM (the affinity API then returns false),
 *     - the audio focus machinery hands AUDIOFOCUS_LOSS to the previous player as soon as another
 *       app takes focus, so the two apps keep stopping each other.
 *
 * Everything is resolved from the *live* AudioService instance (field/method names only, no hard
 * class names or signatures) because on this ROM both android.media.IAudioPolicyCallback and
 * android.media.MediaFocusControl are not resolvable by name from the system_server class loader.
 */
public class HookSystem implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        Log.i("injected into system_server, version=" + BuildConfig.VERSION_NAME);
        Router.attachClassLoader(lpparam.classLoader);

        boolean hooked = hookReady(lpparam, "onSystemReady");
        if (!hooked) {
            hooked = hookReady(lpparam, "systemReady");
        }
        if (!hooked) {
            Log.e("could not hook AudioService ready callback", null);
        }
    }

    private boolean hookReady(XC_LoadPackage.LoadPackageParam lpparam, final String method) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.audio.AudioService",
                    lpparam.classLoader, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.i("AudioService." + method + "() returned, preparing routing");
                            try {
                                final Context ctx = (Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext");
                                if (ctx == null) {
                                    Log.e("AudioService context is null", null);
                                    return;
                                }
                                Object audioService = param.thisObject;
                                Router.setAudioService(audioService);
                                hookPermissionGate(audioService);
                                hookFocus(audioService);
                                hookPlayers(audioService);
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        Router.start(ctx);
                                    }
                                }, 5000);
                            } catch (Throwable t) {
                                Log.e("ready hook body failed", t);
                            }
                        }
                    });
            Log.i("hooked AudioService." + method);
            return true;
        } catch (Throwable t) {
            Log.e("hooking AudioService." + method + " failed", t);
            return false;
        }
    }

    /**
     * AudioService.checkUpdateForPolicy(pcb, msg) asks MODIFY_AUDIO_ROUTING from the *calling* uid.
     * uid 0/1000 answer "denied" on this ROM, which makes every setUidDeviceAffinity() call fail.
     * Let those two through; every other caller keeps the framework's answer.
     */
    private void hookPermissionGate(Object audioService) {
        try {
            int count = 0;
            for (final Method m : audioService.getClass().getDeclaredMethods()) {
                if (!"checkUpdateForPolicy".equals(m.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        try {
                            int caller = Binder.getCallingUid();
                            if (caller != 0 && caller != Process.SYSTEM_UID) {
                                return null;
                            }
                            Object pcb = param.args[0];
                            Object binder = pcb == null ? null
                                    : pcb.getClass().getMethod("asBinder").invoke(pcb);
                            Object map = XposedHelpers.getObjectField(param.thisObject,
                                    "mAudioPolicies");
                            if (map instanceof Map && binder != null) {
                                Object proxy = ((Map<?, ?>) map).get(binder);
                                if (proxy != null) {
                                    return proxy;
                                }
                                Log.e("policy not registered (caller uid " + caller + ")", null);
                            }
                            return null;
                        } catch (Throwable t) {
                            Log.e("permission gate bypass failed", t);
                            return null;
                        }
                    }
                });
                count++;
            }
            Log.i("permission gate hooked: " + count + " method(s)");
        } catch (Throwable t) {
            Log.e("hooking permission gate failed", t);
        }
    }

    /** Locate the focus controller object held by AudioService (field name varies by version). */
    private Object findFocusController(Object audioService) {
        Field[] fields = audioService.getClass().getDeclaredFields();
        Object fallback = null;
        for (Field f : fields) {
            String name = f.getName();
            if (!name.toLowerCase().contains("focus")) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(audioService);
                if (value == null) {
                    continue;
                }
                if (value.getClass().getName().contains("FocusControl")) {
                    Log.i("focus controller field: " + name + " -> " + value.getClass().getName());
                    return value;
                }
                fallback = value;
            } catch (Throwable ignored) {
                // skip unreadable fields
            }
        }
        if (fallback != null) {
            Log.i("focus field fallback -> " + fallback.getClass().getName());
        }
        return fallback;
    }

    /**
     * Two independent levers so pinned apps never pause each other:
     *
     * <ol>
     *   <li>a focus request from a pinned uid is answered with REQUEST_GRANTED without touching
     *       the focus stack, so it cannot push anyone out;</li>
     *   <li>any void method on the focus controller (and on the objects it holds) that looks like
     *       it delivers a focus change is skipped when it would hand LOSS to a pinned uid.</li>
     * </ol>
     *
     * Method names are never assumed: the controller object is taken from AudioService and every
     * candidate is discovered by reflection; the discovered names are written to the log too.
     */
    private void hookFocus(Object audioService) {
        try {
            Object focus = findFocusController(audioService);
            if (focus == null) {
                Router.setFocusInfo("focus controller not found");
                Log.e("focus controller not found", null);
                return;
            }
            java.util.List<Object> targets = new java.util.ArrayList<>();
            targets.add(focus);
            // one level of nested helpers (FocusStack, ExtPolicyAdapter, ...)
            for (Field f : focus.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(focus);
                    if (value != null && !(value instanceof Number) && !(value instanceof String)
                            && !(value instanceof Boolean)
                            && !(value instanceof java.util.Collection)
                            && !(value instanceof java.util.Map)) {
                        targets.add(value);
                    }
                } catch (Throwable ignored) {
                    // unreadable field
                }
            }

            int hooked = 0;
            StringBuilder names = new StringBuilder();
            java.util.Set<String> hookedSignatures = new java.util.HashSet<>();
            for (Object target : targets) {
                Class<?> cls = target.getClass();
                names.append('[').append(cls.getName()).append("] ");
                for (final Method m : cls.getDeclaredMethods()) {
                    names.append(m.getName()).append(' ');
                    boolean isRequest = "requestAudioFocus".equals(m.getName());
                    boolean looksLikeDelivery = m.getReturnType() == void.class
                            && hasIntParameter(m)
                            && matches(m.getName(), "loss", "duck", "focus", "propagate",
                                    "dispatch", "notify", "send", "handle");
                    if (!isRequest && !looksLikeDelivery) {
                        continue;
                    }
                    String key = cls.getName() + '#' + m.getName() + m.getParameterCount();
                    if (!hookedSignatures.add(key)) {
                        continue;
                    }
                    if (isRequest) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    int uid = Binder.getCallingUid();
                                    if (!Router.isPinned(uid)) {
                                        return;
                                    }
                                    Router.logFocus("no-op focus grant uid=" + uid);
                                    // AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                                    param.setResult(Integer.valueOf(1));
                                } catch (Throwable t) {
                                    Router.logFocus("request hook error " + t);
                                }
                            }
                        });
                    } else {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    int change = Integer.MIN_VALUE;
                                    int uid = -1;
                                    for (Object arg : param.args) {
                                        if (arg instanceof Integer) {
                                            change = (Integer) arg;
                                        } else if (arg != null && arg.getClass().getName()
                                                .endsWith("AudioFocusInfo")) {
                                            Object u = XposedHelpers.callMethod(arg, "getClientUid");
                                            if (u instanceof Integer) {
                                                uid = (Integer) u;
                                            }
                                        }
                                    }
                                    if (uid <= 0) {
                                        return;
                                    }
                                    // 1 = LOSS, 2 = LOSS_TRANSIENT, 3 = LOSS_TRANSIENT_CAN_DUCK
                                    boolean isLoss = change >= 1 && change <= 3;
                                    if (!Router.isPinned(uid)) {
                                        if (isLoss) {
                                            Router.logFocus("loss to unpinned uid=" + uid
                                                    + " change=" + change + " via " + m.getName());
                                        }
                                        return;
                                    }
                                    Router.logFocus("focus delivery to pinned uid=" + uid
                                            + " change=" + change + " via " + m.getName()
                                            + (isLoss ? " (suppressed)" : ""));
                                    if (isLoss) {
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    Router.logFocus("delivery hook error " + t);
                                }
                            }
                        });
                    }
                    hooked++;
                }
            }
            Router.setFocusInfo(hooked + " hooks on " + focus.getClass().getName());
            Log.i("focus hooks installed: " + hooked + " on " + focus.getClass().getName());
            Log.i("focus methods: " + names);
        } catch (Throwable t) {
            Log.e("hooking focus failed", t);
            Router.setFocusInfo("failed: " + t);
        }
    }

    /**
     * Player lifecycle probe: records which uid started/stopped playing, so a test run leaves
     * hard evidence of whether two apps were really playing at the same time.
     */
    private void hookPlayers(Object audioService) {
        try {
            int hooked = 0;
            for (final Method m : audioService.getClass().getDeclaredMethods()) {
                if ("trackPlayer".equals(m.getName()) && m.getReturnType() == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Router.logFocus("player start uid=" + Binder.getCallingUid()
                                        + " piid=" + param.getResult());
                            } catch (Throwable ignored) {
                                // probe only
                            }
                        }
                    });
                    hooked++;
                } else if ("playerEvent".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Router.logFocus("player event piid=" + param.args[0]
                                        + " event=" + param.args[1]);
                            } catch (Throwable ignored) {
                                // probe only
                            }
                        }
                    });
                    hooked++;
                } else if ("releasePlayer".equals(m.getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Router.logFocus("player release piid=" + param.args[0]);
                            } catch (Throwable ignored) {
                                // probe only
                            }
                        }
                    });
                    hooked++;
                }
            }
            Log.i("player hooks installed: " + hooked);
        } catch (Throwable t) {
            Log.e("hooking players failed", t);
        }
    }

    private static boolean matches(String name, String... needles) {
        String lower = name.toLowerCase();
        for (String n : needles) {
            if (lower.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasIntParameter(Method m) {
        for (Class<?> p : m.getParameterTypes()) {
            if (p == int.class) {
                return true;
            }
        }
        return false;
    }
}
