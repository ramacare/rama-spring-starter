package org.rama.repository.system;

import org.rama.entity.system.ClientUserConfig;
import org.rama.repository.BaseRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.Optional;

@GraphQlRepository
public interface ClientUserConfigRepository extends BaseRepository<ClientUserConfig, Long>, QuerydslPredicateExecutor<ClientUserConfig> {
    Optional<ClientUserConfig> findByClientUsername(String clientUsername);
    boolean existsByClientUsername(String clientUsername);
}
