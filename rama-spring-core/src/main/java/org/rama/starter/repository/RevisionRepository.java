package org.rama.starter.repository;

import org.rama.starter.entity.Revision;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

public interface RevisionRepository extends BaseRepository<Revision, Long>, QuerydslPredicateExecutor<Revision> {
    List<Revision> findAllByRevisionEntityAndMrnOrderByRevisionDatetimeDesc(String revisionEntity, String mrn);
    List<Revision> findAllByRevisionEntityInAndMrnOrderByRevisionDatetimeDesc(List<String> revisionEntity, String mrn);
    List<Revision> findAllByRevisionKeyOrderByRevisionDatetimeDesc(String revisionKey);
}
