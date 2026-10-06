package org.rama.ftp;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class FtpServiceTest {

    @Mock private FtpConnectionManager connectionManager;
    @Mock private FTPClient client;

    private FtpService ftpService;
    private final List<byte[]> storedPayloads = new ArrayList<>();

    /** Mirrors FtpConnection.withClient: replays the lambda once after an IOException. */
    private final FtpConnection replayingConnection = new FtpConnection(new FtpProperties.Server()) {
        @Override
        public <T> T withClient(IOFunction<FTPClient, T> fn) {
            try {
                return fn.apply(client);
            } catch (IOException e) {
                try {
                    return fn.apply(client);
                } catch (IOException e2) {
                    throw new RuntimeException("FTP error: " + e2.getMessage(), e2);
                }
            }
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        FtpProperties props = new FtpProperties();
        FtpProperties.Server server = new FtpProperties.Server();
        server.setEncoding("UTF-8");
        props.setServers(Map.of("lis", server));
        ftpService = new FtpService(connectionManager, props);

        when(connectionManager.get("lis")).thenReturn(replayingConnection);
        when(client.changeWorkingDirectory(anyString())).thenReturn(true);
        lenient().when(client.getReplyString()).thenReturn("451 transient");

        // first STOR consumes the stream and fails; the replay succeeds with whatever it is given
        when(client.storeFile(eq("order.hl7"), any(InputStream.class))).thenAnswer(inv -> {
            byte[] read = inv.getArgument(1, InputStream.class).readAllBytes();
            storedPayloads.add(read);
            return storedPayloads.size() > 1;
        });
    }

    @Test
    void upload_whenFirstStoreFailsAndIsReplayed_shouldResendFullPayload() {
        byte[] payload = "MSH|^~\\&|HIS|RAMA|LIS|RAMA\r".getBytes(StandardCharsets.UTF_8);

        ftpService.upload("lis", "/out", "order.hl7", new ByteArrayInputStream(payload), false);

        assertThat(storedPayloads).hasSize(2);
        assertThat(storedPayloads.get(1)).isEqualTo(payload);
    }

    @Test
    void writeText_whenFirstStoreFailsAndIsReplayed_shouldResendFullPayload() {
        String content = "MSH|^~\\&|HIS|RAMA|LIS|RAMA\r";

        ftpService.writeText("lis", "/out", "order.hl7", content, false, StandardCharsets.UTF_8);

        assertThat(storedPayloads).hasSize(2);
        assertThat(new String(storedPayloads.get(1), StandardCharsets.UTF_8)).isEqualTo(content);
    }
}
