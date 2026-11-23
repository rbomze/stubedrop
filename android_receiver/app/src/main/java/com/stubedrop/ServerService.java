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
            if (first < 0x20 || first > 0x7E) {
                // binary data, typically a TLS handshake: a client is using https:// on this plain-HTTP port.
                // Ignore it silently and do NOT count it as a failed login.
                DropLog.add(ip + "  ⚠ ignored non-HTTP data (https:// used on the plain-http port?)");
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(bin));
            String line = r.readLine(); // GET /?url=...&t=... HTTP/1.1
            if (line == null) return;
            int code = 403; String body = "";
            String reason = "malformed request: [" + line.replaceAll("[^\\x20-\\x7E]", "?").substring(0, Math.min(line.length(), 80)) + "]";
            String[] parts = line.split(" ");
            if (parts.length >= 2) {
                Uri u = Uri.parse("http://x" + parts[1]);
                String url = u.getQueryParameter("url"), t = u.getQueryParameter("t");
                reason = !token.equals(t) ? "bad password" : (url == null || !YT.matcher(url).matches()) ? "not a YouTube link: " + url : "";
                if (reason.isEmpty()) {
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
                boolean blocked = recordFailure(ip);
                DropLog.add(ip + "  ✗ rejected (" + reason + ")" + (blocked ? " – blocked for 4 min" : ""));
                toast("S-Tube Drop: rejected request from " + ip);
            }
            String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Error";
            OutputStream o = sock.getOutputStream();
            o.write(("HTTP/1.1 " + code + " " + status + "\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body).getBytes());
            o.flush();
        } catch (IOException ignored) {}
    }

    private void stopServer() {
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    @Override public void onDestroy() { stopServer(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
