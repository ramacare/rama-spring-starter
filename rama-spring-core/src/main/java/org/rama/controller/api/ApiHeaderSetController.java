package org.rama.controller.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.api.ApiHeaderSet;
import org.rama.repository.api.ApiHeaderSetRepository;
import org.rama.service.GenericEntityService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ApiHeaderSetController {
    private final ApiHeaderSetRepository apiHeaderSetRepository;

    @MutationMapping(name = "createApiHeaderSet")
    public Optional<ApiHeaderSet> createEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.createEntity(ApiHeaderSet.class, apiHeaderSetRepository, input, "id");
    }

    @MutationMapping(name = "updateApiHeaderSet")
    public Optional<ApiHeaderSet> updateEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.updateEntity(ApiHeaderSet.class, apiHeaderSetRepository, input, "id");
    }

    @QueryMapping
    public Optional<ApiHeaderSet> apiHeaderSetById(@Argument String id) {
        return apiHeaderSetRepository.findById(id);
    }
}
