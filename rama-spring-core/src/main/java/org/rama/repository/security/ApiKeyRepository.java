package org.rama.repository.security;

import org.rama.entity.security.ApiKey;
import org.rama.repository.BaseRepository;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.Optional;

@GraphQlRepository
public interface ApiKeyRepository extends BaseRepository<ApiKey, Long> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    Optional<ApiKey> findByName(String name);
}
