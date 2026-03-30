package org.rama.service.system;

import org.rama.entity.system.ClientConfig;
import org.rama.entity.system.SystemLog;
import org.rama.repository.system.ClientConfigRepository;
import org.rama.repository.system.SystemLogRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ClientConfigService {
    private final ClientConfigRepository clientConfigRepository;
    private final SystemLogRepository systemLogRepository;

    public ClientConfigService(ClientConfigRepository clientConfigRepository, SystemLogRepository systemLogRepository) {
        this.clientConfigRepository = clientConfigRepository;
        this.systemLogRepository = systemLogRepository;
    }

    @Transactional
    public ClientConfig retrieveOrRegister(String computerName, String fingerprint) {
        if (computerName == null || computerName.isBlank()) {
            throw new IllegalArgumentException("computerName is required");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint is required");
        }

        OffsetDateTime now = OffsetDateTime.now();
        Optional<ClientConfig> byComputerName = clientConfigRepository.findByComputerName(computerName);
        if (byComputerName.isPresent()) {
            ClientConfig client = byComputerName.get();
            handleFingerprintChangeIfNeeded(client, fingerprint, computerName);
            client.setLastSeenDatetime(now);
            return clientConfigRepository.save(client);
        }

        List<ClientConfig> byFingerprint = clientConfigRepository.findByFingerprint(fingerprint);
        if (byFingerprint.size() > 1) {
            writeSystemLog(SystemLog.LogLevel.ERROR, "CLIENT_FINGERPRINT_DUPLICATE", "Multiple ClientConfig rows found with the same fingerprint", Map.of("fingerprint", fingerprint, "count", byFingerprint.size()));
            throw new IllegalStateException("Duplicate fingerprint detected: " + fingerprint);
        }

        if (byFingerprint.size() == 1) {
            ClientConfig client = byFingerprint.get(0);
            client.setLastSeenDatetime(now);
            return clientConfigRepository.save(client);
        }

        ClientConfig created = new ClientConfig();
        created.setComputerName(computerName);
        created.setFingerprint(fingerprint);
        created.setLastSeenDatetime(now);
        created.setConfiguration(new HashMap<>());
        return clientConfigRepository.save(created);
    }

    private void handleFingerprintChangeIfNeeded(ClientConfig client, String newFingerprint, String computerName) {
        String oldFingerprint = client.getFingerprint();
        boolean changed = oldFingerprint != null && !oldFingerprint.isBlank() && !oldFingerprint.equals(newFingerprint);
        if (changed) {
            client.setFingerprint(newFingerprint);
            writeSystemLog(SystemLog.LogLevel.WARN, "CLIENT_FINGERPRINT_CHANGED", "Client fingerprint changed for computerName", Map.of("computerName", computerName, "oldFingerprint", oldFingerprint, "newFingerprint", newFingerprint, "clientConfigId", client.getId()));
        } else if (oldFingerprint == null || oldFingerprint.isBlank()) {
            client.setFingerprint(newFingerprint);
        }
    }

    private void writeSystemLog(SystemLog.LogLevel level, String logKey, String message, Map<String, Object> detail) {
        SystemLog log = new SystemLog();
        log.setLogLevel(level);
        log.setLogKey(logKey);
        log.setMessage(message);
        log.setDetail(detail);
        systemLogRepository.save(log);
    }
}
