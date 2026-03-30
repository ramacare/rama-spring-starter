package org.rama.repository.system;

import jakarta.persistence.LockModeType;
import org.rama.entity.system.SystemParameter;
import org.rama.repository.BaseRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.Optional;

@GraphQlRepository
public interface SystemParameterRepository extends BaseRepository<SystemParameter, String>, QuerydslPredicateExecutor<SystemParameter> {
    SystemParameter findSystemParameterByParameterKey(String parameterKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SystemParameter p where p.parameterKey = :parameterKey")
    Optional<SystemParameter> findByParameterKeyForUpdate(String parameterKey);
}
