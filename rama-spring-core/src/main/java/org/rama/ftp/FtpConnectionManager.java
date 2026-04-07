package org.rama.ftp;

import org.springframework.beans.factory.DisposableBean;

import java.util.HashMap;
import java.util.Map;

public class FtpConnectionManager implements DisposableBean {
    private final Map<String, FtpConnection> map = new HashMap<>();

    public FtpConnectionManager(FtpProperties props) {
        props.getServers().forEach((name, cfg) -> map.put(name, new FtpConnection(cfg)));
    }

    public FtpConnection get(String server) {
        FtpConnection c = map.get(server);
        if (c == null) throw new IllegalArgumentException("Unknown FTP server: " + server);
        return c;
    }

    @Override
    public void destroy() {
        map.values().forEach(FtpConnection::shutdown);
        map.clear();
    }
}
