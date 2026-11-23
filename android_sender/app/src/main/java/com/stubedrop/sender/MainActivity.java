package com.stubedrop.sender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    static final String EXTRA_WARNING = "warning";

    private SharedPreferences p;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText ip, port, token;
    private CheckBox https;
    private TextView warn, certStatus, urlView, result;
    private Button trust;
    private DropClient.Config cfg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Store.prefs(this);
        cfg = Store.load(this);

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(20), dp(20), dp(28));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("S-Tube Drop Sender", 24, true);
        title.setPadding(dp(14), 0, 0, 0);
        head.addView(title);
        l.addView(head);

        warn = text("", 14, true);
        warn.setTextColor(0xFFFFB3B8);
        warn.setBackgroundColor(0xFF4A1C26);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        warn.setVisibility(View.GONE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(14);
        l.addView(warn, wl);

        l.addView(text("Share a YouTube link from any app (e.g. PipePipe) and pick “S-Tube Drop Sender”: it is sent straight to SmartTube on your TV.", 14, false));

        l.addView(heading("Target"));
        ip = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, cfg.ip, "e.g. 192.168.1.50");
        port = field(InputType.TYPE_CLASS_NUMBER, String.valueOf(cfg.port), "8765");
        token = field(InputType.TYPE_CLASS_TEXT, cfg.token, "changeme");
        addField(l, "TV IP address", "The address of the TV. It is shown in the S-Tube Drop receiver app on the TV; give the TV a fixed IP in your router.", ip);
        addField(l, "Port", "The port the receiver app listens on (default 8765). Must match the receiver app.", port);
        addField(l, "Password / token", "The shared secret (default “changeme”). Must match the receiver app. Change it from the default.", token);

        https = new CheckBox(this);
        https.setText("Use HTTPS (recommended: encrypts the password)");
        https.setChecked(cfg.https);
        l.addView(https);

        certStatus = text("", 14, true);
        l.addView(certStatus);
        trust = button("Trust certificate…");
        l.addView(trust);
        l.addView(text("The TV's certificate is self-signed. Tap the button, then compare the fingerprint shown with the one in the receiver app on the TV and confirm. Required once for each address.", 12, false));

        Button test = button("Test connection");
        l.addView(test);
        result = text("", 14, true);
        l.addView(result);

        l.addView(heading("Effective URL"));
        urlView = text("", 14, false);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextIsSelectable(true);
        l.addView(urlView);

        l.addView(heading("Send from clipboard"));
        l.addView(text("Copied a YouTube link instead of sharing it? Send it from here.", 14, false));
        Button clip = button("Send link from clipboard");
        l.addView(clip);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);

        TextWatcher watch = new SimpleWatcher(this::onEdit);
        ip.addTextChangedListener(watch); port.addTextChangedListener(watch); token.addTextChangedListener(watch);
        https.setOnCheckedChangeListener((v, on) -> onEdit());
        trust.setOnClickListener(v -> trustCertificate());
        test.setOnClickListener(v -> run(DropClient.TEST_URL));
        clip.setOnClickListener(v -> sendClipboard());

        refresh();
        String w = getIntent().getStringExtra(EXTRA_WARNING);
        if (w != null) showWarning(w);
        else if (cfg.https && DropClient.validHost(cfg.ip) && DropClient.needsTrust(cfg)) showWarning("The TV's certificate isn't trusted yet. Tap “Trust certificate…” to continue.");
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        String w = i.getStringExtra(EXTRA_WARNING);
        if (w != null) { cfg = Store.load(this); refresh(); showWarning(w); }
    }

    // ---- editing ----

    private void onEdit() {
        cfg.ip = ip.getText().toString().trim();
        try { cfg.port = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { cfg.port = 0; }
        cfg.token = token.getText().toString().trim();
        cfg.https = https.isChecked();
        p.edit().putString("ip", cfg.ip).putInt("port", cfg.port).putString("token", cfg.token).putBoolean("https", cfg.https).apply();
        result.setText("");
        showWarning(cfg.https && DropClient.validHost(cfg.ip) && DropClient.needsTrust(cfg)
            ? "The TV's certificate isn't trusted yet. Tap “Trust certificate…” to continue." : "");
        refresh();
    }

    private void refresh() {
        box(ip, !cfg.ip.isEmpty() && !DropClient.validHost(cfg.ip));
        box(port, !DropClient.validPort(cfg.port));
        box(token, false);
        urlView.setText(DropClient.buildUrl(cfg, ""));
        boolean show = cfg.https;
        certStatus.setVisibility(show ? View.VISIBLE : View.GONE);
        trust.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (!DropClient.validHost(cfg.ip)) { certStatus.setText("Enter the TV's IP address first."); certStatus.setTextColor(0xFFAAAAAA); }
            else if (DropClient.needsTrust(cfg)) { certStatus.setText("✗ Certificate of " + DropClient.trustKey(cfg) + " not trusted yet"); certStatus.setTextColor(0xFFFFB86B); }
            else { certStatus.setText("✓ Certificate of " + DropClient.trustKey(cfg) + " trusted"); certStatus.setTextColor(0xFF6BE08A); }
            trust.setEnabled(DropClient.validHost(cfg.ip) && DropClient.validPort(cfg.port));
        }
    }

    private void showWarning(String msg) {
        warn.setText("⚠ " + msg);
        warn.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- actions ----

    /** Connects, shows the certificate fingerprint and lets the user pin it. */
    private void trustCertificate() {
        final String host = cfg.ip; final int pt = cfg.port;
        result.setText("Connecting…"); result.setTextColor(0xFFAAAAAA);
        new Thread(() -> {
            String fp = null, err = null;
            try { fp = DropClient.fetchFingerprint(host, pt); } catch (Exception e) { err = e.getMessage(); }
            final String f = fp, e = err;
            ui.post(() -> {
                if (f == null) {
                    result.setText("✗ No secure connection to " + host + ":" + pt + ". Is the TV on and the receiver app running? " + (e == null ? "" : "(" + e + ")"));
                    result.setTextColor(0xFFFF8A92);
                    return;
                }
                result.setText("");
                new AlertDialog.Builder(this)
                    .setTitle("Trust this certificate?")
                    .setMessage(host + ":" + pt + "\n\nSHA-256 fingerprint:\n" + f
                        + "\n\nOnly continue if it matches the fingerprint shown in the S-Tube Drop receiver app on your TV.")
                    .setPositiveButton("Trust", (d, w) -> {
                        p.edit().putString("pinKey", host + ":" + pt).putString("pinFp", f).apply();
                        cfg = Store.load(this);
                        showWarning("");
                        refresh();
                        run(DropClient.TEST_URL);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            });
        }).start();
    }

    private void sendClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String link = null;
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            link = LinkExtractor.extract(t == null ? null : t.toString());
        }
        if (link == null) { setResult(false, "No YouTube link found in the clipboard."); return; }
        run(link);
    }

    /** Sends a link (or the test placeholder) and shows the outcome here. */
    private void run(String link) {
        cfg = Store.load(this);
        result.setText("Sending…"); result.setTextColor(0xFFAAAAAA);
        final DropClient.Config c = cfg;
        new Thread(() -> {
            DropClient.Result r = DropClient.send(c, link);
            ui.post(() -> {
                setResult(r.ok(), r.msg);
                if (r.kind == DropClient.Kind.CERT_CHANGED) {
                    p.edit().remove("pinKey").remove("pinFp").apply();
                    cfg = Store.load(this); refresh();
                    showWarning(r.msg);
                } else if (r.kind == DropClient.Kind.TRUST || r.kind == DropClient.Kind.CONFIG || r.kind == DropClient.Kind.AUTH) showWarning(r.msg);
                else if (r.ok()) showWarning("");
            });
        }).start();
    }

    private void setResult(boolean ok, String msg) {
        result.setText((ok ? "✓ " : "✗ ") + msg);
        result.setTextColor(ok ? 0xFF6BE08A : 0xFFFF8A92);
    }

    // ---- small view helpers ----

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, dp(22), 0, dp(4));
        t.setTextColor(0xFFFF6B75);
        return t;
    }

    private EditText field(int type, String value, String hint) {
        EditText e = new EditText(this);
        e.setInputType(type); e.setSingleLine(); e.setText(value); e.setHint(hint);
        e.setHintTextColor(0xFF6E7497);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        box(e, false);
        return e;
    }

    /** Label + short description above a field. */
    private void addField(LinearLayout l, String label, String description, EditText field) {
        TextView t = text(label, 15, true);
        t.setPadding(0, dp(14), 0, 0);
        l.addView(t);
        l.addView(text(description, 12, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        l.addView(field, lp);
    }

    /** A visible, rounded text box; red outline when the value is invalid. */
    private void box(EditText e, boolean bad) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0xFF1E2342);
        d.setCornerRadius(dp(8));
        d.setStroke(dp(bad ? 2 : 1), bad ? 0xFFFF3B47 : 0xFF4B5390);
        e.setBackground(d);
    }

    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }

    private static class SimpleWatcher implements TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
        public void afterTextChanged(Editable e) { r.run(); }
    }
}
