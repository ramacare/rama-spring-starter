package org.rama.ftp;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

public class FtpConnection {
    private final FtpProperties.Server cfg;
    private final ReentrantLock lock = new ReentrantLock(true);
    private FTPClient client;

    public FtpConnection(FtpProperties.Server cfg) {
        this.cfg = cfg;
    }

    private void ensureConnected() throws IOException {
        if (client == null || !client.isConnected()) {
            closeQuietly();
            client = new FTPClient();
            client.setAutodetectUTF8(true);
            client.setControlEncoding(StandardCharsets.UTF_8.name());
            client.setConnectTimeout(cfg.getConnectTimeoutMillis());
            client.setDataTimeout(Duration.ofMillis(cfg.getDataTimeoutMillis()));

            client.connect(cfg.getHost(), cfg.getPort());
            if (!client.login(cfg.getUsername(), cfg.getPassword())) {
                closeQuietly();
                throw new IOException("FTP login failed for " + cfg.getHost());
            }

            if (cfg.isPassiveMode()) client.enterLocalPassiveMode();
            else client.enterLocalActiveMode();

            client.setFileType(cfg.isBinaryFileType() ? FTP.BINARY_FILE_TYPE : FTP.ASCII_FILE_TYPE);
            client.setBufferSize(cfg.getBufferSize());
        } else {
            try {
                if (!client.sendNoOp()) throw new IOException("NOOP failed");
            } catch (IOException e) {
                disconnectQuietly();
                ensureConnected();
            }
        }
    }

    public <T> T withClient(IOFunction<FTPClient, T> fn) {
        lock.lock();
        try {
            ensureConnected();
            return fn.apply(client);
        } catch (IOException e) {
            try {
                disconnectQuietly();
                ensureConnected();
                return fn.apply(client);
            } catch (IOException e2) {
                throw new RuntimeException("FTP error: " + e2.getMessage(), e2);
            }
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        lock.lock();
        try { closeQuietly(); } finally { lock.unlock(); }
    }

    private void disconnectQuietly() {
        try { if (client != null && client.isConnected()) client.disconnect(); } catch (IOException ignored) {}
    }

    private void closeQuietly() {
        try {
            if (client != null) {
                try { if (client.isConnected()) client.logout(); } catch (IOException ignored) {}
                try { if (client.isConnected()) client.disconnect(); } catch (IOException ignored) {}
            }
        } finally { client = null; }
    }

    @FunctionalInterface
    public interface IOFunction<T, R> {
        R apply(T t) throws IOException;
    }
}
