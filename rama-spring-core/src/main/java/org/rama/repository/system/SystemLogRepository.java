package org.rama.repository.system;

import org.rama.entity.system.SystemLog;
import org.rama.repository.BaseRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface SystemLogRepository extends BaseRepository<SystemLog, Long>, QuerydslPredicateExecutor<SystemLog> {
}
