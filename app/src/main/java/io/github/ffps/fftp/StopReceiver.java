package io.github.ffps.fftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** "Stop" button of the notification: turns the server off and remembers that. */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs.setEnabled(context, false);
        context.stopService(new Intent(context, FtpService.class));
    }
}
