package io.github.ffps.fftp;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    /** Set by the notification: show the settings even if the server is enabled. */
    static final String EXTRA_SETTINGS = "settings";

    private CheckBox cbEnable;
    private CheckBox cbAuto;
    private EditText etPort;
    private EditText etUser;
    private EditText etPass;
    private TextView tvStatus;
    private boolean suppress;

    private final Handler handler = new Handler();
    private final Runnable ticker = new Runnable() {
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // Server enabled but not running yet: start it silently, without the settings screen.
        // If it is already running (the user tapped the icon) or was opened from the
        // notification, show the settings. After a failed start (e.g. the port is busy) the
        // settings are shown too, otherwise there would be no way to fix it.
        if (state == null && !getIntent().getBooleanExtra(EXTRA_SETTINGS, false)
                && Prefs.enabled(this) && !FtpService.active && FtpService.error == null) {
            FtpService.start(this);
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        setContentView(R.layout.activity_main);
        cbEnable = (CheckBox) findViewById(R.id.cb_enable);
        cbAuto = (CheckBox) findViewById(R.id.cb_autostart);
        etPort = (EditText) findViewById(R.id.et_port);
        etUser = (EditText) findViewById(R.id.et_user);
        etPass = (EditText) findViewById(R.id.et_pass);
        tvStatus = (TextView) findViewById(R.id.tv_status);

        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_version)).setText(getString(R.string.version, v));
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        etPort.setText(String.valueOf(Prefs.port(this)));
        etUser.setText(Prefs.user(this));
        etPass.setText(Prefs.pass(this));
        cbAuto.setChecked(Prefs.autostart(this));
        cbEnable.setChecked(Prefs.enabled(this));

        cbEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!suppress) apply();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                apply();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void setEnableChecked(boolean v) {
        suppress = true;
        cbEnable.setChecked(v);
        suppress = false;
    }

    private void apply() {
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (NumberFormatException e) {
            port = 0;
        }
        if (port < 1024 || port > 65535) {
            Toast.makeText(this, R.string.bad_port, Toast.LENGTH_LONG).show();
            etPort.requestFocus();
            if (cbEnable.isChecked()) setEnableChecked(false);
            return;
        }
        boolean on = cbEnable.isChecked();
        Prefs.save(this, on, port, etUser.getText().toString().trim(), etPass.getText().toString(),
                cbAuto.isChecked());
        if (on) {
            requestStorageAccess();
            FtpService.start(this);
        } else {
            stopService(new Intent(this, FtpService.class));
        }
        refreshStatus();
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    private void refreshStatus() {
        // The service can switch itself off (Stop button in the notification).
        if (Prefs.enabled(this) != cbEnable.isChecked()) setEnableChecked(Prefs.enabled(this));

        String text;
        if (FtpService.running) {
            StringBuilder sb = new StringBuilder();
            List<String> ips = FtpService.addresses();
            int port = Prefs.port(this);
            if (ips.isEmpty()) sb.append("port ").append(port);
            for (String ip : ips) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("ftp://").append(ip).append(':').append(port);
            }
            text = getString(R.string.status_running, sb.toString());
        } else if (FtpService.error != null) {
            text = getString(R.string.status_error, FtpService.error);
        } else {
            text = getString(R.string.status_stopped);
        }
        tvStatus.setText(text);
    }
}
