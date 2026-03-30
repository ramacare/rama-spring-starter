package org.rama.repository.system;

import org.rama.entity.system.SystemTemplate;
import org.rama.repository.BaseRepository;
import org.rama.repository.SoftDeleteRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface SystemTemplateRepository extends BaseRepository<SystemTemplate, String>,
        SoftDeleteRepository<SystemTemplate, String>,
        QuerydslPredicateExecutor<SystemTemplate> {
}
