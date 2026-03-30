package org.rama.repository.master;

import org.rama.entity.master.MasterGroup;
import org.rama.repository.SoftDeleteRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface MasterGroupRepository extends SoftDeleteRepository<MasterGroup, String>, QuerydslPredicateExecutor<MasterGroup> {
}
