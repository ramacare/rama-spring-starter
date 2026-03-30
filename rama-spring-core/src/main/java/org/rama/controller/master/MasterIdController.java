package org.rama.controller.master;

import graphql.GraphQLException;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.master.MasterId;
import org.rama.repository.master.MasterIdRepository;
import org.rama.service.GenericEntityService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class MasterIdController {
    private final MasterIdRepository masterIdRepository;

    @QueryMapping
    public List<MasterId> masterId() {
        return masterIdRepository.findAll();
    }

    @QueryMapping
    public PageableDTO<MasterId> masterIdPageable(@Argument PageableInput pageable) {
        return GenericEntityService.findEntityPageable(masterIdRepository, pageable);
    }

    @MutationMapping
    public Optional<MasterId> createMasterId(@Argument Map<String, Object> input) {
        String idType = (String) input.get("idType");
        String prefix = (String) input.get("prefix");

        if (masterIdRepository.existsByIdTypeAndPrefix(idType, prefix)) {
            throw new GraphQLException("Duplicate idType and prefix");
        }

        if (!input.containsKey("runningNumber") || input.get("runningNumber") == null) {
            input.put("runningNumber", 0);
        }

        return GenericEntityService.createEntity(MasterId.class, masterIdRepository, input, "id");
    }

    @MutationMapping
    public Optional<MasterId> updateMasterId(@Argument Map<String, Object> input) {
        return GenericEntityService.updateEntity(MasterId.class, masterIdRepository, input, "id");
    }
}
