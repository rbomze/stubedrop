package com.stubedrop;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private SharedPreferences p;
    private EditText port, token;
    private TextView status, urlView, httpsView, certView, logView, howView;
    private EditText pkg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        p = getSharedPreferences("cfg", MODE_PRIVATE);
        p.edit().remove("log").apply(); // purge any log stored by older versions
        try { TlsSupport.ensureKey(); } catch (Exception ignored) {} // generate the certificate on first launch

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(32);
        l.setPadding(pad, dp(24), pad, pad);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = text("S-Tube Drop", 28, true);
        title.setPadding(dp(16), 0, 0, 0);
        head.addView(title);
        l.addView(head);
        l.addView(text("Send YouTube links from your phone or computer straight to SmartTube on this TV.", 15, false));

        // port
        l.addView(heading("Port"));
        l.addView(text("The TCP port this TV listens on for incoming links (1024–65535). Change it only if another app already uses 8765.", 14, false));
        port = new EditText(this);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setSingleLine();
        port.setText(String.valueOf(p.getInt("port", 8765)));
        l.addView(port);

        // token
        l.addView(heading("Password (token)"));
        l.addView(text("A shared secret. Every request must carry it as t=… or it is rejected. Change the default 'changeme' – anyone on your network who knows it can start videos on this TV.", 14, false));
        token = new EditText(this);
        token.setSingleLine();
        token.setText(p.getString("token", "changeme"));
        l.addView(token);

        Button save = button("Save & (re)start");
        Button stop = button("Stop");
        LinearLayout row = new LinearLayout(this);
        row.addView(save); row.addView(stop);
        l.addView(row);
        status = text("", 15, true);
        l.addView(status);

        // url
        l.addView(heading("Your drop URLs"));
        l.addView(text("Any device on the same network can send a link, with either method on the same port:\n\n"
          + "POST (recommended): the link is the request body, so it needs no URL-escaping, and the password goes in the X-Token header, so it never appears in a URL or log.\n\n"
          + "GET: everything is in the URL: url=<link> and t=<password>. The link must be URL-escaped (for example & becomes %26).\n\n"
          + "Use testurl instead of a link to check the connection and password without starting a video.", 14, false));
        l.addView(text("Plain HTTP", 14, true));
        urlView = text("", 15, false);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setPadding(0, dp(4), 0, dp(4));
        l.addView(urlView);
        l.addView(text("HTTPS (encrypted, self-signed certificate)", 14, true));
        httpsView = text("", 15, false);
        httpsView.setTypeface(Typeface.MONOSPACE);
        httpsView.setPadding(0, dp(4), 0, dp(4));
        l.addView(httpsView);
        certView = text("", 12, false);
        certView.setTypeface(Typeface.MONOSPACE);
        l.addView(certView);
        l.addView(text("The certificate was generated on this TV when the app first ran; its private key never leaves the device. Browsers and apps will warn that it is not trusted: accept it once, or compare the fingerprint above with the one shown in the warning. With curl use -k.", 12, false));

        // usage
        l.addView(heading("How to use"));
        l.addView(text("Intended flow: find a video in an app such as PipePipe on your phone, tap Share, pick a sender, and the video starts on this TV in SmartTube. Three ways to send:\n\n"
          + "• S-Tube Drop Sender, the companion app for Android phones: share a link to it. Enter this TV's IP, the port and the password once, and trust the certificate.\n"
          + "• The S-Tube Drop Firefox / LibreWolf extension: click its button in the address bar on a YouTube video page.\n"
          + "• HTTP Shortcuts, a general-purpose Android app (free, open source): setup below.", 14, false));
        howView = text("", 14, false);
        howView.setPadding(0, dp(8), 0, 0);
        l.addView(howView);

        // target app
        l.addView(heading("Target app"));
        l.addView(text("Package name of the SmartTube build that should open the links.", 14, false));
        pkg = new EditText(this);
        pkg.setSingleLine();
        pkg.setText(p.getString("pkg", ServerService.DEFAULT_PKG));
        l.addView(pkg);
        Button fdroid = button("F-Droid build"), play = button("Play Store build");
        LinearLayout prow = new LinearLayout(this);
        prow.addView(fdroid); prow.addView(play);
        l.addView(prow);
        fdroid.setOnClickListener(v -> pkg.setText("app.smarttube.fdroid"));
        play.setOnClickListener(v -> pkg.setText("com.teamsmart.videomanager.tv"));

        // log
        l.addView(heading("Recent drops (last 5, memory only)"));
        logView = text("", 13, false);
        logView.setTypeface(Typeface.MONOSPACE);
        l.addView(logView);
        l.addView(text("Three rejected requests within 60 s from one address block it silently for 4 minutes.", 12, false));

        // permission
        l.addView(heading("Launch permission"));
        l.addView(text("Android only lets background apps open other apps if 'Display over other apps' is allowed for S-Tube Drop. Required once.", 14, false));
        Button overlay = button("Open permission settings");
        l.addView(overlay);

        save.setOnClickListener(v -> {
            int pn;
            try { pn = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { pn = 0; }
            if (pn < 1024 || pn > 65535) { status.setText("Port must be between 1024 and 65535"); return; }
            String tk = token.getText().toString().trim();
            if (tk.isEmpty()) { status.setText("Password must not be empty"); return; }
            String pk = pkg.getText().toString().trim();
            if (!pk.matches("[A-Za-z0-9_.]+")) { status.setText("Invalid package name"); return; }
            p.edit().putString("pkg", pk).putInt("port", pn).putString("token", tk).putBoolean("enabled", true).apply();
            startForegroundService(new Intent(this, ServerService.class));
            refresh();
        });
        stop.setOnClickListener(v -> {
            p.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, ServerService.class));
            refresh();
        });
        overlay.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { status.setText("Enable 'Display over other apps' for S-Tube Drop in system settings"); }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);

        // start automatically on first launch / whenever it was left enabled
        if (p.getBoolean("enabled", true)) startForegroundService(new Intent(this, ServerService.class));
        refresh();
    }

    @Override protected void onResume() {
        super.onResume(); refresh(); showLog();
        DropLog.setListener(() -> runOnUiThread(this::showLog));
    }
    @Override protected void onPause() { DropLog.setListener(null); super.onPause(); }

    private void showLog() {
        String l = DropLog.text();
        logView.setText(l.isEmpty() ? "(nothing yet)" : l);
    }

    private void refresh() {
        boolean on = p.getBoolean("enabled", true);
        status.setText(on ? "● Running on port " + p.getInt("port", 8765) : "○ Stopped");
        urlView.setText(dropUrl("http"));
        httpsView.setText(dropUrl("https"));
        certView.setText("Certificate fingerprint (SHA-256):\n" + TlsSupport.fingerprint());
        String base = "http://" + localIp() + ":" + p.getInt("port", 8765) + "/";
        howView.setText(
            "Setting up HTTP Shortcuts on your phone (once):\n\n"
          + "1. Open Variables and add a variable of type Text named  link . Enable the option that lets the Share dialog set its value, and disable 'Remember value'.\n\n"
          + "2. Create a shortcut with these settings (POST, recommended):\n"
          + "   • Method: POST\n"
          + "   • URL: " + base + "\n"
          + "   • Headers: add  X-Token  with the value  " + p.getString("token", "changeme") + "\n"
          + "   • Request body: Custom text, content type text/plain, containing only  {link}  (insert the variable). No escaping needed.\n\n"
          + "   Or with GET:\n"
          + "   • Method: GET\n"
          + "   • URL: " + base + "\n"
          + "   • Request parameters:  url = {link}  and  t = " + p.getString("token", "changeme") + "\n"
          + "   HTTP Shortcuts escapes parameter values for you.\n\n"
          + "3. In PipePipe (or the YouTube app, a browser…) open a video, tap Share, choose HTTP Shortcuts, then your shortcut.\n\n"
          + "The URL above is plain HTTP. HTTPS needs HTTP Shortcuts to accept this TV's self-signed certificate; the S-Tube Drop Sender app and the extension handle that for you.\n\n"
          + "Notes: only youtube.com, youtu.be and music.youtube.com links are accepted. Give the TV a fixed IP in your router, or the shortcut stops working when the address changes. The phone must be on the same network.");
    }

    private String dropUrl(String scheme) {
        String base = scheme + "://" + localIp() + ":" + p.getInt("port", 8765) + "/";
        String tk = p.getString("token", "changeme");
        return "POST " + base + "\nX-Token: " + tk + "\nbody: https://youtu.be/VIDEO_ID\n\nGET " + base + "?url=https://youtu.be/VIDEO_ID&t=" + tk;
    }

    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (ni.isUp() && !ni.isLoopback())
                    for (InetAddress a : Collections.list(ni.getInetAddresses()))
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
        } catch (Exception ignored) {}
        return "<tv-ip>";
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }
    private TextView heading(String s) {
        TextView t = text(s, 20, true);
        t.setPadding(0, dp(24), 0, dp(4));
        t.setTextColor(0xFFFF6B75);
        return t;
    }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
}
