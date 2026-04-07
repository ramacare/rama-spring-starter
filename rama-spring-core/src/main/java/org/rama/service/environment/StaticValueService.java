package org.rama.service.environment;

import org.rama.entity.master.MasterItem;
import org.rama.service.master.MasterItemService;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class StaticValueService implements StaticValueResolver {
    private final MasterItemService masterItemService;
    private final String groupKey;
    private final String currentUsernameFallbackKey;
    private final Duration ttl;
    private volatile Map<String, String> staticValues = Map.of();

    private volatile Instant lastRetrievedTime;

    public StaticValueService(MasterItemService masterItemService, String groupKey, String currentUsernameFallbackKey, Duration ttl) {
        this.masterItemService = masterItemService;
        this.groupKey = groupKey;
        this.currentUsernameFallbackKey = currentUsernameFallbackKey;
        this.ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        refreshValues();
    }

    @Override
    public String getStaticValue(String itemCode) {
        if (isExpired()) {
            refreshValues();
        }
        return staticValues.get(itemCode);
    }

    @Override
    public String getCurrentUsernameFallback() {
        if (currentUsernameFallbackKey == null || currentUsernameFallbackKey.isBlank()) {
            return null;
        }
        return getStaticValue(currentUsernameFallbackKey);
    }

    public synchronized void refreshValues() {
        lastRetrievedTime = Instant.now();
        Map<String, String> newValues = new HashMap<>();
        for (MasterItem masterItem : masterItemService.getMasterItems(groupKey)) {
            newValues.put(masterItem.getItemCode(), masterItem.getItemValue());
        }
        staticValues = Map.copyOf(newValues);
    }

    private boolean isExpired() {
        return lastRetrievedTime == null || Instant.now().isAfter(lastRetrievedTime.plus(ttl));
    }
}
