package com.linuxwff789.audiosplit;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Only hooks system_server ("android" scope).
 *
 * The real work happens in {@link Router}: after AudioService is ready we register an
 * AudioPolicy carrying one RENDER mix per output device we want to be able to steer to,
 * then pin chosen UIDs onto one of those devices with setUidDeviceAffinity().
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
        hookPolicyPermissionGate(lpparam);
        hookFocus(lpparam);
    }

    /**
     * Audio focus: Android pauses/ducks the current player whenever another app takes focus. For
     * the "two sounds at once" use case the pinned apps must ignore focus loss, otherwise the
     * second app stops the first no matter how the devices are routed.
     *
     * Method names are resolved by reflection (no hard signature), and the discovered names are
     * reported in the UI self check.
     */
    private void hookFocus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> focusControl = XposedHelpers.findClass("android.media.MediaFocusControl",
                    lpparam.classLoader);
            int hooked = 0;
            StringBuilder names = new StringBuilder();
            for (final java.lang.reflect.Method m : focusControl.getDeclaredMethods()) {
                names.append(m.getName()).append(' ');
                if ("dispatchAudioFocusChange".equals(m.getName())) {
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
                                // 1 = LOSS, 2 = LOSS_TRANSIENT, 3 = LOSS_TRANSIENT_CAN_DUCK
                                if (uid > 0 && change >= 1 && change <= 3 && Router.isPinned(uid)) {
                                    Router.logFocus("suppressed loss change=" + change
                                            + " uid=" + uid);
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Router.logFocus("loss hook error " + t);
                            }
                        }
                    });
                    hooked++;
                } else if ("requestAudioFocus".equals(m.getName())) {
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
                    hooked++;
                }
            }
            Router.setFocusInfo(hooked + " hooks; methods: " + names);
            Log.i("focus hooks installed: " + hooked);
        } catch (Throwable t) {
            Log.e("hooking focus failed", t);
            Router.setFocusInfo("failed: " + t);
        }
    }

    /**
     * AudioService.checkUpdateForPolicy() asks MODIFY_AUDIO_ROUTING from the calling uid; on this
     * ROM not even system_server (uid 1000) holds it, which makes setUidDeviceAffinity() bail out.
     * Let uid 0 / 1000 through and keep everyone else denied.
     */
    private void hookPolicyPermissionGate(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> policyCallback = XposedHelpers.findClass("android.media.IAudioPolicyCallback",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod("com.android.server.audio.AudioService",
                    lpparam.classLoader, "checkUpdateForPolicy",
                    policyCallback, String.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            try {
                                int caller = Binder.getCallingUid();
                                if (caller != 0 && caller != Process.SYSTEM_UID) {
                                    return null; // same denial the framework would give
                                }
                                Object pcb = param.args[0];
                                Object binder = XposedHelpers.callMethod(pcb, "asBinder");
                                Object map = XposedHelpers.getObjectField(
                                        param.thisObject, "mAudioPolicies");
                                if (map instanceof java.util.Map) {
                                    Object proxy = ((java.util.Map<?, ?>) map).get(binder);
                                    if (proxy != null) {
                                        return proxy;
                                    }
                                    Log.e("policy not registered for uid " + caller, null);
                                }
                                return null;
                            } catch (Throwable t) {
                                Log.e("permission gate bypass failed", t);
                                return null;
                            }
                        }
                    });
            Log.i("policy permission gate hooked (uid 0/1000 allowed)");
        } catch (Throwable t) {
            Log.e("hooking permission gate failed", t);
        }
    }

    private boolean hookReady(XC_LoadPackage.LoadPackageParam lpparam, final String method) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.audio.AudioService",
                    lpparam.classLoader, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.i("AudioService." + method + "() returned, scheduling routing");
                            try {
                                final Context ctx = (Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext");
                                if (ctx == null) {
                                    Log.e("AudioService context is null", null);
                                    return;
                                }
                                Router.setAudioService(param.thisObject);
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
}
