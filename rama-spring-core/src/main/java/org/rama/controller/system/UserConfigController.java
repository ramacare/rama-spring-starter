package org.rama.controller.system;

import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.system.QUserConfig;
import org.rama.entity.system.UserConfig;
import org.rama.repository.system.UserConfigRepository;
import org.rama.service.GenericEntityService;
import org.rama.service.system.UserConfigService;
import org.rama.util.QueryUtil;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class UserConfigController {
    private final GenericEntityService genericEntityService;
    private final UserConfigRepository userConfigRepository;
    private final UserConfigService userConfigService;

    @MutationMapping(name = "createUserConfig")
    public Optional<UserConfig> createEntity(@Argument Map<String, Object> input) {
        return genericEntityService.createEntity(UserConfig.class, userConfigRepository, input, "id");
    }

    @MutationMapping(name = "updateUserConfig")
    public Optional<UserConfig> updateEntity(@Argument Map<String, Object> input) {
        return genericEntityService.updateEntity(UserConfig.class, userConfigRepository, input, "id");
    }

    @MutationMapping(name = "deleteUserConfig")
    public Optional<UserConfig> deleteEntity(@Argument Map<String, Object> input) {
        return genericEntityService.hardDeleteEntity(UserConfig.class, userConfigRepository, input, "id");
    }

    /** The caller's own configuration. Null when no principal can be resolved. */
    @QueryMapping(name = "userConfig")
    public UserConfig userConfig() {
        return userConfigService.retrieveOrRegisterCurrent();
    }

    @QueryMapping(name = "userConfigByUsername")
    public UserConfig userConfigByUsername(@Argument String username) {
        return userConfigService.retrieveOrRegister(username);
    }

    @QueryMapping
    public PageableDTO<UserConfig> userConfigPageable(@Argument PageableInput pageable) {
        return PageableDTO.of(userConfigRepository.findAll(pageable.toPageRequest()));
    }

    @QueryMapping
    public PageableDTO<UserConfig> userConfigByExamplePageable(@Argument Map<String, Object> example, @Argument PageableInput pageable) {
        QUserConfig qUserConfig = QUserConfig.userConfig;
        BooleanExpression predicate = QueryUtil.Example(example, qUserConfig);
        return PageableDTO.of(userConfigRepository.findAll(predicate, pageable.toPageRequest()));
    }
}
