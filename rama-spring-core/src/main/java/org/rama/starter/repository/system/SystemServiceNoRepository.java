package org.rama.starter.repository.system;

import org.rama.starter.entity.system.SystemServiceNo;
import org.rama.starter.repository.BaseRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface SystemServiceNoRepository extends BaseRepository<SystemServiceNo, Long> {
    Optional<SystemServiceNo> findFirstByMrnAndTimestampFieldCreatedAtBetweenOrderByTimestampFieldCreatedAtDesc(String mrn, OffsetDateTime startOfDay, OffsetDateTime endOfDay);
    Optional<SystemServiceNo> findFirstByServiceNoAndTimestampFieldCreatedAtBetweenOrderByTimestampFieldCreatedAtDesc(String serviceNo, OffsetDateTime startOfDay, OffsetDateTime endOfDay);
    Optional<SystemServiceNo> findSystemServiceNoByUuidAndTimestampFieldCreatedAtBetweenOrderByTimestampFieldCreatedAtDesc(UUID uuid, OffsetDateTime startOfDay, OffsetDateTime endOfDay);
}
