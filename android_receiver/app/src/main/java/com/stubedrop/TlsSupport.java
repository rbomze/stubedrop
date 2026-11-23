package com.stubedrop;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/**
 * Self-signed server certificate, generated locally on first use. The private key
 * lives in the Android Keystore and never leaves the device.
 */
public class TlsSupport {
    private static final String ALIAS = "stubedrop-tls";
    private static SSLContext ctx;

    /** Creates the key pair + self-signed certificate if missing or expired. */
    static synchronized void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            try { ((X509Certificate) ks.getCertificate(ALIAS)).checkValidity(); return; }
            catch (Exception expired) { ks.deleteEntry(ALIAS); ctx = null; }
        }
        long now = System.currentTimeMillis();
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        g.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(new X500Principal("CN=S-Tube Drop"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(new Date(now - 24L * 3600 * 1000))
            .setCertificateNotAfter(new Date(now + 3650L * 24 * 3600 * 1000))
            .build());
        g.generateKeyPair();
    }

    static synchronized SSLContext context() throws Exception {
        ensureKey();
        if (ctx != null) return ctx;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, null);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return ctx = c;
    }

    /** SHA-256 fingerprint of the certificate, as shown in browsers' certificate dialogs. */
    static String fingerprint() {
        try {
            ensureKey();
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(ALIAS).getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(i > 0 ? ":" : "").append(String.format("%02X", d[i]));
            return sb.toString();
        } catch (Exception e) { return "unavailable (" + e.getMessage() + ")"; }
    }
}
