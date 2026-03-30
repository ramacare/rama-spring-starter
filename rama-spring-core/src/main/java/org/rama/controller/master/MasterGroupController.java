package org.rama.controller.master;

import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.master.MasterGroup;
import org.rama.entity.master.MasterItem;
import org.rama.entity.master.QMasterGroup;
import org.rama.repository.master.MasterGroupRepository;
import org.rama.repository.master.MasterItemRepository;
import org.rama.service.GenericEntityService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
@Slf4j
public class MasterGroupController {
	private final MasterGroupRepository masterGroupRepository;
	private final MasterItemRepository masterItemRepository;

	//@PreAuthorize("hasRole('ADMIN')")
	@MutationMapping(name="createMasterGroup")
	public Optional<MasterGroup> createEntity(@Argument Map<String,Object> input) {
		return GenericEntityService.createEntity(MasterGroup.class,masterGroupRepository,input,"groupKey");
	}

	@MutationMapping(name="updateMasterGroup")
	public Optional<MasterGroup> updateEntity(@Argument Map<String,Object> input) {
		return GenericEntityService.updateEntity(MasterGroup.class,masterGroupRepository,input,"groupKey");
	}

	@MutationMapping(name = "deleteMasterGroup")
	public Optional<MasterGroup> deleteEntity(@Argument Map<String, Object> input) {
		return GenericEntityService.softDeleteEntity(MasterGroup.class,masterGroupRepository,input,"groupKey");
	}

	@QueryMapping
	public PageableDTO<MasterGroup> masterGroupTerminatedPageable(@Argument PageableInput pageable) {
		return PageableDTO.of(masterGroupRepository.terminatedPageable(pageable.toPageRequest()));
	}

	@QueryMapping
	public PageableDTO<MasterGroup> masterGroupPageable(@Argument PageableInput pageable) {
		return GenericEntityService.findEntityPageable(masterGroupRepository,pageable);
	}

	@QueryMapping(name = "masterGroupByGeneral")
	public List<MasterGroup> masterGroupByGeneral() {
		QMasterGroup qMasterGroup = QMasterGroup.masterGroup;
		BooleanExpression predicate = qMasterGroup.groupKey.notLike("$$%");

		return (List<MasterGroup>) masterGroupRepository.findAll(predicate);
	}

	@QueryMapping(name = "masterGroupByGeneralPageable")
	public PageableDTO<MasterGroup> masterGroupByGeneralPageable(@Argument PageableInput pageable) {
		QMasterGroup qMasterGroup = QMasterGroup.masterGroup;
		BooleanExpression predicate = qMasterGroup.groupKey.notLike("$$%");

		return PageableDTO.of(masterGroupRepository.findAll(predicate,pageable.toPageRequest()));
	}

	@QueryMapping(name = "masterGroupBySystem")
	public List<MasterGroup> masterGroupBySystem() {
		QMasterGroup qMasterGroup = QMasterGroup.masterGroup;
		BooleanExpression predicate = qMasterGroup.groupKey.startsWith("$$");

		return (List<MasterGroup>) masterGroupRepository.findAll(predicate);
	}

	@QueryMapping(name = "masterGroupBySystemPageable")
	public PageableDTO<MasterGroup> masterGroupBySystemPageable(@Argument PageableInput pageable) {
		QMasterGroup qMasterGroup = QMasterGroup.masterGroup;
		BooleanExpression predicate = qMasterGroup.groupKey.startsWith("$$");

		return PageableDTO.of(masterGroupRepository.findAll(predicate,pageable.toPageRequest()));
	}

	@BatchMapping
	public Map<MasterGroup, List<MasterItem>> masterItems(List<MasterGroup> masterGroups) {
		return GenericEntityService.batchMappingRelation(masterGroups,MasterGroup::getGroupKey,MasterItem::getGroupKey, masterItemRepository::findMasterItemByGroupKeyIn);
	}
}
