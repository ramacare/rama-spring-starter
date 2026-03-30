package org.rama.controller.system;

import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.system.ClientConfig;
import org.rama.entity.system.QClientConfig;
import org.rama.repository.system.ClientConfigRepository;
import org.rama.service.GenericEntityService;
import org.rama.service.system.ClientConfigService;
import org.rama.util.QueryUtil;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class ClientConfigController {
    private final ClientConfigRepository clientConfigRepository;
    private final ClientConfigService clientConfigService;

    @MutationMapping(name = "createClientConfig")
    public Optional<ClientConfig> createEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.createEntity(ClientConfig.class, clientConfigRepository, input,"id");
    }

    @MutationMapping(name = "updateClientConfig")
    public Optional<ClientConfig> updateEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.updateEntity(ClientConfig.class, clientConfigRepository, input,"id");
    }

    @MutationMapping(name = "deleteClientConfig")
    public Optional<ClientConfig> deleteEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.hardDeleteEntity(ClientConfig.class,clientConfigRepository,input,"id");
    }

    @QueryMapping(name = "clientConfigByComputerNameAndFingerprint")
    public ClientConfig clientConfigByComputerNameAndFingerprint(@Argument String computerName, @Argument String fingerprint) {
        return clientConfigService.retrieveOrRegister(computerName, fingerprint);
    }

    @QueryMapping
    public PageableDTO<ClientConfig> clientConfigPageable(@Argument PageableInput pageable) {
        return PageableDTO.of(clientConfigRepository.findAll(pageable.toPageRequest()));
    }

    @QueryMapping
    public PageableDTO<ClientConfig> clientConfigByExamplePageable(@Argument Map<String,Object> example,@Argument PageableInput pageable) {
        QClientConfig qClientConfig = QClientConfig.clientConfig;
        BooleanExpression predicate = QueryUtil.Example(example,qClientConfig);
        return PageableDTO.of(clientConfigRepository.findAll(predicate,pageable.toPageRequest()));
    }
}
