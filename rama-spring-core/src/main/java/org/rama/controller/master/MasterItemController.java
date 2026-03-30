package org.rama.controller.master;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.rama.entity.PageableDTO;
import org.rama.entity.PageableInput;
import org.rama.entity.master.MasterItem;
import org.rama.entity.master.QMasterItem;
import org.rama.repository.master.MasterItemRepository;
import org.rama.service.GenericEntityService;
import org.rama.service.GenericMongoService;
import org.rama.util.MongoDBUtil;
import org.rama.util.QueryUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Controller
@RequiredArgsConstructor
public class MasterItemController {
	private final MasterItemRepository masterItemRepository;
	private final JPAQueryFactory queryFactory;
	private final ObjectProvider<GenericMongoService> genericMongoServiceProvider;

	@MutationMapping(name = "createMasterItem")
	public Optional<MasterItem> createEntity(@Argument Map<String, Object> input) {
		return GenericEntityService.createEntity(MasterItem.class, masterItemRepository, input, "id");
	}

	@MutationMapping(name = "updateMasterItem")
	public Optional<MasterItem> updateEntity(@Argument Map<String, Object> input) {
		return GenericEntityService.updateEntity(MasterItem.class, masterItemRepository, input,"id");
	}

	@MutationMapping(name = "deleteMasterItem")
	public Optional<MasterItem> deleteEntity(@Argument Map<String, Object> input) {
		return GenericEntityService.softDeleteEntity(MasterItem.class,masterItemRepository,input,"id");
	}

	@QueryMapping
	public PageableDTO<MasterItem> masterItemByGroupKeyPageable(@Argument String groupKey,@Argument PageableInput pageable) {
		return PageableDTO.of(masterItemRepository.findMasterItemByGroupKey(groupKey,pageable.toPageRequest()));
	}

	@QueryMapping(name = "masterItemByGroupKeyAndFilterText")
	public List<MasterItem> masterItemByGroupKeyAndFilterText(@Argument String groupKey, @Argument String filterText) {
		QMasterItem qMasterItem = QMasterItem.masterItem;
		BooleanExpression predicate = QueryUtil.WithoutTerminated(qMasterItem).and(qMasterItem.groupKey.eq(groupKey));
		if (filterText != null) {
			predicate = predicate.and(qMasterItem.filterText.eq(filterText));
		} else {
			predicate = predicate.and(qMasterItem.filterText.isNull());
		}
        return StreamSupport.stream(
                masterItemRepository.findAll(
                        predicate,
                        qMasterItem.ordering.asc(),
                        qMasterItem.itemCode.asc()
                ).spliterator(), false
        ).collect(Collectors.toList());
	}

	@QueryMapping(name = "masterItemByExampleFirst")
	public Optional<MasterItem> masterItemByExampleFirst(@Argument Map<String, Object> example) {
		QMasterItem qMasterItem = QMasterItem.masterItem;
		BooleanExpression predicate = QueryUtil.WithoutTerminated(qMasterItem).and(QueryUtil.Example(example,qMasterItem));
		return Optional.ofNullable(queryFactory.selectFrom(qMasterItem)
				.where(predicate)
				.orderBy(qMasterItem.id.desc())
				.fetchFirst());
	}

	@QueryMapping
	public List<MasterItem> masterItemByGroupKeyAndItemCodeIn(@Argument String groupKey, @Argument List<String> itemCodes) {
		if (itemCodes==null || itemCodes.isEmpty()) return  masterItemRepository.findMasterItemByGroupKey(groupKey);
		else return masterItemRepository.findMasterItemByGroupKeyAndItemCodeIn(groupKey,itemCodes);
	}

	@QueryMapping
	public PageableDTO<MasterItem> masterItemTerminatedPageable(@Argument PageableInput pageable) {
		return PageableDTO.of(masterItemRepository.terminatedPageable(pageable.toPageRequest()));
	}

	@QueryMapping
	public Optional<MasterItem> masterItemByGroupKeyAndItemCode(@Argument String groupKey, @Argument String itemCode) {
		return masterItemRepository.findMasterItemByGroupKeyAndItemCode(groupKey, itemCode);
	}

    @QueryMapping(name = "masterItemByGroupKey")
    public List<MasterItem> masterItemByGroupKey(@Argument String groupKey) {
        return masterItemRepository.findMasterItemByGroupKeyOrderByOrderingAndItemCodeAsc(groupKey);
    }

	@QueryMapping
	public PageableDTO<MasterItem> masterItemByExamplePageable(@Argument Map<String, Object> example, @Argument PageableInput pageable) {
		QMasterItem qMasterItem = QMasterItem.masterItem;
		BooleanExpression predicate = QueryUtil.WithoutTerminated(qMasterItem).and(QueryUtil.Example(example, qMasterItem));
		return GenericEntityService.findEntityPageable(masterItemRepository, pageable, predicate);
	}

	@QueryMapping
	public List<MasterItem> masterItemByCriteria(@Argument List<Map<String, Object>> criteria) {
		Criteria mongoCriteria = MongoDBUtil.criteriaBuilder(MongoDBUtil.withoutTerminated(criteria));
		GenericMongoService genericMongoService = requireGenericMongoService();
		List<org.rama.mongo.document.MasterItem> result = genericMongoService.findEntity(org.rama.mongo.document.MasterItem.class, mongoCriteria);

		QMasterItem qMasterItem = QMasterItem.masterItem;
		BooleanExpression predicate = qMasterItem.id.in(result.stream().map(MasterItem::getId).collect(Collectors.toList())).and(QueryUtil.WithoutTerminated(qMasterItem));
		return (List<MasterItem>) masterItemRepository.findAll(predicate);
	}

	@QueryMapping
	public PageableDTO<MasterItem> masterItemByCriteriaPageable(@Argument List<Map<String, Object>> criteria, @Argument PageableInput pageable) {
		Criteria mongoCriteria = MongoDBUtil.criteriaBuilder(MongoDBUtil.withoutTerminated(criteria));
		GenericMongoService genericMongoService = requireGenericMongoService();
		PageableDTO<org.rama.mongo.document.MasterItem> result = genericMongoService.findEntityIdPageable(org.rama.mongo.document.MasterItem.class, pageable, mongoCriteria);

		QMasterItem qMasterItem = QMasterItem.masterItem;
		BooleanExpression predicate = qMasterItem.id.in(result.getData().stream().map(MasterItem::getId).collect(Collectors.toList())).and(QueryUtil.WithoutTerminated(qMasterItem));
		return PageableDTO.of(result.getMeta(), (List<MasterItem>) masterItemRepository.findAll(predicate, pageable.toPageRequest().getSort()));
	}

	private GenericMongoService requireGenericMongoService() {
		GenericMongoService genericMongoService = genericMongoServiceProvider.getIfAvailable();
		if (genericMongoService == null) {
			throw new IllegalStateException("GenericMongoService is not available. Enable rama.mongo or provide a MongoTemplate-backed starter Mongo configuration.");
		}
		return genericMongoService;
	}
}
