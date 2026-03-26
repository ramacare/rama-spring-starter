package org.rama.starter.repository.master;

import org.rama.starter.entity.master.MasterItem;
import org.rama.starter.repository.SoftDeleteRepository;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MasterItemRepository extends SoftDeleteRepository<MasterItem, String> {
    default List<MasterItem> findMasterItemByGroupKey(String groupKey) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.equal(root.get("groupKey"), groupKey)));
    }

    default List<MasterItem> findMasterItemByGroupKeyAndItemCodeIn(String groupKey, Collection<String> itemCodes) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), root.get("itemCode").in(itemCodes))));
    }

    default Optional<MasterItem> findMasterItemByGroupKeyAndItemCode(String groupKey, String itemCode) {
        return findOne(withoutTerminated().and((root, query, cb) -> cb.and(cb.equal(root.get("groupKey"), groupKey), cb.equal(root.get("itemCode"), itemCode))));
    }

    default List<MasterItem> findMasterItemByGroupKeyOrderByOrderingAndItemCodeAsc(String groupKey) {
        return findAll(withoutTerminated().and((root, query, cb) -> cb.equal(root.get("groupKey"), groupKey)), Sort.by(Sort.Direction.ASC, "ordering", "itemCode"));
    }
}
