package org.rama.ftp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "ftp")
@Getter
@Setter
public class FtpProperties {
    private Map<String, Server> servers = new HashMap<>();

    @Getter
    @Setter
    public static class Server {
        private String host;
        private int port = 21;
        private String username;
        private String password;
        private boolean passiveMode = true;
        private boolean binaryFileType = true;
        private int bufferSize = 32 * 1024;
        private int connectTimeoutMillis = 8000;
        private int dataTimeoutMillis = 15000;
        private String encoding = "UTF-8";
        private String outboundFolder = "/outbound";
        private String inboundFolder = "/inbound";
        private String inboundBackupFolder = "./logs";
    }
}
