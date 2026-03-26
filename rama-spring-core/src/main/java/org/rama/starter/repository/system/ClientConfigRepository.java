package org.rama.starter.repository.system;

import org.rama.starter.entity.system.ClientConfig;
import org.rama.starter.repository.BaseRepository;

import java.util.List;
import java.util.Optional;

public interface ClientConfigRepository extends BaseRepository<ClientConfig, Long> {
    Optional<ClientConfig> findByComputerName(String computerName);
    List<ClientConfig> findByFingerprint(String fingerprint);
}
