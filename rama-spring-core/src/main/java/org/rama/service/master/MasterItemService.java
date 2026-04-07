package org.rama.service.master;

import org.rama.entity.master.MasterItem;
import org.rama.repository.master.MasterItemRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MasterItemService {
    private final MasterItemRepository masterItemRepository;
    private final Map<String, CachedItem> itemCache = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    public MasterItemService(MasterItemRepository masterItemRepository) {
        this.masterItemRepository = masterItemRepository;
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

    public String getMasterItemCode(String groupKey, String itemValue, String filterText) {
        if (itemValue == null || groupKey == null) {
            return null;
        }

        List<MasterItem> items = getMasterItems(groupKey);
        String result = null;
        for (MasterItem item : items) {
            boolean valueMatches = itemValue.equals(item.getItemValue()) || itemValue.equals(item.getItemValueAlternative());
            if (!valueMatches) {
                continue;
            }
            if (filterText != null && !filterText.equals(item.getFilterText())) {
                continue;
            }
            result = item.getItemCode();
        }
        return result;
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
