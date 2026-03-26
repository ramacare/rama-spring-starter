package org.rama.starter.service.master;

import org.rama.starter.entity.master.MasterItem;
import org.rama.starter.repository.master.MasterItemRepository;

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

    public String translateMaster(String groupKey, String itemCode, String lang) {
        MasterItem item = getMasterItem(groupKey, itemCode);
        if (item == null) {
            return "";
        }
        return "EN".equalsIgnoreCase(lang) ? item.getItemValueAlternative() : item.getItemValue();
    }
}
