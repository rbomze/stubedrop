package com.stubedrop;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.Toast;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Pattern;

public class ServerService extends Service {
    static final String DEFAULT_PKG = "app.smarttube.fdroid";
    static final String TEST_PAGE = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        + "<title>S-Tube Drop</title><style>body{font:18px system-ui,sans-serif;background:#161a2e;color:#e8eaf5;display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center;text-align:center}"
        + "div{max-width:30em;padding:1.5em}h1{color:#6be08a;margin:0 0 .4em}p{margin:.4em 0;color:#9aa0c0}</style></head><body><div>"
        + "<h1>\u2713 It works</h1><p>S-Tube Drop is reachable and accepted the password.</p>"
        + "<p>You can close this tab now.</p></div></body></html>";
    static final Pattern YT = Pattern.compile("https://(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/.*");

    // rate limiting: 3 failures within 60 s -> silently ignore that IP for 240 s
    static final int MAX_FAILS = 3;
    static final long WINDOW_MS = 60_000, BLOCK_MS = 240_000;
    private final Map<String, ArrayDeque<Long>> fails = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile ServerSocket server;
    private SharedPreferences prefs;
    private String token;

    @Override public int onStartCommand(Intent i, int flags, int id) {
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        int port = prefs.getInt("port", 8765);
        token = prefs.getString("token", "changeme");
        startForeground(1, notification(port));
        stopServer();
        new Thread(() -> run(port), "http").start();
        return START_STICKY;
    }

    private Notification notification(int port) {
        String ch = "stubedrop";
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(ch, "S-Tube Drop", NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        return b.setContentTitle("S-Tube Drop is running").setContentText("Listening on port " + port)
            .setSmallIcon(R.drawable.ic_stat).setOngoing(true).build();
    }

    private void run(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            server = s;
            while (!s.isClosed()) {
                Socket c = s.accept();
                new Thread(() -> handle(c)).start();
            }
        } catch (IOException e) { /* closed or bind failure */ }
    }

    private synchronized boolean isBlocked(String ip) {
        Long until = blockedUntil.get(ip);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        blockedUntil.remove(ip);
        return false;
    }

    /** Records a failure; returns true if this failure triggers a block. */
    private synchronized boolean recordFailure(String ip) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> q = fails.get(ip);
        if (q == null) { q = new ArrayDeque<>(); fails.put(ip, q); }
        q.add(now);
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.removeFirst();
        if (q.size() >= MAX_FAILS) {
            blockedUntil.put(ip, now + BLOCK_MS);
            fails.remove(ip);
            return true;
        }
        return false;
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            String ip = sock.getInetAddress().getHostAddress();
            if (isBlocked(ip)) return; // silent: close without any response

