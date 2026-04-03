package org.rama.controller.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.api.Api;
import org.rama.repository.api.ApiRepository;
import org.rama.service.GenericEntityService;
import org.springframework.data.domain.Example;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ApiController {
    private final GenericEntityService genericEntityService;
    private final ApiRepository apiRepository;
    private final JsonMapper jsonMapper;

    @MutationMapping(name = "createApi")
    public Optional<Api> createEntity(@Argument Map<String, Object> input) {
        return genericEntityService.createEntity(Api.class, apiRepository, input, "id");
    }

    @MutationMapping(name = "updateApi")
    public Optional<Api> updateEntity(@Argument Map<String, Object> input) {
        return genericEntityService.updateEntity(Api.class, apiRepository, input, "id");
    }

    @QueryMapping
    public PageableDTO<Api> apiPageable(@Argument PageableInput pageable) {
        return GenericEntityService.findEntityPageable(apiRepository,pageable);
    }

    @QueryMapping
    public List<Api> apiByExample(@Argument Map<String, Object> example) {
        Api filter = jsonMapper.convertValue(example, Api.class);
        return apiRepository.findAll(Example.of(filter));
    }

    @QueryMapping
    public PageableDTO<Api> apiByExamplePageable(@Argument Map<String, Object> example,@Argument PageableInput pageable) {
        Api filter = jsonMapper.convertValue(example, Api.class);
        return GenericEntityService.findEntityPageable(apiRepository, pageable, filter);
    }
}
