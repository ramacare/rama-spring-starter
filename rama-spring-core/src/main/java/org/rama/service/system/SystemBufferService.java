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
     * Enqueue a payload for asynchronous dispatch. Typically called from a listener's
     * afterCommit synchronization (post-entity-commit), so this method joins the
     * caller's transaction if one is active, otherwise opens its own. The row is
     * durable once this method returns; no further coordination with the entity
     * transaction is needed (it has already committed by the time we get here).
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SystemBuffer enqueue(String bufferType, String payload, String target) {
        SystemBuffer row = new SystemBuffer();
        row.setBufferType(bufferType);
        row.setPayload(payload);
        row.setTarget(target);
        return repository.save(row);
    }
}