            sock.setSoTimeout(5000);
            BufferedInputStream bin = new BufferedInputStream(sock.getInputStream());
            bin.mark(1);
            int first = bin.read();
            if (first < 0) return;
            bin.reset();
            Conn conn;
            if (first == 0x16) { // TLS ClientHello: serve https on the same port
                try {
                    Conn.Tls t = new Conn.Tls(TlsSupport.context(), bin, sock.getOutputStream());
                    t.handshake();
                    conn = t;
                } catch (Exception e) {
                    // typically the browser rejected the self-signed certificate; not a failed login
                    DropLog.add(ip + "  ⚠ TLS handshake failed (certificate not accepted yet?)");
                    return;
                }
            } else if (first < 0x20 || first > 0x7E) {
                DropLog.add(ip + "  ⚠ ignored non-HTTP data");
                return;
            } else {
                conn = new Conn.Plain(bin, sock.getOutputStream());
            }
            String line = conn.readLine(); // GET /?url=...&t=... HTTP/1.1
            if (line == null) { conn.close(); return; }
            int code = 403; String body = "", ctype = "text/plain";
            String reason = "malformed request: [" + line.replaceAll("[^\\x20-\\x7E]", "?").substring(0, Math.min(line.length(), 80)) + "]";
            String[] parts = line.split(" ");
            if (parts.length >= 2 && parts[1].startsWith("/favicon")) {
                // browsers fetch this on their own after opening a page: not a drop, not a failure
                conn.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                conn.close();
                return;
            }
            if (parts.length >= 1 && parts[0].equalsIgnoreCase("OPTIONS")) {
                // CORS preflight from a browser extension sending the X-Token header: not a drop, not a failure
                conn.write(("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST\r\n"
                    + "Access-Control-Allow-Headers: X-Token, Content-Type\r\nAccess-Control-Max-Age: 600\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                conn.close();
                return;
            }
            if (parts.length >= 2) {
                Uri u = Uri.parse("http://x" + parts[1]);
                String url = u.getQueryParameter("url"), t = u.getQueryParameter("t");
                if (parts[0].equalsIgnoreCase("POST")) {
                    // the link travels in the body, so it needs no URL-escaping: either the bare link
                    // (text/plain) or a form body "url=..." (application/x-www-form-urlencoded);
                    // the password travels in the X-Token header, so it never appears in a URL
                    int len = 0; boolean form = false;
                    t = null;
                    String h;
                    while ((h = conn.readLine()) != null && !h.isEmpty()) {
                        String lh = h.toLowerCase(Locale.ROOT);
                        if (lh.startsWith("x-token:")) t = h.substring(8).trim();
                        else if (lh.startsWith("content-length:")) {
                            try { len = Integer.parseInt(h.substring(15).trim()); } catch (NumberFormatException ignored) {}
                        } else if (lh.startsWith("content-type:") && lh.contains("application/x-www-form-urlencoded")) form = true;
                    }
                    String body0 = new String(conn.readBody(Math.max(0, Math.min(len, 4096))).getBytes("ISO-8859-1"), "UTF-8").trim();
                    if (form) {
                        Uri f = Uri.parse("http://x/?" + body0);
                        if (f.getQueryParameter("url") != null) url = f.getQueryParameter("url");
                    } else if (!body0.isEmpty()) url = body0;
                }
                boolean isTest = token.equals(t) && "testurl".equals(url);
                reason = isTest ? "" : !token.equals(t) ? "bad password" : (url == null || !YT.matcher(url).matches()) ? "not a YouTube link: " + url : "";
                if (isTest) {
                    // connection/password/certificate check: never starts a video
                    code = 200; ctype = "text/html; charset=utf-8"; body = TEST_PAGE;
                    DropLog.add(ip + "  ✓ connection test");
                } else if (reason.isEmpty()) {
                    String pkg = prefs.getString("pkg", DEFAULT_PKG);
                    Intent in = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(in); code = 200; body = "ok";
                        DropLog.add(ip + "  ▶ " + url);
                        toast("S-Tube Drop: playing " + url);
                    } catch (Exception e) {
                        code = 500; body = "launch failed";
                        DropLog.add(ip + "  ✗ could not launch " + pkg);
                        toast("S-Tube Drop: could not launch " + pkg);
                    }
                }
            }
            if (code == 403) {
                body = reason.startsWith("bad password") ? "bad password" : "bad url";
                // a correct password with a bad link (e.g. the browser extension's connection test)
                // is not a guessing attempt, so it does not count towards the block
                boolean blocked = !reason.startsWith("not a YouTube link") && recordFailure(ip);
                DropLog.add(ip + "  ✗ rejected (" + reason + ")" + (blocked ? " – blocked for 4 min" : ""));
                toast("S-Tube Drop: rejected request from " + ip);
            }
            String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Error";
            byte[] payload = body.getBytes("UTF-8");
            conn.write(("HTTP/1.1 " + code + " " + status + "\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: " + ctype
                + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            if (payload.length > 0) conn.write(payload);
            conn.close();
        } catch (IOException ignored) {}
    }

    private void stopServer() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    @Override public void onDestroy() { stopServer(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
