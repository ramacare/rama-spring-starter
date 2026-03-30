package org.rama.controller.system;

import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.system.SystemParameter;
import org.rama.repository.system.SystemParameterRepository;
import org.rama.service.GenericEntityService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class SystemParameterController {
    private final SystemParameterRepository systemParameterRepository;

    @MutationMapping(name = "createSystemParameter")
    public Optional<SystemParameter> createEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.createEntity(SystemParameter.class, systemParameterRepository, input, "parameterKey");
    }

    @MutationMapping(name = "updateSystemParameter")
    public Optional<SystemParameter> updateEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.updateEntity(SystemParameter.class, systemParameterRepository, input,"parameterKey");
    }

    @QueryMapping
    public PageableDTO<SystemParameter> systemParameterPageable(@Argument PageableInput pageable) {
        return GenericEntityService.findEntityPageable(systemParameterRepository,pageable);
    }
}
