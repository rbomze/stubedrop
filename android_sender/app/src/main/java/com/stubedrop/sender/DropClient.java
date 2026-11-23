package com.stubedrop.sender;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.*;
import javax.net.ssl.*;

/**
 * Talks to the S-Tube Drop receiver. Plain Java (no Android classes) so it can be tested on a PC.
 *
 * HTTPS uses the receiver's self-signed certificate, trusted by pinning: the user confirms the
 * certificate's SHA-256 fingerprint once per ip:port, after which only exactly that certificate
 * is accepted.
 */
public class DropClient {
    public static final String TEST_URL = "testurl";

    public static class Config {
        public String ip = ""; public int port = 8765; public String token = "changeme";
        public boolean https = true;
        public String pinKey = "", pinFp = "";   // the ip:port and fingerprint the user trusted
    }

    public enum Kind { OK, CONFIG, TRUST, CERT_CHANGED, AUTH, BAD_LINK, UNREACHABLE, OTHER }

    public static class Result {
        public final Kind kind; public final String msg;
        Result(Kind k, String m) { kind = k; msg = m; }
        public boolean ok() { return kind == Kind.OK; }
    }

    public static String trustKey(Config c) { return c.ip + ":" + c.port; }
    public static boolean validHost(String h) { return h != null && h.matches("[A-Za-z0-9.-]+"); }
    public static boolean validPort(int p) { return p >= 1 && p <= 65535; }

    /** HTTPS is on but this ip:port has no trusted certificate yet. */
    public static boolean needsTrust(Config c) {
        return c.https && !(trustKey(c).equals(c.pinKey) && c.pinFp != null && !c.pinFp.isEmpty());
    }

    public static String buildUrl(Config c, String link) {
        return (c.https ? "https" : "http") + "://" + (c.ip.isEmpty() ? "<tv-ip>" : c.ip) + ":" + c.port
            + "/?url=" + enc(link == null || link.isEmpty() ? "https://youtu.be/VIDEO_ID" : link) + "&t=" + enc(c.token);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
    }

    // ---- certificate pinning ----------------------------------------------------------------

    static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(i > 0 ? ":" : "").append(String.format("%02X", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new CertificateException(e); }
    }

    static class PinMismatch extends CertificateException {
        PinMismatch(String m) { super(m); }
    }

    /** Accepts only the certificate with this fingerprint. */
    static class PinnedTrust implements X509TrustManager {
        private final String fp;
        PinnedTrust(String fp) { this.fp = fp; }
        public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("client"); }
        public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            if (chain == null || chain.length == 0 || !fingerprint(chain[0]).equalsIgnoreCase(fp))
                throw new PinMismatch("certificate does not match the trusted fingerprint");
        }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    /** Accepts anything and remembers the server certificate (used only to show its fingerprint). */
    static class CaptureTrust implements X509TrustManager {
        volatile X509Certificate cert;
        public void checkClientTrusted(X509Certificate[] c, String a) {}
        public void checkServerTrusted(X509Certificate[] chain, String auth) { cert = chain[0]; }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static SSLContext context(TrustManager tm) throws Exception {
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, new TrustManager[]{tm}, null);
        return c;
    }

    /** Connects (TLS handshake only, no request) and returns the server certificate's SHA-256 fingerprint. */
    public static String fetchFingerprint(String ip, int port) throws IOException {
        try {
            CaptureTrust tm = new CaptureTrust();
            try (SSLSocket s = (SSLSocket) context(tm).getSocketFactory().createSocket()) {
                s.connect(new InetSocketAddress(ip, port), 5000);
                s.setSoTimeout(5000);
                s.startHandshake();
            }
            if (tm.cert == null) throw new IOException("no certificate received");
            return fingerprint(tm.cert);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException(e); }
    }

    // ---- sending ----------------------------------------------------------------------------

    /** Sends a YouTube link (or TEST_URL) to the receiver. */
    public static Result send(Config c, String link) {
        if (!validHost(c.ip)) return new Result(Kind.CONFIG, "Enter the TV's IP address to get started.");
        if (!validPort(c.port)) return new Result(Kind.CONFIG, "The port is invalid (1–65535).");
        if (needsTrust(c)) return new Result(Kind.TRUST, "Trust the TV's certificate first: tap “Trust certificate…” and confirm the fingerprint.");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(buildUrl(c, link)).openConnection();
            if (h instanceof HttpsURLConnection) {
                HttpsURLConnection s = (HttpsURLConnection) h;
                s.setSSLSocketFactory(context(new PinnedTrust(c.pinFp)).getSocketFactory());
                s.setHostnameVerifier((host, session) -> true); // the pinned certificate is the identity
            }
            h.setConnectTimeout(5000);
            h.setReadTimeout(6000);
            int code = h.getResponseCode();
            String body = read(code >= 400 ? h.getErrorStream() : h.getInputStream());
            h.disconnect();
            if (code == 200) return new Result(Kind.OK, TEST_URL.equals(link) ? "Connected: the TV accepted the password." : "Sent to TV ▶");
            if (code == 403 && body.startsWith("bad password")) return new Result(Kind.AUTH, "The TV rejected the password. Check it matches the one in the S-Tube Drop receiver app. Repeated failures get this phone blocked for 4 minutes.");
            if (code == 403) return new Result(Kind.BAD_LINK, "The TV did not accept this link as a YouTube video link.");
            return new Result(Kind.OTHER, "The TV answered with HTTP " + code + (body.isEmpty() ? "" : ": " + body));
        } catch (SSLException e) {
            for (Throwable t = e; t != null; t = t.getCause())
                if (t instanceof PinMismatch)
                    return new Result(Kind.CERT_CHANGED, "The TV's certificate changed (receiver app reinstalled?). Trust it again.");
            return new Result(Kind.UNREACHABLE, "Secure connection to " + trustKey(c) + " failed: " + e.getMessage());
        } catch (Exception e) {
            return new Result(Kind.UNREACHABLE, "TV not reachable at " + trustKey(c) + ". Is it on, and is the S-Tube Drop receiver app running?");
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[2048]; int n, total = 0;
        while ((n = in.read(b)) > 0 && (total += n) < 4096) o.write(b, 0, n);
        in.close();
        return o.toString(StandardCharsets.UTF_8.name());
    }
}
