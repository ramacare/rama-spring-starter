package org.rama.repository.revision;

import org.rama.entity.Revision;
import org.rama.repository.BaseRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@GraphQlRepository
public interface RevisionRepository extends BaseRepository<Revision, Long>, QuerydslPredicateExecutor<Revision> {
    List<Revision> findAllByRevisionEntityAndMrnOrderByRevisionDatetimeDesc(String revisionEntity, String mrn);
    List<Revision> findAllByRevisionEntityInAndMrnOrderByRevisionDatetimeDesc(List<String> revisionEntity, String mrn);
    List<Revision> findAllByRevisionKeyOrderByRevisionDatetimeDesc(String revisionKey);
    Optional<Revision> findFirstByRevisionKeyAndRevisionDatetimeLessThanEqualOrderByRevisionDatetimeDesc(
            String revisionKey, OffsetDateTime revisionDatetime);
}
