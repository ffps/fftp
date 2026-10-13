package io.github.ffps.fftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After boot: starts the server whenever the autostart option is on (whether or not it was
 * running or stopped before). After an app update: restores the server if it was running.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (Prefs.enabled(context)) FtpService.start(context);
        } else if (Prefs.autostart(context)) {
            Prefs.setEnabled(context, true);
            FtpService.start(context);
        }
    }
}
