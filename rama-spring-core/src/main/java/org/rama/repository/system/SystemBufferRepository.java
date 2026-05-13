package org.rama.repository.system;

import org.rama.entity.system.SystemBuffer;
import org.rama.repository.BaseRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface SystemBufferRepository extends BaseRepository<SystemBuffer, Long> {
    List<SystemBuffer> findByBufferTypeOrderByIdAsc(String bufferType, Pageable pageable);
    long countByBufferType(String bufferType);
}
