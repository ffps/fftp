package io.github.ffps.fftp;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings storage. */
final class Prefs {
    static final int DEFAULT_PORT = 8021;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("fftp", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).commit();
    }

    static boolean autostart(Context c) {
        return sp(c).getBoolean("autostart", false);
    }

    static int port(Context c) {
        return sp(c).getInt("port", DEFAULT_PORT);
    }

    static String user(Context c) {
        return sp(c).getString("user", "ftp");
    }

    static String pass(Context c) {
        return sp(c).getString("pass", "");
    }

    static void save(Context c, boolean enabled, int port, String user, String pass, boolean autostart) {
        sp(c).edit()
                .putBoolean("enabled", enabled)
                .putInt("port", port)
                .putString("user", user)
                .putString("pass", pass)
                .putBoolean("autostart", autostart)
                .commit();
    }
}
