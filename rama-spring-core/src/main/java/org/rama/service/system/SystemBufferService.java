package org.rama.service.system;

import lombok.RequiredArgsConstructor;
import org.rama.entity.system.SystemBuffer;
import org.rama.repository.system.SystemBufferRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class SystemBufferService {

    private final SystemBufferRepository repository;

    /**
     * Enqueue a payload for asynchronous dispatch. Must be called from within an
     * entity transaction (e.g. listener afterCommit synchronization). The INSERT
     * participates in the calling transaction — if the transaction rolls back, the
     * buffer row is rolled back too, preventing phantom dispatches.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SystemBuffer enqueue(String bufferType, String payload, String target) {
        SystemBuffer row = new SystemBuffer();
        row.setBufferType(bufferType);
        row.setPayload(payload);
        row.setTarget(target);
        return repository.save(row);
    }
}
