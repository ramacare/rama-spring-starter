package org.rama.repository.system;

import org.rama.entity.system.UserConfig;
import org.rama.repository.BaseRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.Optional;

@GraphQlRepository
public interface UserConfigRepository extends BaseRepository<UserConfig, Long>, QuerydslPredicateExecutor<UserConfig> {
    Optional<UserConfig> findByUsername(String username);
    boolean existsByUsername(String username);
}
