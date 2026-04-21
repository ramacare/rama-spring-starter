package org.rama.service.master;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.rama.entity.master.MasterItem;
import org.rama.entity.master.QMasterItem;
import org.rama.repository.master.MasterItemRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MasterItemService {
    private final MasterItemRepository masterItemRepository;
    private final JPAQueryFactory queryFactory;
    private final Map<String, CachedItem> itemCache = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    public MasterItemService(MasterItemRepository masterItemRepository, JPAQueryFactory queryFactory) {
        this.masterItemRepository = masterItemRepository;
        this.queryFactory = queryFactory;
    }

    public MasterItem getMasterItem(String groupKey, String itemCode) {
        String cacheKey = groupKey + "|" + itemCode;
        CachedItem cached = itemCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.item;
        }
        MasterItem item = masterItemRepository.findMasterItemByGroupKeyAndItemCode(groupKey, itemCode).orElse(null);
        itemCache.put(cacheKey, new CachedItem(item));
        return item;
    }

    public MasterItem getMasterItemWithTerminated(String groupKey, String itemCode) {
        return masterItemRepository.findMasterItemByGroupKeyAndItemCodeWithTerminated(groupKey, itemCode).orElse(null);
    }

    public List<MasterItem> getMasterItems(String groupKey) {
        return masterItemRepository.findMasterItemByGroupKeyOrderByOrderingAndItemCodeAsc(groupKey);
    }

    public List<MasterItem> getMasterItems(String groupKey, Collection<String> itemCodes) {
        return masterItemRepository.findMasterItemByGroupKeyAndItemCodeIn(groupKey, itemCodes);
    }

    public String translateMaster(String groupKey, String itemCode) {
        return translateMaster(groupKey, itemCode, "TH");
    }

    public String translateMaster(String groupKey, String itemCode, String lang) {
        MasterItem item = getMasterItem(groupKey, itemCode);
        if (item == null) {
            return "";
        }
        return "EN".equalsIgnoreCase(lang) ? item.getItemValueAlternative() : item.getItemValue();
    }

    public String translateMasterWithTerminated(String groupKey, String itemCode) {
        return translateMasterWithTerminated(groupKey, itemCode, "TH");
    }

    public String translateMasterWithTerminated(String groupKey, String itemCode, String lang) {
        MasterItem item = getMasterItemWithTerminated(groupKey, itemCode);
        if (item == null) {
            return "";
        }
        return "EN".equalsIgnoreCase(lang) ? item.getItemValueAlternative() : item.getItemValue();
    }

    public String getMasterItemCode(String groupKey, String itemValue, String filterText) {
        if (itemValue == null || groupKey == null) {
            return null;
        }
        QMasterItem q = QMasterItem.masterItem;
        BooleanExpression predicate = q.groupKey.eq(groupKey)
                .and(q.itemValue.eq(itemValue).or(q.itemValueAlternative.eq(itemValue)));
        if (filterText != null) {
            predicate = predicate.and(q.filterText.eq(filterText));
        }
        return queryFactory.select(q.itemCode).from(q)
                .where(predicate)
                .orderBy(q.id.desc())
                .fetchFirst();
    }

    public void evictCache() {
        itemCache.clear();
    }

    private record CachedItem(MasterItem item, Instant cachedAt) {
        CachedItem(MasterItem item) {
            this(item, Instant.now());
        }

        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(CACHE_TTL));
        }
    }
}
