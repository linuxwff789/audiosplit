package com.linuxwff789.audiosplit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-pushes the saved config on boot: system_server starts before this and may have no config. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String json = AppConfigStore.load(context);
            if (json != null) {
                AppConfigStore.push(context, json);
                Log.i("boot: re-pushed saved config");
            }
        } catch (Throwable t) {
            Log.e("boot re-push failed", t);
        }
    }
}
