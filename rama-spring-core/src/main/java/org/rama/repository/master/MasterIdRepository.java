package org.rama.repository.master;

import jakarta.persistence.LockModeType;
import org.rama.entity.master.MasterId;
import org.rama.repository.BaseRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface MasterIdRepository extends BaseRepository<MasterId, Integer>, QuerydslPredicateExecutor<MasterId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    MasterId findFirstByIdTypeAndPrefix(String idType, String prefix);

    boolean existsByIdTypeAndPrefix(String idType, String prefix);
}
