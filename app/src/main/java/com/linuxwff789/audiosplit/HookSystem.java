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
     * Suppress AUDIOFOCUS_LOSS/LOSS_TRANSIENT/LOSS_TRANSIENT_CAN_DUCK delivery to pinned uids, so
     * two pinned apps cannot pause each other.
     */
    private void hookFocus(Object audioService) {
        try {
            Object focus = findFocusController(audioService);
            if (focus == null) {
                Router.setFocusInfo("focus controller not found");
                Log.e("focus controller not found", null);
                return;
            }
            Class<?> cls = focus.getClass();
            int hooked = 0;
            StringBuilder names = new StringBuilder();
            for (final Method m : cls.getDeclaredMethods()) {
                names.append(m.getName()).append(' ');
                boolean isChangeDelivery = m.getName().toLowerCase().contains("focuschange")
                        && hasIntParameter(m) && m.getReturnType() == void.class;
                boolean isRequest = "requestAudioFocus".equals(m.getName());
                if (!isChangeDelivery && !isRequest) {
                    continue;
                }
                if (isChangeDelivery) {
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
                                if (uid > 0 && change >= 1 && change <= 3
                                        && Router.isPinned(uid)) {
                                    Router.logFocus("suppressed loss change=" + change
                                            + " uid=" + uid + " via " + m.getName());
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Router.logFocus("loss hook error " + t);
                            }
                        }
                    });
                } else {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                int uid = Binder.getCallingUid();
                                if (Router.isPinned(uid)) {
                                    Router.logFocus("request uid=" + uid + " result="
                                            + param.getResult());
                                }
                            } catch (Throwable ignored) {
                                // never break focus handling
                            }
                        }
                    });
                }
                hooked++;
            }
            Router.setFocusInfo(hooked + " hooks on " + cls.getName() + "; methods: " + names);
            Log.i("focus hooks installed: " + hooked + " on " + cls.getName());
        } catch (Throwable t) {
            Log.e("hooking focus failed", t);
            Router.setFocusInfo("failed: " + t);
        }
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
