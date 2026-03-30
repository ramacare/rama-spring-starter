package org.rama.service.document.template.hooks;

import org.rama.service.document.template.ReplacementObjectHook;
import org.rama.service.master.MasterItemService;

import java.util.List;
import java.util.Map;

public class MasterHooks implements ReplacementObjectHook {
    private final MasterItemService masterItemService;

    public MasterHooks(MasterItemService masterItemService) {
        this.masterItemService = masterItemService;
    }

    @Override
    public Object process(Object replacement, Map<String, String> attributes) {
        String groupKey = attributes.get("groupKey");
        if (replacement == null || !attributes.containsKey("master") || groupKey == null) {
            return replacement;
        }
        String lang = attributes.getOrDefault("lang", "TH");
        boolean showCode = attributes.containsKey("showCode");
        if (replacement instanceof String value) {
            return render(groupKey, value, lang, showCode);
        }
        if (replacement instanceof List<?> values) {
            return values.stream().map(value -> render(groupKey, String.valueOf(value), lang, showCode)).toList();
        }
        return replacement;
    }

    private String render(String groupKey, String itemCode, String lang, boolean showCode) {
        String translated = masterItemService.translateMaster(groupKey, itemCode, lang);
        return showCode ? itemCode + "-" + translated : translated;
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
