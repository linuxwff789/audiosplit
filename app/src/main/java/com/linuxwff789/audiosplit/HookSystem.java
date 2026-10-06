package com.linuxwff789.audiosplit;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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
