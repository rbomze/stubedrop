package com.stubedrop;

import java.io.*;
import javax.net.ssl.*;
import java.nio.ByteBuffer;

/** Minimal "read the request line / write the response" connection, plain or TLS. */
interface Conn {
    String readLine() throws IOException;
    /** Reads exactly n body bytes (as ISO-8859-1 chars, i.e. one char per byte). */
    String readBody(int n) throws IOException;
    void write(byte[] b) throws IOException;
    void close();

    class Plain implements Conn {
        private final InputStream in; private final OutputStream o;
        Plain(InputStream in, OutputStream o) { this.in = in; this.o = o; }
        public String readLine() throws IOException { return Conn.line(this::readByte); }
        public String readBody(int n) throws IOException { return Conn.body(this::readByte, n); }
        private int readByte() throws IOException { return in.read(); }
        public void write(byte[] b) throws IOException { o.write(b); o.flush(); }
        public void close() {}
    }

    interface Bytes { int next() throws IOException; }

    static String line(Bytes src) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while (sb.length() < 8192 && (c = src.next()) >= 0) {
            if (c == '\n') return sb.toString().replace("\r", "");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString().replace("\r", "");
    }

    static String body(Bytes src, int n) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < n) {
            int c = src.next();
            if (c < 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    /**
     * Server-side TLS driven by an SSLEngine over streams we control, so the first
     * byte already read (to tell TLS from plain HTTP) is not lost.
     */
    class Tls implements Conn {
        private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
        private final SSLEngine engine; private final InputStream in; private final OutputStream out;
        private final ByteBuffer netIn, appIn;

        Tls(SSLContext ctx, InputStream in, OutputStream out) {
            engine = ctx.createSSLEngine();
            engine.setUseClientMode(false);
            this.in = in; this.out = out;
            SSLSession s = engine.getSession();
            netIn = ByteBuffer.allocate(s.getPacketBufferSize());
            appIn = ByteBuffer.allocate(s.getApplicationBufferSize());
        }

        void handshake() throws IOException {
            engine.beginHandshake();
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            while (hs != SSLEngineResult.HandshakeStatus.FINISHED && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                switch (hs) {
                    case NEED_UNWRAP: hs = unwrap().getHandshakeStatus(); break;
                    case NEED_WRAP: hs = wrap(EMPTY).getHandshakeStatus(); break;
                    case NEED_TASK: {
                        Runnable t;
                        while ((t = engine.getDelegatedTask()) != null) t.run();
                        hs = engine.getHandshakeStatus();
                        break;
                    }
                    default: throw new IOException("unexpected TLS state " + hs);
                }
            }
        }

        private SSLEngineResult unwrap() throws IOException {
            while (true) {
                netIn.flip();
                SSLEngineResult r;
                try { r = engine.unwrap(netIn, appIn); } finally { netIn.compact(); }
                switch (r.getStatus()) {
                    case BUFFER_UNDERFLOW: {
                        int n = in.read(netIn.array(), netIn.position(), netIn.remaining());
                        if (n < 0) throw new EOFException("client closed during TLS");
                        netIn.position(netIn.position() + n);
                        continue;
                    }
                    case BUFFER_OVERFLOW: throw new IOException("TLS application buffer too small");
                    default: return r;
                }
            }
        }

        private SSLEngineResult wrap(ByteBuffer src) throws IOException {
            ByteBuffer o = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
            SSLEngineResult r = engine.wrap(src, o);
            o.flip();
            out.write(o.array(), 0, o.limit());
            out.flush();
            return r;
        }

        private boolean reading; // appIn holds decrypted bytes not yet consumed

        private int readByte() throws IOException {
            while (true) {
                if (reading && appIn.hasRemaining()) return appIn.get() & 0xff;
                appIn.clear(); reading = false;
                if (unwrap().getStatus() == SSLEngineResult.Status.CLOSED) return -1;
                appIn.flip(); reading = true;
            }
        }

        public String readLine() throws IOException { return Conn.line(this::readByte); }
        public String readBody(int n) throws IOException { return Conn.body(this::readByte, n); }

        public void write(byte[] b) throws IOException {
            ByteBuffer src = ByteBuffer.wrap(b);
            while (src.hasRemaining()) wrap(src);
        }

        public void close() {
            try {
                engine.closeOutbound();
                for (int i = 0; i < 4 && !engine.isOutboundDone(); i++) wrap(EMPTY);
            } catch (Exception ignored) {}
        }
    }
}
