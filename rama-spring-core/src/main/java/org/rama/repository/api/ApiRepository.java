package org.rama.repository.api;

import org.rama.entity.api.Api;
import org.rama.repository.BaseRepository;
import org.springframework.graphql.data.GraphQlRepository;

@GraphQlRepository
public interface ApiRepository extends BaseRepository<Api, String> {
}
