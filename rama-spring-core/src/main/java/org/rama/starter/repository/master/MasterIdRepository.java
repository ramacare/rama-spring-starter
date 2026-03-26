package org.rama.starter.repository.master;

import jakarta.persistence.LockModeType;
import org.rama.starter.entity.master.MasterId;
import org.rama.starter.repository.BaseRepository;
import org.springframework.data.jpa.repository.Lock;

public interface MasterIdRepository extends BaseRepository<MasterId, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    MasterId findFirstByIdTypeAndPrefix(String idType, String prefix);
}
