package io.github.ffps.fftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the server after boot (if autostart is on) or after an app update (if it was enabled). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null || !Prefs.enabled(context)) return;
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (replaced || Prefs.autostart(context)) {
            FtpService.start(context);
        }
    }
}
