package org.rama.starter.repository.system;

import jakarta.persistence.LockModeType;
import org.rama.starter.entity.system.SystemParameter;
import org.rama.starter.repository.BaseRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SystemParameterRepository extends BaseRepository<SystemParameter, String> {
    SystemParameter findSystemParameterByParameterKey(String parameterKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SystemParameter p where p.parameterKey = :parameterKey")
    Optional<SystemParameter> findByParameterKeyForUpdate(String parameterKey);
}
