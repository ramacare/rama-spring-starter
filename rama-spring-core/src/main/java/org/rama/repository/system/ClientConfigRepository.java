package org.rama.repository.system;

import org.rama.entity.system.ClientConfig;
import org.rama.repository.BaseRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.List;
import java.util.Optional;

@GraphQlRepository
public interface ClientConfigRepository extends BaseRepository<ClientConfig, Long>, QuerydslPredicateExecutor<ClientConfig> {
    Optional<ClientConfig> findByComputerName(String computerName);
    List<ClientConfig> findByFingerprint(String fingerprint);
    boolean existsByComputerName(String computerName);
    boolean existsByFingerprint(String fingerprint);
}
