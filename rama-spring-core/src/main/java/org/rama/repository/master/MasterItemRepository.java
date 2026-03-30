package org.rama.repository.master;

import org.rama.entity.master.MasterItem;
import org.rama.repository.SoftDeleteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.graphql.data.GraphQlRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@GraphQlRepository
public interface MasterItemRepository extends SoftDeleteRepository<MasterItem, String>, QuerydslPredicateExecutor<MasterItem> {
    default List<MasterItem> findMasterItemByGroupKeyIn(Collection<String> groupKeys) {
        return findAll(withoutTerminated().and((root, query, cb) -> root.get("groupKey").in(groupKeys)));
    }

    default List<MasterItem> findMasterItemByGroupKey(String groupKey) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.equal(root.get("groupKey"), groupKey)));
    }

    default List<MasterItem> findAllPropertiesByGroupKey(String groupKey) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.equal(root.get("groupKey"), groupKey)));
    }

    default List<MasterItem> findAllByGroupKeyAndFilterText(String groupKey, String filterText) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), cb.equal(root.get("filterText"), filterText))));
    }

    default Page<MasterItem> findMasterItemByGroupKey(String groupKey, Pageable pageable) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.equal(root.get("groupKey"), groupKey)), pageable);
    }

    default List<MasterItem> findMasterItemByGroupKeyAndItemCodeIn(String groupKey, Collection<String> itemCodes) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), root.get("itemCode").in(itemCodes))));
    }

    default Optional<MasterItem> findMasterItemByGroupKeyAndItemCode(String groupKey, String itemCode) {
        return findOne(withoutTerminated().and((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), cb.equal(root.get("itemCode"), itemCode))));
    }

    default Optional<MasterItem> findMasterItemByGroupKeyAndItemCodeWithTerminated(String groupKey, String itemCode) {
        return findOne((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), cb.equal(root.get("itemCode"), itemCode)));
    }

    default Optional<MasterItem> findFirstByGroupKeyAndFilterText(String groupKey, String filterText) {
        return findOne(withoutTerminated().and((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), cb.equal(root.get("filterText"), filterText))));
    }

    default List<MasterItem> findMasterItemByGroupKeyOrderByOrderingAndItemCodeAsc(String groupKey) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.equal(root.get("groupKey"), groupKey)), Sort.by(Sort.Direction.ASC, "ordering", "itemCode"));
    }
}
