package org.rama.repository.api;

import org.rama.entity.api.ApiHeaderSet;
import org.rama.repository.BaseRepository;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface ApiHeaderSetRepository extends BaseRepository<ApiHeaderSet, String> {
}
