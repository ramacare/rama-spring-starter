package org.rama.controller.system;

import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.system.ClientUserConfig;
import org.rama.entity.system.QClientUserConfig;
import org.rama.repository.system.ClientUserConfigRepository;
import org.rama.service.GenericEntityService;
import org.rama.service.system.ClientUserConfigService;
import org.rama.util.QueryUtil;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class ClientUserConfigController {
    private final GenericEntityService genericEntityService;
    private final ClientUserConfigRepository clientUserConfigRepository;
    private final ClientUserConfigService clientUserConfigService;

    @MutationMapping(name = "createClientUserConfig")
    public Optional<ClientUserConfig> createEntity(@Argument Map<String, Object> input) {
        return genericEntityService.createEntity(ClientUserConfig.class, clientUserConfigRepository, input, "id");
    }

    @MutationMapping(name = "updateClientUserConfig")
    public Optional<ClientUserConfig> updateEntity(@Argument Map<String, Object> input) {
        return genericEntityService.updateEntity(ClientUserConfig.class, clientUserConfigRepository, input, "id");
    }

    @MutationMapping(name = "deleteClientUserConfig")
    public Optional<ClientUserConfig> deleteEntity(@Argument Map<String, Object> input) {
        return genericEntityService.hardDeleteEntity(ClientUserConfig.class, clientUserConfigRepository, input, "id");
    }

    @QueryMapping(name = "clientUserConfigByClientUsername")
    public ClientUserConfig clientUserConfigByClientUsername(@Argument String clientUsername) {
        return clientUserConfigService.retrieveOrRegister(clientUsername);
    }

    @QueryMapping
    public PageableDTO<ClientUserConfig> clientUserConfigPageable(@Argument PageableInput pageable) {
        return PageableDTO.of(clientUserConfigRepository.findAll(pageable.toPageRequest()));
    }

    @QueryMapping
    public PageableDTO<ClientUserConfig> clientUserConfigByExamplePageable(@Argument Map<String, Object> example, @Argument PageableInput pageable) {
        QClientUserConfig qClientUserConfig = QClientUserConfig.clientUserConfig;
        BooleanExpression predicate = QueryUtil.Example(example, qClientUserConfig);
        return PageableDTO.of(clientUserConfigRepository.findAll(predicate, pageable.toPageRequest()));
    }
}
