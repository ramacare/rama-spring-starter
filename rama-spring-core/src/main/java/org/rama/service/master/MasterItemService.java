package org.rama.service.master;

import org.rama.entity.master.MasterItem;
import org.rama.repository.master.MasterItemRepository;

import java.util.Collection;
import java.util.List;

public class MasterItemService {
    private final MasterItemRepository masterItemRepository;

    public MasterItemService(MasterItemRepository masterItemRepository) {
        this.masterItemRepository = masterItemRepository;
    }

    public MasterItem getMasterItem(String groupKey, String itemCode) {
        return masterItemRepository.findMasterItemByGroupKeyAndItemCode(groupKey, itemCode).orElse(null);
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
}
