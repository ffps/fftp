package io.github.ffps.fftp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Foreground service that owns the FTP server and shows the status-bar notification. */
public class FtpService extends Service {
    static final String ACTION_STOP = "io.github.ffps.fftp.STOP";
    private static final String CHANNEL = "fftp";
    private static final int NOTIFICATION_ID = 1;

    /** True while the server socket is listening. */
    static volatile boolean running;
    /** Last start error (null if none). */
    static volatile String error;
    /** True from the moment the service accepted a start until it is destroyed. */
    static volatile boolean active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FtpServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context c) {
        Intent i = new Intent(c, FtpService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** IPv4 addresses of this device (without loopback). */
    static List<String> addresses() {
        List<String> res = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) res.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return res;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = Prefs.port(this);
        List<String> ips = addresses();
        String text = ips.isEmpty() ? "port " + port : "ftp://" + ips.get(0) + ":" + port;
        // Must be called promptly after startForegroundService() on Android 8+.
        startForeground(NOTIFICATION_ID, buildNotification(text));

        if (!Prefs.enabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        active = true;

        stopServer();
        error = null;
        final FtpServer s = new FtpServer(port, Prefs.user(this), Prefs.pass(this),
                Environment.getExternalStorageDirectory());
        server = s;
        // Bind off the main thread (StrictMode forbids network calls there).
        new Thread(new Runnable() {
            public void run() {
                try {
                    s.start();
                    running = true;
                } catch (final IOException e) {
                    error = String.valueOf(e.getMessage());
                    running = false;
                    main.post(new Runnable() {
                        public void run() {
                            Toast.makeText(FtpService.this,
                                    getString(R.string.start_failed, error), Toast.LENGTH_LONG).show();
                        }
                    });
                    stopSelf();
                }
            }
        }, "fftp-start").start();

        acquireLocks();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        running = false;
        active = false;
        releaseLocks();
        stopForeground(true);
        super.onDestroy();
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
    }

    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fftp:server");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "fftp");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
            // Locks are best-effort.
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }

    private int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_SETTINGS, true), piFlags());
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FtpService.class).setAction(ACTION_STOP), piFlags());

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, getString(R.string.stop), stop)
                .build();
    }
}
