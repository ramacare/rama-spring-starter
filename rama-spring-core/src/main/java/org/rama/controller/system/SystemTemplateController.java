package org.rama.controller.system;

import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.system.QSystemTemplate;
import org.rama.entity.system.SystemTemplate;
import org.rama.repository.system.SystemTemplateRepository;
import org.rama.service.GenericEntityService;
import org.rama.util.QueryUtil;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class SystemTemplateController {
    private final SystemTemplateRepository systemTemplateRepository;

    @MutationMapping(name = "createSystemTemplate")
    public Optional<SystemTemplate> createEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.createEntity(SystemTemplate.class, systemTemplateRepository, input, "id");
    }

    @MutationMapping(name = "updateSystemTemplate")
    public Optional<SystemTemplate> updateEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.updateEntity(SystemTemplate.class, systemTemplateRepository, input, "id");
    }

    @MutationMapping(name = "deleteSystemTemplate")
    public Optional<SystemTemplate> deleteEntity(@Argument Map<String, Object> input) {
        return GenericEntityService.softDeleteEntity(SystemTemplate.class, systemTemplateRepository, input, "id");
    }

    @QueryMapping
    public PageableDTO<SystemTemplate> systemTemplatePageable(@Argument PageableInput pageable) {
        QSystemTemplate q = QSystemTemplate.systemTemplate;
        BooleanExpression predicate = QueryUtil.WithoutTerminated(q);
        return GenericEntityService.findEntityPageable(systemTemplateRepository, pageable, predicate);
    }

    @QueryMapping
    public PageableDTO<SystemTemplate> systemTemplateByExamplePageable(@Argument Map<String, Object> example, @Argument PageableInput pageable) {
        QSystemTemplate q = QSystemTemplate.systemTemplate;
        BooleanExpression predicate = QueryUtil.WithoutTerminated(q).and(QueryUtil.Example(example, q));
        return GenericEntityService.findEntityPageable(systemTemplateRepository, pageable, predicate);
    }

    @QueryMapping
    public PageableDTO<SystemTemplate> systemTemplateTerminatedPageable(@Argument PageableInput pageable) {
        return PageableDTO.of(systemTemplateRepository.terminatedPageable(pageable.toPageRequest()));
    }
}
